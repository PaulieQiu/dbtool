package pan.dromara.dbtool.ai;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.service.IDbConnService;
import pan.dromara.dbtool.service.IDbMetaService;

import cn.hutool.core.util.ReflectUtil;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiSqlServiceImpl 规则引擎（LLM 关闭、离线降级路径）单元测试。
 * 覆盖：高危检测 / SQL 解释 / 自然语言转 SQL(规则) / 优化建议 / 测试数据生成 / 白名单安全。
 *
 * @author doubao
 */
class AiSqlServiceImplTest {

    private IDbConnService connService;
    private IDbMetaService metaService;
    private AiSqlServiceImpl service;

    @BeforeEach
    void setUp() {
        connService = mock(IDbConnService.class);
        metaService = mock(IDbMetaService.class);
        service = new AiSqlServiceImpl(connService, metaService);
        ReflectUtil.setFieldValue(service, "allowConns", "7");

        DbConn conn = new DbConn();
        conn.setConnName("test");
        conn.setAccessMode("READ_ONLY");
        when(connService.getById(7L)).thenReturn(conn);

        DbTableVo user = new DbTableVo();
        user.setTableName("user");
        user.setTableComment("用户表");
        when(metaService.tables(7L, "testdb")).thenReturn(List.of(user));
        when(metaService.databases(7L)).thenReturn(List.of("testdb"));

        DbColumnVo id = new DbColumnVo();
        id.setColumnName("id");
        id.setColumnType("bigint");
        DbColumnVo name = new DbColumnVo();
        name.setColumnName("name");
        name.setColumnType("varchar(64)");
        name.setColumnComment("姓名");
        when(metaService.columns(7L, "testdb", "user")).thenReturn(List.of(id, name));
    }

    // ==================== detectRisk（纯规则，无需连接） ====================

    @Test
    void shouldClassifyDestructiveDdlAsCritical() {
        assertEquals("CRITICAL", service.detectRisk("DROP TABLE users"));
        assertEquals("CRITICAL", service.detectRisk("TRUNCATE TABLE users"));
        assertEquals("CRITICAL", service.detectRisk("ALTER TABLE users ADD COLUMN x INT"));
    }

    @Test
    void shouldClassifyDeleteOrUpdateWithoutWhereAsCritical() {
        assertEquals("CRITICAL", service.detectRisk("DELETE FROM users"));
        assertEquals("CRITICAL", service.detectRisk("UPDATE users SET x = 1"));
    }

    @Test
    void shouldClassifyDeleteOrUpdateWithWhereAsMedium() {
        assertEquals("MEDIUM", service.detectRisk("DELETE FROM users WHERE id = 1"));
        assertEquals("MEDIUM", service.detectRisk("UPDATE users SET x = 1 WHERE id = 1"));
    }

    @Test
    void shouldClassifyReadOnlyAsLowAndOtherWritesAsMedium() {
        assertEquals("LOW", service.detectRisk("SELECT * FROM users"));
        assertEquals("MEDIUM", service.detectRisk("INSERT INTO users VALUES (1)"));
        assertEquals("MEDIUM", service.detectRisk("CREATE TABLE t (id INT)"));
        assertEquals("LOW", service.detectRisk(""));
        assertEquals("LOW", service.detectRisk("   "));
    }

    // ==================== explainSql ====================

    @Test
    void shouldExplainReadOnlyQuery() {
        String out = service.explainSql("SELECT id FROM users");
        assertTrue(out.contains("【语句类型】SELECT"));
        assertTrue(out.contains("【是否只读】是"));
        assertTrue(out.contains("users"));
    }

    @Test
    void shouldExplainUnconditionalDeleteWithRisk() {
        String out = service.explainSql("DELETE FROM users");
        assertTrue(out.contains("【是否只读】否"));
        assertTrue(out.contains("【风险提示】"));
    }

    @Test
    void shouldExplainDeleteWithWhereWithoutRisk() {
        String out = service.explainSql("DELETE FROM users WHERE id = 1");
        assertTrue(out.contains("【是否只读】否"));
        assertTrue(!out.contains("【风险提示】"));
    }

    @Test
    void shouldRejectBlankOrCommentOnlySql() {
        assertThrows(ServiceException.class, () -> service.explainSql(""));
        assertThrows(ServiceException.class, () -> service.explainSql("  -- just a comment"));
    }

    // ==================== naturalLanguageToSql（规则降级） ====================

    @Test
    void shouldGenerateCountViaRuleEngine() {
        String sql = service.naturalLanguageToSql(7L, "testdb", "统计一下 user 的数量");
        assertTrue(sql.toLowerCase().contains("count(*)"));
        assertTrue(sql.contains("`user`"));
    }

    @Test
    void shouldGenerateSelectColumnsViaRuleEngine() {
        String sql = service.naturalLanguageToSql(7L, "testdb", "查询 user 的所有字段");
        assertTrue(sql.contains("id"));
        assertTrue(sql.contains("name"));
        assertTrue(sql.contains("LIMIT 100"));
    }

    @Test
    void shouldRejectConnOutsideWhitelist() {
        AiSqlServiceImpl svc = new AiSqlServiceImpl(connService, metaService); // allowConns 空 = 拒绝所有
        assertThrows(ServiceException.class, () -> svc.naturalLanguageToSql(7L, "testdb", "查询 user"));
        assertThrows(ServiceException.class, () -> svc.generateTestData(7L, "testdb", "user", 5));
        assertThrows(ServiceException.class, () -> svc.optimizeSql(7L, "testdb", "SELECT * FROM user"));
    }

    // ==================== optimizeSql（规则） ====================

    @Test
    void shouldSuggestLimitForUnboundedSelect() {
        String out = service.optimizeSql(7L, "testdb", "SELECT * FROM user");
        assertTrue(out.contains("LIMIT"));
        assertTrue(out.contains("SELECT *"));
    }

    @Test
    void shouldFlagDeleteWithoutWhere() {
        String out = service.optimizeSql(7L, "testdb", "DELETE FROM user");
        assertTrue(out.contains("WHERE"));
    }

    // ==================== generateTestData ====================

    @Test
    void shouldGenerateInsertWithRequestedRows() {
        String out = service.generateTestData(7L, "testdb", "user", 10);
        assertTrue(out.contains("INSERT INTO `testdb`.`user`"));
        assertTrue(out.contains("VALUES"));
        assertEquals(10, countValueRows(out));
    }

    @Test
    void shouldCapGeneratedRowsAt1000() {
        String out = service.generateTestData(7L, "testdb", "user", 5000);
        assertEquals(1000, countValueRows(out));
    }

    @Test
    void shouldRejectMissingTableMetadata() {
        when(metaService.columns(7L, "testdb", "ghost")).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> service.generateTestData(7L, "testdb", "ghost", 5));
    }

    /** 统计 INSERT 数据行数（每行含一个字符串示例值 test_value_） */
    private int countValueRows(String sql) {
        int count = 0;
        for (String line : sql.split("\n")) {
            if (line.contains("'test_value_")) {
                count++;
            }
        }
        return count;
    }
}
