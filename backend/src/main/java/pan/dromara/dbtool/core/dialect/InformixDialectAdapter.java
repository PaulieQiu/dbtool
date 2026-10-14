package pan.dromara.dbtool.core.dialect;

import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

/**
 * IBM Informix 方言适配器(C.3.35 新增, 对照 dbx informix.yaml)
 * <p>Informix 元数据走其系统目录(systables/syscolumns/sysindexes/sysconstraints/
 * sysreferences/systriggers), 而非 information_schema。Informix 无"库中库"概念,
 * "模式"即表所有者(tabowner), 因此本适配器将 databasesSql 返回 owner 列表作为模式:</p>
 * <ul>
 *   <li>databasesSql: 按 tabowner 去重(视为可浏览的模式);</li>
 *   <li>tablesSql: 仅返回 tabid &gt; 99 的用户表/视图(tabtype T/V/E);</li>
 *   <li>columnsSql: 由 syscolumns.coltype 低位码 CASE 映射类型(1 为 SMALLINT、2 INTEGER、
 *       5 DECIMAL、7 DATE、12 VARCHAR、18 BIGINT 等), bit 0x100 表示 NOT NULL;</li>
 *   <li>索引/约束/外键/触发器目录依赖多表关联且 coltype 编码特殊, 本批按诚实边界
 *       返回恒空结果集消费占位符, 待真实实例回归后再逐类补齐。</li>
 * </ul>
 * <p>分页: Informix 支持 SELECT SKIP n FIRST m; 标识符: 双引号(也可用无引号); 无 SHOW CREATE TABLE。</p>
 * <p>注意: 驱动坐标 com.ibm.informix:ifxjdbc 需 JDBC URL 携带 INFORMIXSERVER 参数
 * (见 DbTypeRegistry INFORMIX 注册项)。本适配器属"已适配待实测"。</p>
 *
 * @author doubao
 */
public class InformixDialectAdapter implements DbDialectAdapter {

