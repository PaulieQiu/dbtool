package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbResultVo;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Elasticsearch 元数据/查询 支持类
 * <p>从 {@link DbMetaServiceImpl} 拆分, 承载 ES 字段递归收集与 REST SQL 查询逻辑, 主类委托调用。</p>
 *
 * @author doubao
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbEsMetaSupport {

    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\blimit\\b", Pattern.CASE_INSENSITIVE);

    private final DbConnectionManager connectionManager;

    /** 判断是否为 Elasticsearch 连接(ES 走 REST API) */
    public static boolean isEs(DbConn conn) {
        return conn != null && "ELASTICSEARCH".equalsIgnoreCase(conn.getDbType());
    }

    public static String esError() {
        return "Elasticsearch 为全文搜索引擎, 不支持该操作(可查看索引、字段及数据浏览)";
    }

    /**
     * URL 编码(路径段), 索引名可能包含特殊字符
     */
    public static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 递归收集 ES 字段(object/nested 类型展开为 父.子)
     */
    public void collectEsFields(JSONObject properties, String prefix, List<DbColumnVo> list) {
        for (String field : properties.keySet()) {
            JSONObject prop = properties.getJSONObject(field);
            if (prop == null) {
                continue;
            }
            String name = prefix.isEmpty() ? field : prefix + "." + field;
            String type = prop.getStr("type", "object");
            DbColumnVo vo = new DbColumnVo();
            vo.setColumnName(name);
            vo.setDataType(type);
            vo.setColumnType(type);
            JSONObject subFields = prop.getJSONObject("fields");
            vo.setColumnComment(subFields != null && !subFields.isEmpty() ? "多字段:" + subFields.keySet() : "");
            list.add(vo);
            if ("object".equals(type) || "nested".equals(type)) {
                JSONObject sub = prop.getJSONObject("properties");
                if (sub != null) {
                    collectEsFields(sub, name, list);
                }
            }
        }
    }

    /**
     * Elasticsearch REST SQL 查询(REST /_sql, Basic 许可可用; ES 7.x JDBC 需白金许可故走 REST)
     */
    public DbResultVo esQuery(DbConn conn, String sql, Integer pageNum, Integer pageSize) {
        long start = System.currentTimeMillis();
        DbResultVo result = new DbResultVo();
        result.setSqlType("select");
        String trimSql = sql.trim();
        if (trimSql.endsWith(";")) {
            trimSql = trimSql.substring(0, trimSql.length() - 1).trim();
        }
        int fetch = pageSize == null || pageSize <= 0 ? 200 : Math.min(pageSize, 1000);
        int offset = pageNum != null && pageNum > 0 ? Math.max(pageNum - 1, 0) * (pageSize == null || pageSize <= 0 ? 200 : pageSize) : 0;
        // 分页: ES REST SQL 仅支持 LIMIT count(单值), 不支持 MySQL 的 LIMIT offset,size
        // 故查询 offset+fetch 条后在内存中截取 offset 之后的 fetch 条
        String finalSql = trimSql;
        boolean hasLimit = LIMIT_PATTERN.matcher(trimSql).find();
        if (!hasLimit) {
            finalSql = trimSql + " LIMIT " + (offset + fetch);
        }
        JSONObject body = new JSONObject();
        body.set("query", finalSql);
        body.set("fetch_size", fetch);
        Object obj = connectionManager.esRest(conn, "POST", "/_sql", body);
        if (obj instanceof JSONObject resp) {
            JSONArray cols = resp.getJSONArray("columns");
            JSONArray rows = resp.getJSONArray("rows");
            List<String> columns = new ArrayList<>();
            List<String> columnTypes = new ArrayList<>();
            if (cols != null) {
                for (Object c : cols) {
                    JSONObject col = (JSONObject) c;
                    columns.add(col.getStr("name"));
                    columnTypes.add(col.getStr("type"));
                }
            }
            result.setColumns(columns);
            result.setColumnTypes(columnTypes);
            List<Map<String, Object>> rowsList = new ArrayList<>();
            if (rows != null) {
                for (Object r : rows) {
                    JSONArray row = (JSONArray) r;
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size() && i < row.size(); i++) {
                        m.put(columns.get(i), row.get(i));
                    }
                    rowsList.add(m);
                }
            }
            // 内存分页截取(ES 不支持 LIMIT offset,size)
            if (offset > 0 && rowsList.size() > offset) {
                rowsList = new ArrayList<>(rowsList.subList(offset, Math.min(rowsList.size(), offset + fetch)));
            } else if (offset > 0) {
                rowsList = new ArrayList<>();
            }
            result.setRows(rowsList);
            // 关闭服务端 cursor(释放资源)
            String cursor = resp.getStr("cursor");
            if (cursor != null && !cursor.isEmpty()) {
                JSONObject close = new JSONObject();
                close.set("cursor", cursor);
                try {
                    connectionManager.esRest(conn, "POST", "/_sql/close", close);
                } catch (Exception ignored) {
                }
            }
            result.setSuccess(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }
        throw new ServiceException("Elasticsearch 查询返回格式异常");
    }
}
