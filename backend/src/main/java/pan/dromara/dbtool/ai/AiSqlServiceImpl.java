package pan.dromara.dbtool.ai;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.service.IDbConnService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-SQL 助手实现(P2)：自然语言转 SQL、SQL 解释、SQL 优化、高危 SQL 拦截、测试数据生成。
 * <p>双模式：</p>
 * <ul>
 *   <li>LLM 模式：配置 {@code dbtool.ai.llm.*} 后对接 OpenAI 兼容 Chat Completions 接口（DeepSeek/通义/OpenAI 等通用），
 *       基于连接元数据(schema/表/列)生成更贴近库结构的 SQL。</li>
 *   <li>规则降级：未配置 LLM 或调用失败时，自动降级为离线规则引擎（元数据关键词匹配 + 模板），不依赖外部网络。</li>
 * </ul>
 * <p>安全：连接白名单({@code dbtool.ai.allowConns}，空=拒绝所有)；所有方法只生成/分析 SQL，绝不执行；
 * 写类语句标注需二次确认；detectRisk 走纯规则快速判定。</p>
 *
 * @author doubao
 */
@Slf4j
@Service
public class AiSqlServiceImpl implements AiSqlService {

    private static final int MAX_TABLES = 100;
    private static final int MAX_COLUMNS_PER_TABLE = 200;
    /** 规则模式用于匹配库内表名 */
    private static final int MAX_SAMPLE_COLS = 8;

    // ================= 配置 =================
    /** 是否启用 LLM（默认关闭，避免未配置 key 时误触外部 API） */
    @Value("${dbtool.ai.llm.enabled:false}")
    private boolean llmEnabled;
    /** OpenAI 兼容 baseUrl，如 https://api.deepseek.com/v1 */
    @Value("${dbtool.ai.llm.baseUrl:}")
    private String llmBaseUrl;
    /** API Key */
    @Value("${dbtool.ai.llm.apiKey:}")
    private String llmApiKey;
    /** 模型名，如 deepseek-chat、gpt-4o-mini */
    @Value("${dbtool.ai.llm.model:deepseek-chat}")
    private String llmModel;
    /** AI/MCP 允许访问的连接白名单(逗号分隔 connId)；空 = 默认拒绝所有连接(安全默认) */
    @Value("${dbtool.ai.allowConns:}")
    private String allowConns;

    private final IDbConnService connService;
    private final IDbMetaService metaService;

    public AiSqlServiceImpl(IDbConnService connService, IDbMetaService metaService) {
        this.connService = connService;
        this.metaService = metaService;
    }

    // ================= 自然语言 → SQL =================

    @Override
    public String naturalLanguageToSql(Long connId, String schema, String naturalLanguage) {
        if (StrUtil.isBlank(naturalLanguage)) {
            throw new ServiceException("需求描述不能为空");
        }
        DbConn conn = requireAllowed(connId);
        String target = StrUtil.blankToDefault(schema, "");
        String context = buildContext(connId, target);
        // 1) LLM 优先
        String llmSql = callLlm(buildNlSystemPrompt(), buildNlUserPrompt(context, naturalLanguage));
        if (StrUtil.isNotBlank(llmSql)) {
            return annotateSql(llmSql, conn);
        }
        // 2) 规则降级
        return ruleGenerate(connId, target, naturalLanguage);
    }

    // ================= SQL 解释 =================

