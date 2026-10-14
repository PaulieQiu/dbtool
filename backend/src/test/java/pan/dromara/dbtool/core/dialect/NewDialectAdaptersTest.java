package pan.dromara.dbtool.core.dialect;

import pan.dromara.dbtool.core.DbDriverManager;
import pan.dromara.dbtool.core.DbTypeRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C.3.35 新增方言(Informix/Access 独立适配器 + openGauss/xugu/databend 协议族扩展)单元测试。
 * 覆盖: 方言命中、分页 SQL、元数据 SQL 占位符契约(columnsSql 等恰 2 个 ?, tablesSql 恰 1 个 ?)、
 * 能力矩阵(browse/ddl/dialect) 与 DbTypeRegistry 注册。
 * <p>@Tag("dev") 匹配根 pom surefire 的 groups=${profiles.active}(dev 默认激活) 约定。</p>
 *
 * @author doubao
 */
@Tag("dev")
class NewDialectAdaptersTest {

    private final DbDialectRegistry registry = new DbDialectRegistry();

    // ---------- 方言命中 ----------

    @Test
    void informixResolvesToInformixAdapter() {
        DbDialectAdapter a = registry.dialect("INFORMIX");
        assertInstanceOf(InformixDialectAdapter.class, a);
        assertInstanceOf(InformixDialectAdapter.class, registry.dialect("informix"));
    }

    @Test
    void msaccessResolvesToAccessAdapter() {
        assertInstanceOf(AccessDialectAdapter.class, registry.dialect("MSACCESS"));
    }

    @Test
    void opengaussAndXuguResolveToPgFamily() {
        assertInstanceOf(PostgreSqlDialectAdapter.class, registry.dialect("OPENGUSS"));
        assertInstanceOf(PostgreSqlDialectAdapter.class, registry.dialect("xugu"));
    }

    @Test
    void databendResolvesToMySqlFamily() {
        assertInstanceOf(MySqlDialectAdapter.class, registry.dialect("DATABEND"));
    }

    @Test
    void questdbResolvesToPgFamily() {
        assertInstanceOf(PostgreSqlDialectAdapter.class, registry.dialect("QUESTDB"));
        assertInstanceOf(PostgreSqlDialectAdapter.class, registry.dialect("questdb"));
    }

    @Test
    void manticoreResolvesToMySqlFamily() {
        assertInstanceOf(MySqlDialectAdapter.class, registry.dialect("MANTICORE"));
    }

    @Test
    void gaussdbAndVastbaseResolveToPgFamily() {
        assertInstanceOf(PostgreSqlDialectAdapter.class, registry.dialect("GAUSSDB"));
        assertInstanceOf(PostgreSqlDialectAdapter.class, registry.dialect("VASTBASE"));
    }

    // ---------- 分页 ----------

    @Test
    void informixUsesSkipFirst() {
        assertEquals("SELECT SKIP 10 FIRST 20 * FROM t",
            registry.paginate("INFORMIX", "SELECT * FROM t", 10, 20));
    }

