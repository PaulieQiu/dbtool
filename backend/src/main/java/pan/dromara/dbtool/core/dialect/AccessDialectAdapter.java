package pan.dromara.dbtool.core.dialect;

import org.dromara.common.core.exception.ServiceException;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

/**
 * MS Access 方言适配器(C.3.35 新增, 对照 dbx access.yaml)
 * <p>通过 UCanAccess(底层 HSQLDB 引擎)访问 .accdb/.mdb 文件, 元数据经
 * INFORMATION_SCHEMA(TABLES/COLUMNS/SCHEMATA)读取。UCanAccess 将 Access 表映射到
 * HSQLDB 目录, 但标准 INFORMATION_SCHEMA 不完整暴露 Access 的索引/约束/外键/触发器,
 * 本批按诚实边界对这几类返回恒空结果集消费占位符, 待真实文件库回归后再补齐。</p>
 * <ul>
 *   <li>databasesSql: INFORMATION_SCHEMA.SCHEMATA(文件库无库中库, 通常为 PUBLIC);</li>
 *   <li>tablesSql: 仅 BASE TABLE 用户表, 排除底层系统辅助表;</li>
 *   <li>columnsSql: INFORMATION_SCHEMA.COLUMNS(列别名与 MySQL 对齐);</li>
 *   <li>分页: LIMIT size OFFSET offset(HSQLDB 语法); 标识符: 双引号; 无 SHOW CREATE TABLE。</li>
 * </ul>
 * <p>驱动: net.sf.ucanaccess:ucanaccess, URL jdbc:ucanaccess://{db}(详见 DbTypeRegistry MSACCESS)。
 * 本适配器属"已适配待实测"。</p>
 *
 * @author doubao
 */
public class AccessDialectAdapter implements DbDialectAdapter {

    @Override
    public boolean supports(String dbType) {
        return dbType != null && "MSACCESS".equalsIgnoreCase(dbType.trim());
    }