    @Override
    public String explainSql(String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new ServiceException("SQL 不能为空");
        }
        String normalized = DbAccessPolicy.normalizeLeadingComments(sql);
        if (normalized.isEmpty()) {
            throw new ServiceException("SQL 内容为空(可能全部为注释)");
        }
        // LLM 可选
        String llm = callLlm(buildExplainSystemPrompt(), normalized);
        if (StrUtil.isNotBlank(llm)) {
            return llm;
        }
        // 规则降级: 类型/只读/涉及表/风险
        String firstKeyword = firstKeyword(normalized);
        boolean readOnly = DbAccessPolicy.isReadOnlyStatement(normalized);
        String warning = DbAccessPolicy.dangerWarning(normalized);
        List<String> tables = extractTables(normalized);
        int statements = countStatements(normalized);
        StringBuilder sb = new StringBuilder();
        sb.append("【语句类型】").append(firstKeyword).append('\n');
        sb.append("【是否只读】").append(readOnly ? "是" : "否").append('\n');
        if (!tables.isEmpty()) {
            sb.append("【涉及表】").append(String.join(", ", tables)).append('\n');
        }
        sb.append("【语句条数】").append(statements).append('\n');
        if (warning != null) {
            sb.append("【风险提示】").append(warning).append('\n');
        } else if (readOnly && !normalized.toLowerCase(Locale.ROOT).contains("limit")) {
            sb.append("【优化建议】只读查询未带 LIMIT，建议加 LIMIT 限制返回行数\n");
        }
        return sb.toString().trim();
    }

    // ================= SQL 优化建议 =================

    @Override
    public String optimizeSql(Long connId, String schema, String sql) {
        if (StrUtil.isBlank(sql)) {
            throw new ServiceException("SQL 不能为空");
        }
        DbConn conn = requireAllowed(connId);
        String target = StrUtil.blankToDefault(schema, "");
        String context = buildContext(connId, target);
        String llm = callLlm(buildOptimizeSystemPrompt(), context + "\n\n待优化 SQL:\n" + sql);
        if (StrUtil.isNotBlank(llm)) {
            return llm;
        }
        // 规则降级: 基于 SQL 文本 + 元数据
        return ruleOptimize(connId, target, sql);
    }

    // ================= 高危检测 =================

    @Override
    public String detectRisk(String sql) {
        if (StrUtil.isBlank(sql)) {
            return "LOW";
        }
        String normalized = DbAccessPolicy.normalizeLeadingComments(sql);
        if (normalized.isEmpty()) {
            return "LOW";
        }
        String warning = DbAccessPolicy.dangerWarning(normalized);
        if (warning != null) {
            // DROP/TRUNCATE 等破坏性 DDL 判定为 CRITICAL
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (lower.startsWith("drop") || lower.startsWith("truncate")
                || lower.startsWith("grant") || lower.startsWith("revoke")
                || lower.startsWith("alter") || lower.startsWith("create")) {
                return "CRITICAL";
            }
            // 无 WHERE 的 DELETE/UPDATE
            if ((lower.startsWith("delete") || lower.startsWith("update"))
                && !lower.contains(" where ")) {
                return "CRITICAL";
            }
            return "HIGH";
        }
        boolean readOnly = DbAccessPolicy.isReadOnlyStatement(normalized);
        return readOnly ? "LOW" : "MEDIUM";
    }

    // ================= 生成测试数据 =================

    @Override
    public String generateTestData(Long connId, String schema, String table, Integer rowCount) {
        if (StrUtil.isBlank(table)) {
            throw new ServiceException("表名不能为空");
        }
        requireAllowed(connId);
        int rows = rowCount == null || rowCount <= 0 ? 10 : Math.min(rowCount, 1000);
        String target = StrUtil.blankToDefault(schema, "");
        List<DbColumnVo> columns = safeColumns(connId, target, table);
        if (columns.isEmpty()) {
            throw new ServiceException("未读取到表[" + table + "]的列信息，请检查表名与库名");
        }
        List<String> colNames = new ArrayList<>();
        for (DbColumnVo c : columns) {
            colNames.add("`" + c.getColumnName() + "`");
        }
        StringBuilder sb = new StringBuilder("-- 测试数据(" + rows + " 行, 生成后请核对再执行)\n");
        sb.append("INSERT INTO ").append(quoteTable(target, table)).append("\n  (").append(String.join(", ", colNames)).append(")\nVALUES\n");
        for (int i = 0; i < rows; i++) {
            List<String> vals = new ArrayList<>();
            for (DbColumnVo c : columns) {
                vals.add(sampleValue(c.getColumnType(), i));
            }
            sb.append("  (").append(String.join(", ", vals)).append(")").append(i < rows - 1 ? "," : ";").append('\n');
        }
        return sb.toString();
    }

    // ================= 规则降级 =================

    /** 规则生成 SQL(与 LLM 结果保持同一安全标注) */
    private String ruleGenerate(Long connId, String schema, String request) {
        String lower = request.toLowerCase(Locale.ROOT);
        String table = matchTable(connId, schema, request);
        if (table == null) {
            List<DbTableVo> tables = safeTables(connId, StrUtil.blankToDefault(schema, ""));
            StringBuilder sb = new StringBuilder("-- 未在 [").append(StrUtil.blankToDefault(schema, "?"))
                .append("] 库中匹配到表名。可用表(").append(Math.min(tables.size(), MAX_TABLES)).append(" 个):\n-- ");
            for (int i = 0; i < Math.min(tables.size(), 20); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(tables.get(i).getTableName());
            }
            if (tables.size() > 20) {
                sb.append(" ...");
            }
            sb.append("\n请使用上述表名重试, 例如: 查一下 ")
                .append(tables.isEmpty() ? "表名" : tables.get(0).getTableName()).append(" 的所有字段");
            return sb.toString();
        }
        String prefixed = quoteTable(schema, table);
        List<String> cols = columnNames(connId, schema, table);
        if (lower.contains("count") || lower.contains("统计") || lower.contains("多少") || lower.contains("数量")) {
            return "SELECT COUNT(*) AS cnt FROM " + prefixed + ";";
        }
        if (lower.contains("desc") || lower.contains("describe")) {
            return "DESC " + prefixed + ";";
        }
        if (lower.contains("插入") || lower.contains("新增") || lower.contains("insert") || lower.contains("写入")) {
            return "-- 生成 INSERT 模板(写入操作, 执行需二次确认)\nINSERT INTO " + prefixed
                + " (" + String.join(", ", cols.isEmpty() ? List.of("col1") : cols) + ") VALUES (?, ?);";
        }
        if (lower.contains("更新") || lower.contains("修改") || lower.contains("update")) {
            return "-- 生成 UPDATE 模板(写入操作, 执行需二次确认; 建议带 WHERE)\nUPDATE " + prefixed
                + " SET col = 'value' WHERE id = 1;";
        }
        if (lower.contains("删除") || lower.contains("delete")) {
            return "-- 生成 DELETE 模板(高危操作, 执行需二次确认; 必须带 WHERE)\nDELETE FROM " + prefixed
                + " WHERE id = 1;";
        }
        if (lower.contains("建表") || lower.contains("创建表") || lower.contains("create table")) {
            return "-- 生成 CREATE TABLE 模板(DDL, 执行需二次确认)\nCREATE TABLE " + prefixed + " (\n"
                + "    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',\n"
                + "    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'\n"
                + ") COMMENT='新表';";
        }
        if (!cols.isEmpty()) {
            return "SELECT " + String.join(", ", cols.subList(0, Math.min(cols.size(), MAX_SAMPLE_COLS)))
                + " FROM " + prefixed + " LIMIT 100;";
        }
        return "SELECT * FROM " + prefixed + " LIMIT 100;";
    }

    /** 规则优化建议(LLM 不可用时) */
    private String ruleOptimize(Long connId, String schema, String sql) {
        String normalized = DbAccessPolicy.normalizeLeadingComments(sql);
        String lower = normalized.toLowerCase(Locale.ROOT);
        List<String> tips = new ArrayList<>();
        boolean readOnly = DbAccessPolicy.isReadOnlyStatement(normalized);
        if (readOnly && !lower.contains("limit")) {
            tips.add("只读查询未带 LIMIT，建议加 LIMIT 限制返回行数，避免全量拉取");
        }
        if ((lower.startsWith("delete") || lower.startsWith("update")) && !lower.contains(" where ")) {
            tips.add("DELETE/UPDATE 缺少 WHERE 条件，将影响全表，务必补 WHERE 或设置事务隔离");
        }
        if (lower.contains("select *")) {
            tips.add("SELECT * 建议改为显式列名，减少网络传输并避免 SELECT * 带来的隐式依赖");
        }
        if (lower.contains("like '%") || lower.contains("like %")) {
            tips.add("前导 % 的 LIKE 无法走索引，会全表扫描；评估改用前缀匹配或全文索引");
        }
        if (tips.isEmpty()) {
            tips.add("未发现明显可优化的点。可结合执行计划(EXPLAIN)进一步分析扫描方式与索引使用情况");
        }
        StringBuilder sb = new StringBuilder("【优化建议】\n");
        for (int i = 0; i < tips.size(); i++) {
            sb.append(i + 1).append(". ").append(tips.get(i)).append('\n');
        }
        return sb.toString().trim();
    }

    // ================= LLM 调用(OpenAI 兼容) =================

    private boolean llmReady() {
        return llmEnabled && StrUtil.isNotBlank(llmBaseUrl) && StrUtil.isNotBlank(llmApiKey);
    }

    private String callLlm(String system, String user) {
        if (!llmReady()) {
            return null;
        }
        String url = llmBaseUrl.endsWith("/") ? llmBaseUrl : llmBaseUrl + "/";
        if (!url.endsWith("/chat/completions")) {
            url += "chat/completions";
        }
        JSONObject body = new JSONObject();
        body.set("model", llmModel);
        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.set("role", "system");
        sys.set("content", system);
        messages.add(sys);
        JSONObject usr = new JSONObject();
        usr.set("role", "user");
        usr.set("content", user);
        messages.add(usr);
        body.set("messages", messages);
        body.set("temperature", 0.2);
        body.set("max_tokens", 1024);
        try (HttpResponse resp = HttpRequest.post(url)
            .header("Authorization", "Bearer " + llmApiKey)
            .header("Content-Type", "application/json")
            .body(body.toString())
            .timeout(30000)
            .execute()) {
            if (!resp.isOk()) {
                log.warn("LLM 调用失败(HTTP {}): {}", resp.getStatus(), StrUtil.maxLength(resp.body(), 200));
                return null;
            }
            JSONObject json = JSONUtil.parseObj(resp.body());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("LLM 响应无 choices: {}", StrUtil.maxLength(resp.body(), 200));
                return null;
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.getJSONObject("message");
            String content = message == null ? null : message.getStr("content");
            return StrUtil.isBlank(content) ? null : content.trim();
        } catch (Exception e) {
            log.warn("LLM 调用异常(降级规则): {}", e.getMessage());
            return null;
        }
    }

    private String buildNlSystemPrompt() {
        return "你是一名专业的数据库 SQL 生成助手。基于用户提供的数据库 schema 上下文(库/表/列)，"
            + "把用户的自然语言需求转换为精确的 SQL 语句。只返回 SQL 本身，不要解释、不要 Markdown 代码块标记。"
            + "写类需求(INSERT/UPDATE/DELETE/DDL)在 SQL 前用注释标注\"-- 写入操作, 执行需二次确认\"。";
    }

    private String buildNlUserPrompt(String context, String naturalLanguage) {
        return "数据库上下文:\n" + context + "\n\n用户需求:\n" + naturalLanguage;
    }

    private String buildExplainSystemPrompt() {
        return "你是一名数据库专家。请用简洁中文解释用户给出的 SQL：语句类型、作用、涉及表与主要逻辑、潜在风险。";
    }

    private String buildOptimizeSystemPrompt() {
        return "你是一名数据库性能优化专家。请基于用户提供的 schema 上下文与 SQL，给出具体、可执行的优化建议"
            + "(索引、LIMIT、WHERE、避免 SELECT *、避免全表扫描等)，用中文分点列出，简洁务实。";
    }

    // ================= 元数据上下文 =================

    private String buildContext(Long connId, String schema) {
        StringBuilder sb = new StringBuilder();
        List<String> dbs = safeDatabases(connId);
        String target = StrUtil.isBlank(schema) && !dbs.isEmpty() ? dbs.get(0) : schema;
        sb.append("库列表: ").append(String.join(", ", dbs)).append('\n');
        List<DbTableVo> tables = safeTables(connId, target);
        int shown = 0;
        for (DbTableVo t : tables) {
            if (shown >= MAX_TABLES) {
                break;
            }
            shown++;
            sb.append("表 ").append(t.getTableName());
            if (StrUtil.isNotBlank(t.getTableComment())) {
                sb.append("(").append(t.getTableComment()).append(")");
            }
            sb.append(": ");
            List<String> cols = columnNames(connId, target, t.getTableName());
            if (cols.isEmpty()) {
                sb.append("(列读取失败)");
            } else {
                sb.append(String.join(", ", cols.subList(0, Math.min(cols.size(), MAX_COLUMNS_PER_TABLE))));
            }
            sb.append('\n');
        }
        if (tables.size() > MAX_TABLES) {
            sb.append("... 等共 ").append(tables.size()).append(" 张表(已截断)");
        }
        return sb.toString();
    }

    // ================= 安全与工具 =================

    private DbConn requireAllowed(Long connId) {
        if (connId == null) {
            throw new ServiceException("连接ID不能为空");
        }
        List<String> allowed = parseAllowConns();
        if (allowed.isEmpty() || !allowed.contains(String.valueOf(connId))) {
            throw new ServiceException("该连接不在 AI 白名单内, 请在 dbtool.ai.allowConns 配置后使用");
        }
        DbConn conn = connService.getById(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接不存在");
        }
        return conn;
    }

    private List<String> parseAllowConns() {
        List<String> list = new ArrayList<>();
        if (StrUtil.isNotBlank(allowConns)) {
            for (String s : allowConns.split(",")) {
                if (StrUtil.isNotBlank(s)) {
                    list.add(s.trim());
                }
            }
        }
        return list;
    }

    private String annotateSql(String sql, DbConn conn) {
        String trimmed = sql.trim();
        if (trimmed.startsWith("--") || trimmed.startsWith("/*")) {
            return trimmed;
        }
        String warning = DbAccessPolicy.dangerWarning(trimmed);
        if (warning != null) {
            return "-- 风险提示: " + warning + "\n" + trimmed;
        }
        boolean readOnly = DbAccessPolicy.isReadOnlyStatement(trimmed);
        if (!readOnly) {
            return "-- 写入操作, 执行需二次确认\n" + trimmed;
        }
        return trimmed;
    }

    /** 在请求中匹配库内表名: 精确匹配优先; 包含匹配选最长命中表名 */
    private String matchTable(Long connId, String schema, String request) {
        String target = StrUtil.blankToDefault(schema, "");
        String lowerReq = request.toLowerCase(Locale.ROOT).trim();
        List<DbTableVo> tables = safeTables(connId, target);
        for (DbTableVo t : tables) {
            String name = t.getTableName();
            if (name != null && name.toLowerCase(Locale.ROOT).equals(lowerReq)) {
                return name;
            }
        }
        String best = null;
        int bestLen = -1;
        for (DbTableVo t : tables) {
            String name = t.getTableName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lowerReq.contains(lower) || lower.contains(lowerReq)) {
                if (lower.length() > bestLen) {
                    bestLen = lower.length();
                    best = name;
                }
            }
        }
        return best;
    }

    private String quoteTable(String schema, String table) {
        String t = "`" + table + "`";
        return StrUtil.isBlank(schema) ? t : "`" + schema + "`." + t;
    }

    private String firstKeyword(String sql) {
        Matcher m = Pattern.compile("^([a-zA-Z]+)").matcher(sql.trim());
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private int countStatements(String sql) {
        int count = 0;
        for (String part : sql.split(";")) {
            if (!DbAccessPolicy.normalizeLeadingComments(part).isEmpty()) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    private static final Pattern TABLE_REF = Pattern.compile(
        "(?:from|join|update|into|table)\\s+([`\"\\[\\w.]+)`?\"?\\[?",
        Pattern.CASE_INSENSITIVE);

    private List<String> extractTables(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher m = TABLE_REF.matcher(sql);
        while (m.find()) {
            String name = m.group(1).replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(dot + 1);
            }
            if (!name.isEmpty() && !tables.contains(name) && !"dual".equalsIgnoreCase(name)) {
                tables.add(name);
            }
        }
        return tables;
    }

    /** 按列类型生成一行示例值(仅生成, 不执行) */
    private String sampleValue(String columnType, int row) {
        String t = columnType == null ? "" : columnType.toLowerCase(Locale.ROOT);
        if (t.contains("int") || t.contains("number") || t.contains("serial") || t.contains("bigint")
            || t.contains("smallint") || t.contains("tinyint") || t.contains("float") || t.contains("double")
            || t.contains("decimal") || t.contains("numeric") || t.contains("real")) {
            return String.valueOf(100 + row);
        }
        if (t.contains("bool") || t.contains("bit")) {
            return row % 2 == 0 ? "1" : "0";
        }
        if (t.contains("date")) {
            return "'2024-01-" + String.format("%02d", (row % 28) + 1) + "'";
        }
        if (t.contains("time") || t.contains("timestamp")) {
            return "NOW()";
        }
        if (t.contains("clob") || t.contains("text") || t.contains("char") || t.contains("varchar")
            || t.contains("string") || t.contains("json") || t.contains("enum") || t.contains("uuid")) {
            return "'test_value_" + row + "'";
        }
        return "'test_value_" + row + "'";
    }

    // ================= 元数据(异常静默为安全失败) =================

    private List<String> safeDatabases(Long connId) {
        try {
            return metaService.databases(connId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
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

    private List<String> columnNames(Long connId, String schema, String table) {
        List<String> names = new ArrayList<>();
        for (DbColumnVo c : safeColumns(connId, schema, table)) {
            if (c.getColumnName() != null) {
                names.add(c.getColumnName());
            }
        }
        return names;
    }
}