    @Test
    void accessUsesLimitOffset() {
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10",
            registry.paginate("MSACCESS", "SELECT * FROM t", 10, 20));
    }

    @Test
    void opengaussAndXuguUsePgLimitOffset() {
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10", registry.paginate("OPENGUSS", "SELECT * FROM t", 10, 20));
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10", registry.paginate("XUGU", "SELECT * FROM t", 10, 20));
    }

    @Test
    void databendUsesMySqlCommaLimit() {
        assertEquals("SELECT * FROM t LIMIT 10, 20", registry.paginate("DATABEND", "SELECT * FROM t", 10, 20));
    }

    @Test
    void questdbUsesPgLimitOffset() {
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10", registry.paginate("QUESTDB", "SELECT * FROM t", 10, 20));
    }

    @Test
    void manticoreUsesMySqlCommaLimit() {
        assertEquals("SELECT * FROM t LIMIT 10, 20", registry.paginate("MANTICORE", "SELECT * FROM t", 10, 20));
    }

    @Test
    void gaussdbAndVastbaseUsePgLimitOffset() {
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10", registry.paginate("GAUSSDB", "SELECT * FROM t", 10, 20));
        assertEquals("SELECT * FROM t LIMIT 20 OFFSET 10", registry.paginate("VASTBASE", "SELECT * FROM t", 10, 20));
    }

    // ---------- 元数据 SQL 占位符契约(C.3.30): columns 等恰 2 个 ?, tables 恰 1 个 ? ----------

    @Test
    void informixPlaceholderContract() {
        InformixDialectAdapter a = new InformixDialectAdapter();
        assertEquals(1, count(a.tablesSql()));
        assertEquals(2, count(a.columnsSql()));
        assertEquals(2, count(a.indexesSql()));
        assertEquals(2, count(a.constraintsSql()));
        assertEquals(2, count(a.foreignKeysSql()));
        assertEquals(2, count(a.referencesSql()));
        assertEquals(2, count(a.triggersSql()));
    }

    @Test
    void accessPlaceholderContract() {
        AccessDialectAdapter a = new AccessDialectAdapter();
        assertEquals(1, count(a.tablesSql()));
        assertEquals(2, count(a.columnsSql()));
        assertEquals(2, count(a.indexesSql()));
        assertEquals(2, count(a.constraintsSql()));
        assertEquals(2, count(a.foreignKeysSql()));
        assertEquals(2, count(a.referencesSql()));
        assertEquals(2, count(a.triggersSql()));
    }

    private static int count(String sql) {
        return sql == null ? 0 : (int) sql.chars().filter(c -> c == '?').count();
    }

    // ---------- 能力矩阵与注册 ----------

    @Test
    void newTypesRegisteredAndDialectAdapted() {
        List<String> capsInformix = DbTypeRegistry.capabilitiesOf("INFORMIX");
        assertTrue(capsInformix.contains("browse") && capsInformix.contains("ddl") && capsInformix.contains("dialect"));
        assertTrue(DbTypeRegistry.capabilitiesOf("MSACCESS").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("OPENGUSS").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("XUGU").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("DATABEND").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("QUESTDB").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("MANTICORE").contains("browse"));
        // IRIS 无独立方言适配器(仅连接+查询): query 有, browse/ddl/dialect 无
        assertTrue(DbTypeRegistry.capabilitiesOf("IRIS").contains("query"));
        assertTrue(!DbTypeRegistry.capabilitiesOf("IRIS").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("GAUSSDB").contains("browse"));
        assertTrue(DbTypeRegistry.capabilitiesOf("VASTBASE").contains("browse"));
    }

    @Test
    void registryHasNewTypesWithDrivers() {
        assertEquals("org.opengauss.Driver", DbTypeRegistry.of("OPENGUSS").getDriverClass());
        assertEquals("com.xugudb.jdbc.Driver", DbTypeRegistry.of("XUGU").getDriverClass());
        assertEquals("com.databend.jdbc.DatabendDriver", DbTypeRegistry.of("DATABEND").getDriverClass());
        assertEquals("org.postgresql.Driver", DbTypeRegistry.of("QUESTDB").getDriverClass());
        assertEquals("com.mysql.cj.jdbc.Driver", DbTypeRegistry.of("MANTICORE").getDriverClass());
        assertEquals("com.intersystems.jdbc.IRISDriver", DbTypeRegistry.of("IRIS").getDriverClass());
        assertEquals("org.opengauss.Driver", DbTypeRegistry.of("GAUSSDB").getDriverClass());
        assertEquals("org.opengauss.Driver", DbTypeRegistry.of("VASTBASE").getDriverClass());
    }

    // ---------- 厂商私有驱动类型(外置驱动上传, driverClass 未知时自动发现) ----------

    @Test
    void externalDriverPrivateTypesRegisteredWithBlankClass() {
        // 无 Central 坐标的厂商私有类型: driverClass/artifact 留空, 依赖上传 jar + 自动发现
        assertNull(DbTypeRegistry.of("TRANSARP").getDriverClass());
        assertNull(DbTypeRegistry.of("TRANSARP").getArtifact());
        assertNull(DbTypeRegistry.of("GOLDENDB").getDriverClass());
        assertNull(DbTypeRegistry.of("KWDB").getDriverClass());
        assertNull(DbTypeRegistry.of("SUNDB").getDriverClass());
    }

    @Test
    void ensureDriverNativeProtocolReadyWithoutJar() {
        DbDriverManager mgr = new DbDriverManager();
        // 原生协议类型(Redis/MongoDB): driverClass 空 + 非 jdbc url → 无需 JDBC 驱动, 直接就绪
        assertTrue(mgr.ensureDriver("REDIS"));
        assertTrue(mgr.ensureDriver("MONGODB"));
        // 厂商私有 JDBC 类型(transwarp): driverClass 空但 url 为 jdbc: → 需上传外部 jar, 未上传则加载失败(不应 NPE)
        assertFalse(mgr.ensureDriver("TRANSARP"));
    }

    @Test
    void isDriverReadyNativeVsPrivateJdbc() {
        DbDriverManager mgr = new DbDriverManager();
        assertTrue(mgr.isDriverReady("REDIS"));       // 非 JDBC → 就绪
        assertTrue(mgr.isDriverReady("MONGODB"));
        assertFalse(mgr.isDriverReady("TRANSARP"));   // JDBC + 未注册外部驱动 → 未就绪
        assertFalse(mgr.isDriverReady("GOLDENDB"));
    }
}
