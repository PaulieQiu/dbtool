package pan.dromara.dbtool.lsp;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.service.IDbConnService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL LSP (Language Server Protocol) 轻量代理实现(P3)。
 * <p>参考 duck-sqllsp 的「后端持有会话 + 元数据 + 前端请求」模型，做低成本实现：</p>
 * <ul>
 *   <li>startSession/stopSession：按连接建立会话并加载库表列元数据</li>
 *   <li>completion：基于元数据返回 SQL 关键字 + 表名 + 列名补全</li>
 *   <li>hover：查看表/列的元数据(类型/注释)</li>
 *   <li>diagnostics：轻量语法与风险诊断(注释剥离/危险语句/无 LIMIT 提示)</li>
 *   <li>refreshMetadata：重载会话元数据</li>
 * </ul>
 * <p>纯内存、无子进程、无外部依赖；会话与元数据在内存缓存，连接变更需刷新。</p>
 *
 * @author doubao
 */
@Slf4j
@Service
public class SqlLspServiceImpl implements SqlLspService {

    /** 常用 SQL 关键字(供补全) */
    private static final List<String> KEYWORDS = List.of(
        "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "LIMIT", "HAVING",
        "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "OUTER JOIN", "ON",
        "AND", "OR", "NOT", "IN", "LIKE", "BETWEEN", "IS NULL", "IS NOT NULL",
        "INSERT INTO", "UPDATE", "DELETE FROM", "VALUES", "SET", "CREATE TABLE",
        "ALTER TABLE", "DROP TABLE", "INDEX", "UNION", "UNION ALL", "DISTINCT",
        "AS", "ASC", "DESC", "COUNT(*)", "MAX", "MIN", "SUM", "AVG");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** 紧邻标识符的点号链(用于识别 schema.table) */
    private static final Pattern DOTTED = Pattern.compile("(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
    /** SQL 保留字(表名提取时过滤, 避免函数名/关键字误报为表) */
    private static final Set<String> SQL_RESERVED = Set.of(
        "select", "from", "where", "and", "or", "not", "in", "like", "between", "join", "inner",
        "left", "right", "outer", "on", "as", "set", "values", "update", "delete", "insert", "into",
        "table", "group", "by", "order", "having", "limit", "offset", "union", "all", "distinct",
        "asc", "desc", "case", "when", "then", "else", "end", "exists", "is", "null", "true",
        "false", "with", "dual");

    /** 会话空闲超时(30 分钟无请求即清理, 防 stopSession 缺失导致的内存泄漏) */
    private static final long SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    @Value("${dbtool.ai.allowConns:}")
    private String allowConns;

    private final IDbConnService connService;
    private final IDbMetaService metaService;

    /** 会话注册表 (sessionId -> LspSession) */
    private final Map<String, LspSession> sessions = new ConcurrentHashMap<>();

    public SqlLspServiceImpl(IDbConnService connService, IDbMetaService metaService) {
        this.connService = connService;
        this.metaService = metaService;
    }

    // ================= 会话生命周期 =================

    @Override
    public String startSession(Long connId, String schema) {
        DbConn conn = requireAllowed(connId);
        String target = StrUtil.blankToDefault(schema, "");
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        LspSession session = new LspSession(sessionId, connId, target);
        loadMetadata(session);
        sessions.put(sessionId, session);
        log.info("SQL LSP 会话建立: sessionId={}, connId={}, schema={}", sessionId, connId, target);
        return sessionId;
    }

    @Override
    public void stopSession(String sessionId) {
        if (StrUtil.isNotBlank(sessionId) && sessions.remove(sessionId) != null) {
            log.info("SQL LSP 会话销毁: sessionId={}", sessionId);
        }
    }

    /**
     * 定时清理空闲超时会话(30 分钟无请求), 防 stopSession 缺失导致的内存泄漏。
     * 需宿主启用 {@code @EnableScheduling} 时生效; 未启用时由显式 stopSession 释放, 不影响功能。
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 300000)
    public void sweepExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (LspSession s : sessions.values()) {
            if (now - s.lastAccessTime > SESSION_IDLE_TIMEOUT_MS) {
                if (sessions.remove(s.sessionId, s)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("SQL LSP 过期会话清理: 共移除 {} 个(空闲超 {} 分钟)", removed, SESSION_IDLE_TIMEOUT_MS / 60000);
        }
    }

    @Override
    public void refreshMetadata(String sessionId) {
        LspSession session = requireSession(sessionId);
        loadMetadata(session);
    }

    // ================= LSP 请求分发 =================

    @Override
    @SuppressWarnings("unchecked")
    public Object sendRequest(String sessionId, String method, Object params) {
        LspSession session = requireSession(sessionId);
        if (StrUtil.isBlank(method)) {
            throw new ServiceException("LSP 方法名不能为空");
        }
        Map<String, Object> p = params instanceof Map ? (Map<String, Object>) params : Map.of();
        switch (method.toLowerCase(Locale.ROOT)) {
            case "completion":
                return completion(session, p);
            case "hover":
                return hover(session, p);
            case "diagnostics":
                return diagnostics(session, p);
            case "definition":
                return null; // 占位：可扩展为定位建表语句
            case "references":
                return null;
            default:
                throw new ServiceException("不支持的 LSP 方法: " + method + "(支持 completion/hover/diagnostics)");
        }
    }

    // ================= completion =================

    private List<Map<String, Object>> completion(LspSession session, Map<String, Object> p) {
        List<Map<String, Object>> items = new ArrayList<>();
        String text = p.get("text") == null ? "" : String.valueOf(p.get("text"));
        int line = intOf(p.get("line"), 0);
        int character = intOf(p.get("character"), 0);
        // 当前行光标前文本，用于识别补全上下文
        String[] lines = text.split("\n", -1);
        String prefixLine = line >= 0 && line < lines.length ? lines[line] : "";
        String beforeCursor = character >= 0 && character < prefixLine.length()
            ? prefixLine.substring(0, character) : prefixLine;
        String prefix = lastIdentifier(beforeCursor).toLowerCase(Locale.ROOT);

        // 1) 关键字补全(含大小写敏感文本, 提供与输入匹配的关键字)
        for (String kw : KEYWORDS) {
            if (prefix.isEmpty() || kw.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                if (!addLimited(items, item(kw, "keyword", "SQL 关键字"))) {
                    return items;
                }
            }
        }
        // 2) 表名补全
        for (String table : session.tables.keySet()) {
            if (prefix.isEmpty() || table.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                if (!addLimited(items, item(table, "table", "表"))) {
                    return items;
                }
            }
        }
        // 3) 列名补全: 仅当已有输入前缀时补列(空前缀下列全补易爆量且挤掉关键字/表名, 与主流 IDE 行为一致)
        if (!prefix.isEmpty()) {
            String nearTable = detectNearTable(beforeCursor);
            if (nearTable != null) {
                for (String col : session.columnsOf(nearTable)) {
                    if (col.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        if (!addLimited(items, item(col, "column", "列 · " + nearTable))) {
                            return items;
                        }
                    }
                }
            }
        }
        return items;
    }

    /**
     * 追加补全项并受 200 上限约束; 达到上限返回 false 供调用方提前返回, 避免构造超量项
     */
    private static boolean addLimited(List<Map<String, Object>> items, Map<String, Object> it) {
        if (items.size() >= 200) {
            return false;
        }
        items.add(it);
        return true;
    }

    // ================= hover =================

    private Object hover(LspSession session, Map<String, Object> p) {
        String text = p.get("text") == null ? "" : String.valueOf(p.get("text"));
        int line = intOf(p.get("line"), 0);
        int character = intOf(p.get("character"), 0);
        String[] lines = text.split("\n", -1);
        if (line < 0 || line >= lines.length) {
            return null;
        }
        String lineText = lines[line];
        if (character < 0 || character > lineText.length()) {
            return null;
        }
        String token = tokenAt(lineText, character);
        if (StrUtil.isBlank(token)) {
            return null;
        }
        // 表
        if (session.tables.containsKey(token)) {
            return "表 " + token + ": " + session.tableSummary(token);
        }
        // 列(在所有表中查找)
        for (Map.Entry<String, List<String>> e : session.columns.entrySet()) {
            if (e.getValue().contains(token)) {
                return "列 " + token + " (表 " + e.getKey() + "): " + session.columnSummary(e.getKey(), token);
            }
        }
        return null;
    }

    // ================= diagnostics =================

    private List<Map<String, Object>> diagnostics(LspSession session, Map<String, Object> p) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String text = p.get("text") == null ? "" : String.valueOf(p.get("text"));
        String normalized = DbAccessPolicy.normalizeLeadingComments(text);
        if (normalized.isEmpty()) {
            issues.add(diag("SQL 内容为空(可能全部为注释)", "WARNING", 0));
            return issues;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        String first = firstKeyword(normalized);
        // 危险语句
        String warning = DbAccessPolicy.dangerWarning(normalized);
        if (warning != null) {
            issues.add(diag("风险提示: " + warning, "ERROR", 0));
        }
        // 无 WHERE 的 DELETE/UPDATE
        if ((lower.startsWith("delete") || lower.startsWith("update")) && !lower.contains(" where ")) {
            issues.add(diag("DELETE/UPDATE 缺少 WHERE 条件，将影响全表数据", "ERROR", 0));
        }
        // 只读查询无 LIMIT
        if (DbAccessPolicy.isReadOnlyStatement(normalized) && !lower.contains("limit")) {
            issues.add(diag("只读查询未带 LIMIT，建议限制返回行数", "WARNING", 0));
        }
        // 括号不配对
        int open = countChar(normalized, '(');
        int close = countChar(normalized, ')');
        if (open != close) {
            issues.add(diag("括号不配对: 左括号 " + open + " 个, 右括号 " + close + " 个", "ERROR", 0));
        }
        // 表引用识别: 提示引用表是否在元数据内
        for (String table : extractTables(normalized)) {
            if (!session.tables.containsKey(table)) {
                issues.add(diag("引用了库中不存在的表: " + table + "(可刷新元数据后核对)", "WARNING", 0));
            }
        }
        return issues;
    }

    // ================= 元数据加载 =================

    private void loadMetadata(LspSession session) {
        session.tables.clear();
        session.columns.clear();
        session.columnMeta.clear();
        List<DbTableVo> tables = safeTables(session.connId, session.schema);
        for (DbTableVo t : tables) {
            if (session.tables.size() >= 200) {
                break;
            }
            session.tables.put(t.getTableName(), t);
            List<DbColumnVo> cols = safeColumns(session.connId, session.schema, t.getTableName());
            List<String> names = new ArrayList<>();
            for (DbColumnVo c : cols) {
                if (c.getColumnName() != null) {
                    names.add(c.getColumnName());
                }
            }
            if (!cols.isEmpty()) {
                session.columns.put(t.getTableName(), names.subList(0, Math.min(names.size(), 200)));
                session.columnMeta.put(t.getTableName(), cols.subList(0, Math.min(cols.size(), 200)));
            }
        }
    }

    private List<DbColumnVo> safeColumns(Long connId, String schema, String table) {
        try {
            if (StrUtil.isBlank(schema) || StrUtil.isBlank(table)) {
                return new ArrayList<>();
            }
            return metaService.columns(connId, schema, table);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ================= 工具 =================

    private LspSession requireSession(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ServiceException("sessionId 不能为空");
        }
        LspSession s = sessions.get(sessionId);
        if (s == null) {
            throw new ServiceException("LSP 会话不存在或已过期，请重新 startSession");
        }
        s.touch();
        return s;
    }

    private DbConn requireAllowed(Long connId) {
        if (connId == null) {
            throw new ServiceException("连接ID不能为空");
        }
        List<String> allowed = new ArrayList<>();
        if (StrUtil.isNotBlank(allowConns)) {
            for (String s : allowConns.split(",")) {
                if (StrUtil.isNotBlank(s)) {
                    allowed.add(s.trim());
                }
            }
        }
        if (allowed.isEmpty() || !allowed.contains(String.valueOf(connId))) {
            throw new ServiceException("该连接不在 AI 白名单内, 请在 dbtool.ai.allowConns 配置后使用");
        }
        DbConn conn = connService.getById(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接不存在");
        }
        return conn;
    }

    private List<DbTableVo> safeTables(Long connId, String schema) {
        try {
            if (StrUtil.isBlank(schema)) {
                return new ArrayList<>();
            }
            return metaService.tables(connId, schema);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> item(String label, String kind, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("kind", kind);
        m.put("detail", detail);
        return m;
    }

    private Map<String, Object> diag(String message, String severity, int line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        m.put("severity", severity);
        m.put("line", line);
        return m;
    }

    private String lastIdentifier(String s) {
        Matcher m = IDENTIFIER.matcher(s);
        String last = "";
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private String tokenAt(String line, int character) {
        Matcher m = IDENTIFIER.matcher(line);
        while (m.find()) {
            if (character >= m.start() && character <= m.end()) {
                return m.group();
            }
        }
        return null;
    }

    /** 粗略识别光标前最近 FROM/JOIN/UPDATE/INTO 后的表名(用于列补全上下文) */
    private String detectNearTable(String beforeCursor) {
        Matcher m = Pattern.compile("(?:from|join|update|into|table)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$",
            Pattern.CASE_INSENSITIVE).matcher(beforeCursor);
        return m.find() ? m.group(1) : null;
    }

    private String firstKeyword(String sql) {
        Matcher m = Pattern.compile("^([a-zA-Z]+)").matcher(sql.trim());
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private int countChar(String s, char c) {
        int n = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) {
                n++;
            }
        }
        return n;
    }

    private List<String> extractTables(String sql) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?:from|join|update|into|table)\\s+", Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) {
            // 跳过子查询: FROM (SELECT ... / JOIN (SELECT ...
            int p = m.end();
            while (p < sql.length() && Character.isWhitespace(sql.charAt(p))) {
                p++;
            }
            if (p < sql.length() && sql.charAt(p) == '(') {
                continue;
            }
            Matcher id = IDENTIFIER.matcher(sql.substring(m.end()));
            if (!id.find()) {
                continue;
            }
            String name = id.group();
            // 支持 schema.table / db.schema.table: 抓取紧邻的点号链
            Matcher dot = DOTTED.matcher(sql.substring(m.end() + id.end()));
            if (dot.find() && dot.start() == id.end()) {
                name += dot.group();
            }
            if (name.contains(".")) {
                name = name.substring(name.lastIndexOf('.') + 1);
            }
            String lower = name.toLowerCase(Locale.ROOT);
            // 过滤 SQL 保留字与 Oracle 伪表 dual, 避免函数名/关键字误报为表
            if (SQL_RESERVED.contains(lower) || "dual".equalsIgnoreCase(name)) {
                continue;
            }
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static int intOf(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ================= 会话对象 =================

    private static final class LspSession {
        private final String sessionId;
        private final Long connId;
        private final String schema;
        /** 最近访问时间(用于空闲会话清理) */
        private volatile long lastAccessTime;
        /** 表名 -> DbTableVo(含注释与列) */
        private final Map<String, DbTableVo> tables = new LinkedHashMap<>();
        /** 表名 -> 列名列表 */
        private final Map<String, List<String>> columns = new LinkedHashMap<>();
        /** 表名 -> 列元数据(用于 hover) */
        private final Map<String, List<DbColumnVo>> columnMeta = new LinkedHashMap<>();

        private LspSession(String sessionId, Long connId, String schema) {
            this.sessionId = sessionId;
            this.connId = connId;
            this.schema = schema;
            this.lastAccessTime = System.currentTimeMillis();
        }

        /** 标记会话活跃(任何请求/访问时更新) */
        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }

        List<String> columnsOf(String table) {
            List<String> cols = columns.get(table);
            return cols == null ? List.of() : cols;
        }

        String tableSummary(String table) {
            if (!tables.containsKey(table)) {
                return "未知表";
            }
            List<String> cols = columnsOf(table);
            return "列(" + cols.size() + "): " + String.join(", ", cols);
        }

        String columnSummary(String table, String column) {
            List<DbColumnVo> cols = columnMeta.get(table);
            if (cols == null) {
                return "未知列";
            }
            for (DbColumnVo c : cols) {
                if (column.equals(c.getColumnName())) {
                    return "类型 " + c.getColumnType() + (StrUtil.isNotBlank(c.getColumnComment())
                        ? ", 注释 " + c.getColumnComment() : "");
                }
            }
            return "未知列";
        }
    }
}
