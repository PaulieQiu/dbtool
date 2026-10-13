package pan.dromara.dbtool.service;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.Neo4jDriverManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.Neo4jExpandBo;
import pan.dromara.dbtool.domain.bo.Neo4jQueryBo;
import pan.dromara.dbtool.domain.vo.Neo4jCellVo;
import pan.dromara.dbtool.domain.vo.Neo4jGraphVo;
import pan.dromara.dbtool.domain.vo.Neo4jNodeVo;
import pan.dromara.dbtool.domain.vo.Neo4jRelVo;
import pan.dromara.dbtool.domain.vo.Neo4jResultVo;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Neo4j 图数据库服务
 * <p>基于官方 Java Driver，将 Cypher 查询结果统一转换为「图 + 表」双视图 DTO；</p>
 * <p>参数严格绑定，禁止字符串拼接；只读连接使用服务端 READ 访问模式强制拒写。</p>
 *
 * @author doubao
 */
@Slf4j
@Service
public class Neo4jService {

    /** JavaScript 安全整数上限(2^53-1)，超出的 Long 序列化为字符串避免精度丢失 */
    private static final long JS_SAFE = 9007199254740991L;

    private final IDbConnService connService;
    private final Neo4jDriverManager driverManager;

    /**
     * 会话注册表 (executionId -> Session)，用于跨请求取消
     */
    private final Map<String, Session> sessionRegistry = new ConcurrentHashMap<>();

    public Neo4jService(IDbConnService connService, Neo4jDriverManager driverManager) {
        this.connService = connService;
        this.driverManager = driverManager;
    }

    // ============ 连接 ============

    private DbConn getConn(Long connId) {
        DbConn conn = connService.getById(connId);
        if (conn == null) {
            throw new ServiceException("连接不存在: " + connId);
        }
        return conn;
    }

    private Session openSession(DbConn conn, String database, AccessMode mode) {
        Driver driver = driverManager.getDriver(conn);
        SessionConfig.Builder b = SessionConfig.builder();
        if (StrUtil.isNotBlank(database)) {
            b.withDatabase(database);
        }
        if (mode != null) {
            b.withDefaultAccessMode(mode);
        }
        return driver.session(b.build());
    }

    // ============ Databases ============

    /**
     * 枚举当前账号可访问的数据库（权限不足/版本不支持时抛可读错误，不静默默认）
     */
    public List<String> databases(Long connId) {
        DbConn conn = getConn(connId);
        List<String> dbs = new ArrayList<>();
        try (Session s = openSession(conn, null, AccessMode.READ)) {
            Result r = s.run("SHOW DATABASES");
            while (r.hasNext()) {
                Value v = r.next().get("name");
                if (v != null && !v.isNull()) {
                    dbs.add(v.asString());
                }
            }
            r.consume();
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            throw new ServiceException("枚举数据库失败（可能权限不足或版本不支持）: " + friendlyNeo4j(e));
        }
        return dbs;
    }

    // ============ Schema ============

    /**
     * 获取 Schema 信息：节点标签、关系类型、属性键（含统计计数；统计为全库聚合查询，可能较慢）
     */
    public Map<String, Object> schema(Long connId, String database) {
        DbConn conn = getConn(connId);
        Map<String, Object> out = new LinkedHashMap<>();
        try (Session s = openSession(conn, database, AccessMode.READ)) {
            out.put("labels", runScalarList(s, "SHOW LABELS", "name"));
            out.put("relationshipTypes", runScalarList(s, "SHOW RELATIONSHIP TYPES", "name"));
            out.put("propertyKeys", runScalarList(s, "SHOW PROPERTY KEYS", "name"));
            out.put("labelStats", runCountStats(s,
                "MATCH (n) RETURN labels(n)[0] AS name, count(*) AS count ORDER BY count DESC LIMIT 100",
                "name", "count"));
            out.put("relTypeStats", runCountStats(s,
                "MATCH ()-[r]->() RETURN type(r) AS name, count(*) AS count ORDER BY count DESC LIMIT 100",
                "name", "count"));
            out.put("statsNote", "标签/关系计数为全库聚合查询，数据量大时可能较慢");
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            throw new ServiceException("读取 Schema 失败: " + friendlyNeo4j(e));
        }
        return out;
    }