    @Override
    public String quote(String name) {
        // UCanAccess/HSQLDB 支持双引号引用; Access 原生方括号 [] 也接受, 双引号更兼容 SQL 标准
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    @Override
    public String databasesSql() {
        return "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY SCHEMA_NAME";
    }

    @Override
    public String tablesSql() {
        return "SELECT TABLE_NAME, COALESCE(REMARKS,'') AS TABLE_COMMENT, TABLE_TYPE, '' AS ENGINE, "
            + "0 AS TABLE_ROWS, '' AS CREATE_TIME "
            + "FROM INFORMATION_SCHEMA.TABLES "
            + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' "
            + "AND TABLE_NAME NOT LIKE 'SYS\\_%' ESCAPE '\\' "
            + "ORDER BY TABLE_NAME";
    }

    @Override
    public String columnsSql() {
        return "SELECT COLUMN_NAME, DATA_TYPE, DATA_TYPE AS COLUMN_TYPE, IS_NULLABLE, "
            + "COALESCE(COLUMN_DEFAULT,'') AS COLUMN_DEFAULT, COALESCE(REMARKS,'') AS COLUMN_COMMENT, "
            + "'' AS COLUMN_KEY, '' AS EXTRA, "
            + "COALESCE(CHARACTER_SET_NAME,'') AS CHARACTER_SET_NAME, COALESCE(COLLATION_NAME,'') AS COLLATION_NAME "
            + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
            + "ORDER BY ORDINAL_POSITION";
    }

    @Override
    public String indexesSql() {
        // UCanAccess 的索引目录不走标准 INFORMATION_SCHEMA.INDEXES, 待实测后补齐; 恒空消费 (schema,table)
        return "SELECT '' AS INDEX_NAME, '' AS COLUMN_NAME, 0 AS SEQ_IN_INDEX, "
            + "0 AS NON_UNIQUE, '' AS INDEX_TYPE, '' AS INDEX_COMMENT "
            + "FROM INFORMATION_SCHEMA.TABLES WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String constraintsSql() {
        return "SELECT '' AS CONSTRAINT_NAME, '' AS CONSTRAINT_TYPE, '' AS COLUMN_NAME, "
            + "0 AS ORDINAL_POSITION "
            + "FROM INFORMATION_SCHEMA.TABLES WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String foreignKeysSql() {
        return "SELECT '' AS CONSTRAINT_NAME, '' AS TABLE_NAME, '' AS COLUMN_NAME, "
            + "0 AS ORDINAL_POSITION, '' AS REFERENCED_TABLE_NAME, '' AS REFERENCED_COLUMN_NAME, "
            + "'' AS UPDATE_RULE, '' AS DELETE_RULE "
            + "FROM INFORMATION_SCHEMA.TABLES WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String referencesSql() {
        return "SELECT '' AS CONSTRAINT_NAME, '' AS TABLE_NAME, '' AS COLUMN_NAME, "
            + "0 AS ORDINAL_POSITION, '' AS REFERENCED_TABLE_NAME, '' AS REFERENCED_COLUMN_NAME, "
            + "'' AS UPDATE_RULE, '' AS DELETE_RULE "
            + "FROM INFORMATION_SCHEMA.TABLES WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String triggersSql() {
        return "SELECT '' AS TRIGGER_NAME, '' AS ACTION_TIMING, '' AS EVENT_MANIPULATION, "
            + "'' AS EVENT_OBJECT_TABLE, '' AS ACTION_STATEMENT, '' AS CREATED "
            + "FROM INFORMATION_SCHEMA.TABLES WHERE 1 = 0 AND ? = ?";
    }

    @Override
    public String paginate(String sql, int offset, int size) {
        return sql + " LIMIT " + size + " OFFSET " + offset;
    }

    @Override
    public String showCreateTableSql(String schema, String table) {
        return null;
    }

    @Override
    public String createDatabaseSql(String dbName, String charset) {
        // Access 为单文件库, 无 CREATE DATABASE
        return null;
    }

    @Override
    public String dropTableSql(String schema, String table) {
        return "DROP TABLE " + quote(schema) + "." + quote(table);
    }

    @Override
    public String renameTableSql(String schema, String oldName, String newName) {
        return "ALTER TABLE " + quote(schema) + "." + quote(oldName) + " RENAME TO " + quote(newName);
    }

    @Override
    public String addColumnSql(String schema, String table, String colDdl) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " ADD COLUMN " + colDdl;
    }

    @Override
    public String dropColumnSql(String schema, String table, String column) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " DROP COLUMN " + quote(column);
    }

    @Override
    public String modifyColumnSql(String schema, String table, String oldColumnName, DbColumnVo column) {
        if (oldColumnName != null && !oldColumnName.equals(column.getColumnName())) {
            return null;
        }
        String type = column.getColumnType() != null && !column.getColumnType().isEmpty()
            ? column.getColumnType() : column.getDataType();
        if (type == null || type.isEmpty()) {
            throw new ServiceException("列类型不能为空");
        }
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " ALTER COLUMN "
            + quote(column.getColumnName()) + " " + type;
    }

    @Override
    public String columnDdl(DbColumnVo c) {
        String type = c.getColumnType() != null && !c.getColumnType().isEmpty()
            ? c.getColumnType() : c.getDataType();
        if (type == null || type.isEmpty()) {
            throw new ServiceException("列类型不能为空");
        }
        StringBuilder sb = new StringBuilder(quote(c.getColumnName())).append(' ').append(type);
        if (c.getExtra() != null && c.getExtra().toLowerCase().contains("auto_increment")) {
            sb.append(" GENERATED BY DEFAULT AS IDENTITY");
            return sb.toString();
        }
        boolean notNull = c.getNullable() != null && "NO".equalsIgnoreCase(c.getNullable());
        sb.append(notNull ? " NOT NULL" : " NULL");
        return sb.toString();
    }
}
