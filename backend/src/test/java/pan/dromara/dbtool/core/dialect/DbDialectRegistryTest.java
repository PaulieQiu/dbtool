package pan.dromara.dbtool.core.dialect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * DbDialectRegistry（方言注册表 + 分页委托）单元测试。
 * 覆盖：各方言分页 SQL 正确性、未适配方言回退通用 OFFSET/FETCH、大小写不敏感。
 *
 * @author doubao
 */
class DbDialectRegistryTest {

    private final DbDialectRegistry registry = new DbDialectRegistry();

    @Test
    void mysqlUsesCommaLimit() {
        String sql = "SELECT * FROM t";
        assertEquals("SELECT * FROM t LIMIT 10, 20", registry.paginate("MYSQL", sql, 10, 20));
    }

    @Test
    void postgresUsesLimitOffset() {
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10",
            registry.paginate("POSTGRESQL", "SELECT * FROM t", 10, 20));
    }

    @Test
    void oracleUsesOffsetFetch() {
        assertEquals("SELECT * FROM t OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY",
            registry.paginate("ORACLE", "SELECT * FROM t", 10, 20));
    }

    @Test
    void clickHouseUsesCommaLimit() {
        assertEquals("SELECT * FROM t LIMIT 10, 20",
            registry.paginate("CLICKHOUSE", "SELECT * FROM t", 10, 20));
    }

    @Test
    void sqlServerAddsOrderByForOffsetFetch() {
        assertEquals("SELECT * FROM t ORDER BY (SELECT NULL) OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY",
            registry.paginate("SQLSERVER", "SELECT * FROM t", 10, 20));
    }

    @Test
    void firebirdUsesRowsRange() {
        assertEquals("SELECT * FROM t ROWS 11 TO 30",
            registry.paginate("FIREBIRD", "SELECT * FROM t", 10, 20));
    }

    @Test
    void unknownDialectFallsBackToGenericOffsetFetch() {
        assertEquals("SELECT * FROM t OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY",
            registry.paginate("HIVE", "SELECT * FROM t", 10, 20));
    }

    @Test
    void nullDbTypeFallsBackToGeneric() {
        assertEquals("SELECT * FROM t OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY",
            registry.paginate(null, "SELECT * FROM t", 0, 20));
    }

    @Test
    void dialectLookupIsCaseInsensitiveAndCached() {
        DbDialectAdapter a = registry.dialect("mysql");
        assertNotNull(a);
        assertSame(a, registry.dialect("MYSQL"));
        assertSame(a, registry.dialect("MySql"));
    }
}