    private List<String> runScalarList(Session s, String cypher, String col) {
        List<String> list = new ArrayList<>();
        Result r = s.run(cypher);
        while (r.hasNext()) {
            Value v = r.next().get(col);
            if (v != null && !v.isNull()) {
                list.add(v.asString());
            }
        }
        r.consume();
        return list;
    }

    /**
     * 统计类查询：返回 [{name, count}]，带上限保护
     */
    private List<Map<String, Object>> runCountStats(Session s, String cypher, String nameCol, String countCol) {
        List<Map<String, Object>> list = new ArrayList<>();
        Result r = s.run(cypher);
        while (r.hasNext()) {
            Record rec = r.next();
            Value nv = rec.get(nameCol);
            if (nv != null && !nv.isNull()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", nv.asString());
                Value cv = rec.get(countCol);
                row.put("count", cv != null && !cv.isNull() ? cv.asLong() : 0L);
                list.add(row);
            }
        }
        r.consume();
        return list;
    }

    // ============ 查询 ============

    /**
     * 执行 Cypher，返回图/表 DTO。只读连接以 READ 访问模式执行，写操作由服务端拒绝。
     * 超时由前端「取消」与 Neo4j 服务端默认事务超时共同约束，连接建立超时由 Driver 配置约束。
     */
    public Neo4jResultVo query(Neo4jQueryBo bo) {
        DbConn conn = getConn(bo.getConnId());
        boolean readOnly = DbAccessPolicy.isReadOnly(conn);
        boolean write = Boolean.TRUE.equals(bo.getWrite());
        if (write && readOnly) {
            throw new ServiceException("该连接已设置为只读模式，拒绝执行写操作");
        }
        AccessMode mode = readOnly ? AccessMode.READ : AccessMode.WRITE;
        String execId = StrUtil.blankToDefault(bo.getExecutionId(), "exec-" + UUID.randomUUID());
        Map<String, Object> params = bo.getParameters() == null ? Map.of() : bo.getParameters();
        return execute(conn, bo.getDatabase(), bo.getCypher(), params, mode, bo.effectiveResultLimit(), execId);
    }

    /**
     * 生成执行计划（EXPLAIN 不实际执行查询，无副作用；统一 READ 模式）
     */
    public Neo4jResultVo explain(Neo4jQueryBo bo) {
        DbConn conn = getConn(bo.getConnId());
        String execId = StrUtil.blankToDefault(bo.getExecutionId(), "exec-" + UUID.randomUUID());
        Map<String, Object> params = bo.getParameters() == null ? Map.of() : bo.getParameters();
        return execute(conn, bo.getDatabase(), "EXPLAIN " + bo.getCypher(), params, AccessMode.READ, 50, execId);
    }

    /**
     * 查询执行核心（含图/表 DTO 转换、结果上限保护、执行 ID 注册/取消）
     */
    private Neo4jResultVo execute(DbConn conn, String database, String cypher, Map<String, Object> params,
                                  AccessMode mode, int limit, String execId) {
        Neo4jResultVo result = new Neo4jResultVo();
        result.setExecutionId(execId);
        try (Session session = openSession(conn, database, mode)) {
            sessionRegistry.put(execId, session);
            try {
                Result r = session.run(cypher, params);
                List<String> cols = r.keys();
                result.setColumns(cols);

                // 节点/关系上限与行数同源，避免高连通节点爆量
                GraphAcc acc = new GraphAcc(Math.max(limit, 200), Math.max(limit, 200));
                int count = 0;
                while (r.hasNext() && count < limit) {
                    Record rec = r.next();
                    count++;
                    List<Neo4jCellVo> row = new ArrayList<>();
                    for (String c : cols) {
                        row.add(cell(rec.get(c), acc, 0));
                    }
                    result.getRows().add(row);
                }
                boolean rowTruncated = r.hasNext();
                ResultSummary summary = r.consume();
                result.setTruncated(rowTruncated || result.getRows().size() >= limit || acc.isTruncated());
                result.setGraph(new Neo4jGraphVo(acc.nodesList(), acc.relsList()));
                result.setResultAvailableAfterMs(summary.resultAvailableAfter(TimeUnit.MILLISECONDS));
                result.setCounters(extractCounters(summary.counters()));
                return result;
            } finally {
                sessionRegistry.remove(execId);
            }
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            result.setError(friendlyNeo4j(e));
            return result;
        } catch (Exception e) {
            if (isCancelled(e)) {
                result.setError("查询已取消");
            } else {
                log.warn("Neo4j 查询失败: {}", e.getMessage());
                result.setError("查询执行失败: " + e.getMessage());
            }
            return result;
        }
    }