    /** coltype 低位码 → 类型名(Informix syscolumns.coltype 低 8 位) */
    private static final String TYPE_CASE =
        "CASE (c.coltype & 255) "
        + "WHEN 0 THEN 'CHAR' WHEN 1 THEN 'SMALLINT' WHEN 2 THEN 'INTEGER' "
        + "WHEN 3 THEN 'FLOAT' WHEN 4 THEN 'SMALLFLOAT' WHEN 5 THEN 'DECIMAL' "
        + "WHEN 6 THEN 'SERIAL' WHEN 7 THEN 'DATE' WHEN 8 THEN 'MONEY' "
        + "WHEN 9 THEN 'DATETIME' WHEN 10 THEN 'BYTE' WHEN 11 THEN 'TEXT' "
        + "WHEN 12 THEN 'VARCHAR' WHEN 13 THEN 'INTERVAL' WHEN 14 THEN 'NCHAR' "
        + "WHEN 15 THEN 'NVARCHAR' WHEN 16 THEN 'INT8' WHEN 17 THEN 'SERIAL8' "
        + "WHEN 18 THEN 'BIGINT' WHEN 19 THEN 'BIGSERIAL' WHEN 20 THEN 'LVARCHAR' "
        + "ELSE 'OTHER' END";

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "INFORMIX".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        // Informix 无库中库, 模式 = 表所有者(tabowner); 仅返回用户对象(tabid>99)
        return "SELECT DISTINCT tabowner AS SCHEMA_NAME FROM systables WHERE tabid > 99 ORDER BY 1";
    }

    @Override
    public String tablesSql() {
        return "SELECT t.tabname AS TABLE_NAME, COALESCE(t.remarks,'') AS TABLE_COMMENT, "
            + "CASE t.tabtype WHEN 'T' THEN 'BASE TABLE' WHEN 'V' THEN 'VIEW' "
            + "WHEN 'E' THEN 'EXTERNAL' ELSE 'OTHER' END AS TABLE_TYPE, "
            + "'' AS ENGINE, 0 AS TABLE_ROWS, '' AS CREATE_TIME "
            + "FROM systables t WHERE t.tabowner = ? AND t.tabid > 99 "
            + "ORDER BY TABLE_TYPE, TABLE_NAME";
    }

    @Override
    public String columnsSql() {
        return "SELECT c.colname AS COLUMN_NAME, " + TYPE_CASE + " AS DATA_TYPE, "
            + TYPE_CASE + " AS COLUMN_TYPE, "
            + "CASE WHEN (c.coltype & 256) <> 0 THEN 'NO' ELSE 'YES' END AS IS_NULLABLE, "
            + "'' AS COLUMN_DEFAULT, '' AS COLUMN_COMMENT, "
            + "'' AS COLUMN_KEY, '' AS EXTRA, "
            + "'' AS CHARACTER_SET_NAME, '' AS COLLATION_NAME "
            + "FROM syscolumns c JOIN systables t ON t.tabid = c.tabid "
            + "WHERE t.tabowner = ? AND t.tabname = ? ORDER BY c.colno";
    }

    @Override
    public String indexesSql() {
        // Informix 索引列明细编码于 sysindexes 位域, 待实测后补齐; 恒空消费 (schema,table)
        return "SELECT '' AS INDEX_NAME, '' AS COLUMN_NAME, 0 AS SEQ_IN_INDEX, "
            + "0 AS NON_UNIQUE, '' AS INDEX_TYPE, '' AS INDEX_COMMENT "
            + "FROM systables WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String constraintsSql() {
        return "SELECT '' AS CONSTRAINT_NAME, '' AS CONSTRAINT_TYPE, '' AS COLUMN_NAME, "
            + "0 AS ORDINAL_POSITION "
            + "FROM systables WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT '' AS CONSTRAINT_NAME, '' AS TABLE_NAME, '' AS COLUMN_NAME, "
            + "0 AS ORDINAL_POSITION, '' AS REFERENCED_TABLE_NAME, '' AS REFERENCED_COLUMN_NAME, "
            + "'' AS UPDATE_RULE, '' AS DELETE_RULE "
            + "FROM systables WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String referencesSql() {
        return "SELECT '' AS CONSTRAINT_NAME, '' AS TABLE_NAME, '' AS COLUMN_NAME, "
            + "0 AS ORDINAL_POSITION, '' AS REFERENCED_TABLE_NAME, '' AS REFERENCED_COLUMN_NAME, "
            + "'' AS UPDATE_RULE, '' AS DELETE_RULE "
            + "FROM systables WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String triggersSql() {
        return "SELECT '' AS TRIGGER_NAME, '' AS ACTION_TIMING, '' AS EVENT_MANIPULATION, "
            + "'' AS EVENT_OBJECT_TABLE, '' AS ACTION_STATEMENT, '' AS CREATED "
            + "FROM systables WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        // Informix 分页语法: SELECT SKIP offset FIRST size ...
        return "SELECT SKIP " + offset + " FIRST " + size + " " + stripSelect(sql);
    }

    private String stripSelect(String sql) {
        String s = sql == null ? "" : sql.trim();
        if (s.regionMatches(true, 0, "SELECT", 0, 6)) {
            return s.substring(6).trim();
        }
        return s;
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        // Informix 无 SHOW CREATE TABLE, 由上层按列元数据重建
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        return "CREATE DATABASE " + quote(dbName) + " WITH LOG";
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE " + quote(schema) + "." + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "RENAME TABLE " + quote(schema) + "." + quote(oldName) + " TO " + quote(newName);
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " ADD (" + colDdl + ")";
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " DROP " + quote(column);
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        // Informix 改列语法为 MODIFY (col type), 不支持一步内改名
        if (oldColumnName != null && !oldColumnName.equals(column.getColumnName())) {
            return null;
        }
        String type = column.getColumnType() != null && !column.getColumnType().isEmpty()
            ? column.getColumnType() : column.getDataType();
        if (type == null || type.isEmpty()) {
            throw new ServiceException("列类型不能为空");
        }
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " MODIFY " + quote(column.getColumnName()) + " " + type;
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        String type = c.getColumnType() != null && !c.getColumnType().isEmpty()
            ? c.getColumnType() : c.getDataType();
        if (type == null || type.isEmpty()) {
            throw new ServiceException("列类型不能为空");
        }
        StringBuilder sb = new StringBuilder(quote(c.getColumnName())).append(' ').append(type);
        boolean notNull = c.getNullable() != null && "NO".equalsIgnoreCase(c.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
        return sb.toString();
    }
}