    /**
     * 取消指定执行 ID 的查询（关闭对应 Session 以中断）
     */
    public boolean cancel(String executionId) {
        if (StrUtil.isBlank(executionId)) {
            return false;
        }
        Session s = sessionRegistry.remove(executionId);
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    /**
     * 邻居展开：按节点 id() 查询一层关系与邻居节点（显式用户操作，不自动递归全图）
     */
    public Neo4jGraphVo expand(Neo4jExpandBo bo) {
        DbConn conn = getConn(bo.getConnId());
        String cypher = "MATCH (n) WHERE id(n) = $nodeId "
            + "MATCH (n)-[r]-(m) RETURN r, m LIMIT $limit";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", bo.getNodeId());
        params.put("limit", bo.effectiveRelLimit());
        GraphAcc acc = new GraphAcc(bo.effectiveNodeLimit(), bo.effectiveRelLimit());
        try (Session s = openSession(conn, bo.getDatabase(), AccessMode.READ)) {
            Result r = s.run(cypher, params);
            while (r.hasNext()) {
                Record rec = r.next();
                acc.rel(rec.get("r").asRelationship());
                acc.node(rec.get("m").asNode());
            }
            r.consume();
        } catch (org.neo4j.driver.exceptions.Neo4jException e) {
            throw new ServiceException("展开邻居失败: " + friendlyNeo4j(e));
        }
        return new Neo4jGraphVo(acc.nodesList(), acc.relsList());
    }

    // ============ 值转换 ============

    private boolean isCancelled(Exception e) {
        String m = e.getMessage();
        return e instanceof org.neo4j.driver.exceptions.SessionExpiredException
            || (m != null && (m.contains("Connection to the database was closed")
            || m.contains("session has been closed")
            || m.contains("interrupted")));
    }

    /**
     * 将驱动 Value 转换为表格单元格（图元素进 GraphAcc，返回图内引用 id）
     */
    private Neo4jCellVo cell(Value v, GraphAcc acc, int depth) {
        if (v == null || v.isNull()) {
            return Neo4jCellVo.of("null", null);
        }
        String tn = v.type().name();
        if ("Node".equals(tn)) {
            Neo4jNodeVo node = acc.node(v.asNode());
            return Neo4jCellVo.of("node", node == null ? null : node.getId());
        }
        if ("Relationship".equals(tn)) {
            Neo4jRelVo rel = acc.rel(v.asRelationship());
            return Neo4jCellVo.of("rel", rel == null ? null : rel.getId());
        }
        if ("Path".equals(tn)) {
            Path p = v.asPath();
            for (Node n : p.nodes()) {
                acc.node(n);
            }
            for (Relationship r : p.relationships()) {
                acc.rel(r);
            }
            return Neo4jCellVo.of("path", "Path(" + p.length() + "段)");
        }
        if ("List".equals(tn)) {
            if (depth > 4) {
                return Neo4jCellVo.of("list", "(嵌套过深)");
            }
            List<Neo4jCellVo> cells = new ArrayList<>();
            for (Object item : v.asList()) {
                if (cells.size() >= 500) {
                    break;
                }
                cells.add(item instanceof Value vv ? cell(vv, acc, depth + 1) : Neo4jCellVo.of("other", item));
            }
            return Neo4jCellVo.of("list", cells);
        }
        if ("Map".equals(tn)) {
            if (depth > 4) {
                return Neo4jCellVo.of("map", "(嵌套过深)");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : v.asMap().entrySet()) {
                out.put(e.getKey(), e.getValue() instanceof Value vv ? cell(vv, acc, depth + 1) : e.getValue());
            }
            return Neo4jCellVo.of("map", out);
        }
        // 标量
        return Neo4jCellVo.of(scalarType(v), scalarValue(v));
    }

    private String scalarType(Value v) {
        String tn = v.type().name();
        if ("String".equals(tn)) {
            return "string";
        }
        if ("Integer".equals(tn) || "Float".equals(tn)) {
            return "Integer".equals(tn) ? "long" : "double";
        }
        if ("Boolean".equals(tn)) {
            return "boolean";
        }
        return "other";
    }

    private Object scalarValue(Value v) {
        String tn = v.type().name();
        if ("String".equals(tn)) {
            return v.asString();
        }
        if ("Integer".equals(tn)) {
            return safeLong(v.asLong());
        }
        if ("Float".equals(tn)) {
            return v.asDouble();
        }
        if ("Boolean".equals(tn)) {
            return v.asBoolean();
        }
        // 空间点/时间等：以 Cypher 字面量展示，避免不可序列化类型
        return v.toString();
    }

    /**
     * 节点/关系属性值 → 可 JSON 序列化的普通值
     */
    private static Object plain(Value v, int depth) {
        if (v == null || v.isNull()) {
            return null;
        }
        String tn = v.type().name();
        if ("String".equals(tn)) {
            return v.asString();
        }
        if ("Integer".equals(tn)) {
            return safeLong(v.asLong());
        }
        if ("Float".equals(tn)) {
            return v.asDouble();
        }
        if ("Boolean".equals(tn)) {
            return v.asBoolean();
        }
        if ("ByteArray".equals(tn)) {
            return Base64.getEncoder().encodeToString(v.asByteArray());
        }
        if ("List".equals(tn) && depth < 3) {
            List<Object> list = new ArrayList<>();
            for (Object item : v.asList()) {
                list.add(item instanceof Value vv ? plain(vv, depth + 1) : item);
            }
            return list;
        }
        if ("Map".equals(tn) && depth < 3) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : v.asMap().entrySet()) {
                m.put(e.getKey(), e.getValue() instanceof Value vv ? plain(vv, depth + 1) : e.getValue());
            }
            return m;
        }
        return v.toString();
    }

    private static Object safeLong(long l) {
        return Math.abs(l) > JS_SAFE ? String.valueOf(l) : l;
    }

    private Map<String, Long> extractCounters(SummaryCounters c) {
        Map<String, Long> m = new LinkedHashMap<>();
        putNonZero(m, "nodesCreated", c.nodesCreated());
        putNonZero(m, "nodesDeleted", c.nodesDeleted());
        putNonZero(m, "relationshipsCreated", c.relationshipsCreated());
        putNonZero(m, "relationshipsDeleted", c.relationshipsDeleted());
        putNonZero(m, "propertiesSet", c.propertiesSet());
        putNonZero(m, "labelsAdded", c.labelsAdded());
        putNonZero(m, "labelsRemoved", c.labelsRemoved());
        putNonZero(m, "indexesAdded", c.indexesAdded());
        putNonZero(m, "indexesRemoved", c.indexesRemoved());
        putNonZero(m, "constraintsAdded", c.constraintsAdded());
        putNonZero(m, "constraintsRemoved", c.constraintsRemoved());
        return m;
    }

    private void putNonZero(Map<String, Long> m, String k, long v) {
        if (v != 0) {
            m.put(k, v);
        }
    }

    /**
     * Neo4j 服务端错误中文化（保留原始 code 便于排查）
     */
    private String friendlyNeo4j(org.neo4j.driver.exceptions.Neo4jException e) {
        String code = e.code() == null ? "" : e.code();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (code.contains("ClientError.Statement.InvalidSyntax") || code.contains("SyntaxException")) {
            return "Cypher 语法错误: " + msg;
        }
        if (code.contains("ClientError.Statement.ParameterMissing")) {
            return "缺少查询参数: " + msg;
        }
        if (code.contains("ClientError.Security.Unauthorized") || code.contains("AuthenticationFailed")) {
            return "Neo4j 认证失败（用户名或密码错误）";
        }
        if (code.contains("ClientError.Security.Forbidden") || code.contains("Forbidden")) {
            return "权限不足，该操作被 Neo4j 拒绝: " + msg;
        }
        if (code.contains("ClientError.Statement.ExecutionFailure") || code.contains("ProcedureNotFound")) {
            return "语句执行失败: " + msg;
        }
        if (code.contains("ClientError.Database.DatabaseNotFound") || code.contains("DatabaseNotFound")) {
            return "数据库不存在: " + msg;
        }
        if (code.contains("ClientError.Schema")) {
            return "Schema 操作错误: " + msg;
        }
        return code + ": " + msg;
    }

    // ============ 图数据累积器 ============

    /**
     * 图数据累积器：按会话内 elementId 去重节点/关系，并执行节点/关系上限保护
     */
    private static final class GraphAcc {
        private final Map<String, Neo4jNodeVo> nodes = new LinkedHashMap<>();
        private final Map<String, Neo4jRelVo> rels = new LinkedHashMap<>();
        private final int nodeLimit;
        private final int relLimit;

        private GraphAcc(int nodeLimit, int relLimit) {
            this.nodeLimit = nodeLimit;
            this.relLimit = relLimit;
        }

        Neo4jNodeVo node(Node n) {
            String elementId = n.elementId();
            Neo4jNodeVo vo = nodes.get(elementId);
            if (vo == null && nodes.size() < nodeLimit) {
                vo = new Neo4jNodeVo();
                vo.setId(elementId);
                vo.setNodeId(n.id());
                vo.setElementId(elementId);
                List<String> labels = new ArrayList<>();
                n.labels().forEach(labels::add);
                vo.setLabels(labels);
                Map<String, Object> props = new LinkedHashMap<>();
                for (String k : n.keys()) {
                    props.put(k, plain(n.get(k), 0));
                }
                vo.setProperties(props);
                nodes.put(elementId, vo);
            }
            return vo;
        }

        Neo4jRelVo rel(Relationship r) {
            String elementId = r.elementId();
            Neo4jRelVo vo = rels.get(elementId);
            if (vo == null && rels.size() < relLimit) {
                vo = new Neo4jRelVo();
                vo.setId(elementId);
                vo.setRelId(r.id());
                vo.setElementId(elementId);
                vo.setType(r.type());
                // source/target 尽量映射到节点会话 id；节点可能不在本次结果，回退 elementId
                vo.setSource(resolveNodeId(r.startNodeElementId()));
                vo.setTarget(resolveNodeId(r.endNodeElementId()));
                Map<String, Object> props = new LinkedHashMap<>();
                for (String k : r.keys()) {
                    props.put(k, plain(r.get(k), 0));
                }
                vo.setProperties(props);
                rels.put(elementId, vo);
            }
            return vo;
        }

        private String resolveNodeId(String elementId) {
            Neo4jNodeVo vo = nodes.get(elementId);
            return vo != null ? vo.getId() : elementId;
        }

        List<Neo4jNodeVo> nodesList() {
            return new ArrayList<>(nodes.values());
        }

        List<Neo4jRelVo> relsList() {
            return new ArrayList<>(rels.values());
        }

        boolean isTruncated() {
            return nodes.size() >= nodeLimit || rels.size() >= relLimit;
        }
    }
}
