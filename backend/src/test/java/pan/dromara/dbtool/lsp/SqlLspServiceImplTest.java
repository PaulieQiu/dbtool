package pan.dromara.dbtool.lsp;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SqlLspServiceImpl（轻量 SQL LSP 代理）单元测试。
 * 覆盖：会话生命周期 / completion / hover / diagnostics / 白名单安全。
 *
 * @author doubao
 */
@SuppressWarnings("unchecked")
class SqlLspServiceImplTest {

    private IDbConnService connService;
    private IDbMetaService metaService;
    private SqlLspServiceImpl service;

    @BeforeEach
    void setUp() {
        connService = mock(IDbConnService.class);
        metaService = mock(IDbMetaService.class);
        service = new SqlLspServiceImpl(connService, metaService);
        ReflectUtil.setFieldValue(service, "allowConns", "7");

        DbConn conn = new DbConn();
        conn.setConnName("test");
        when(connService.getById(7L)).thenReturn(conn);

        DbTableVo user = new DbTableVo();
        user.setTableName("user");
        when(metaService.tables(7L, "testdb")).thenReturn(List.of(user));

        DbColumnVo id = new DbColumnVo();
        id.setColumnName("id");
        id.setColumnType("bigint");
        DbColumnVo name = new DbColumnVo();
        name.setColumnName("name");
        name.setColumnType("varchar(64)");
        name.setColumnComment("姓名");
        when(metaService.columns(7L, "testdb", "user")).thenReturn(List.of(id, name));
    }

    // ==================== 会话生命周期 ====================

    @Test
    void shouldStartRefreshAndStopSession() {
        String sid = service.startSession(7L, "testdb");
        assertNotNull(sid);
        service.refreshMetadata(sid);
        service.stopSession(sid);
        // 会话已销毁 → 后续请求抛异常
        assertThrows(ServiceException.class, () -> service.sendRequest(sid, "completion", Map.of()));
    }

    @Test
    void shouldRejectSessionForConnOutsideWhitelist() {
        SqlLspServiceImpl svc = new SqlLspServiceImpl(connService, metaService); // allowConns 空
        assertThrows(ServiceException.class, () -> svc.startSession(7L, "testdb"));
    }

    // ==================== completion ====================

    @Test
    void shouldCompleteKeywordsAndTables() {
        String sid = service.startSession(7L, "testdb");
        List<Map<String, Object>> items = (List<Map<String, Object>>) service.sendRequest(
            sid, "completion", Map.of("text", "", "line", 0, "character", 0));
        List<String> labels = items.stream().map(m -> String.valueOf(m.get("label"))).toList();
        assertTrue(labels.contains("SELECT"));
        assertTrue(labels.contains("user"));
    }

    @Test
    void shouldCompleteTableAfterFromClause() {
        String sid = service.startSession(7L, "testdb");
        List<Map<String, Object>> items = (List<Map<String, Object>>) service.sendRequest(
            sid, "completion", Map.of("text", "SELECT * FROM user", "line", 0, "character", 18));
        List<String> labels = items.stream().map(m -> String.valueOf(m.get("label"))).toList();
        // FROM 后的表名上下文：返回表补全
        assertTrue(labels.contains("user"));
    }

    // ==================== hover ====================

    @Test
    void shouldHoverTable() {
        String sid = service.startSession(7L, "testdb");
        Object out = service.sendRequest(
            sid, "hover", Map.of("text", "SELECT * FROM user", "line", 0, "character", 15));
        assertTrue(String.valueOf(out).contains("表 user"));
        assertTrue(String.valueOf(out).contains("id"));
    }

    @Test
    void shouldHoverColumn() {
        String sid = service.startSession(7L, "testdb");
        Object out = service.sendRequest(
            sid, "hover", Map.of("text", "SELECT id FROM user", "line", 0, "character", 7));
        assertTrue(String.valueOf(out).contains("列 id"));
        assertTrue(String.valueOf(out).contains("bigint"));
    }

    // ==================== diagnostics ====================

    @Test
    void shouldDiagnoseReferenceToMissingTable() {
        String sid = service.startSession(7L, "testdb");
        List<Map<String, Object>> diags = (List<Map<String, Object>>) service.sendRequest(
            sid, "diagnostics", Map.of("text", "SELECT * FROM ghost"));
        boolean hasGhost = diags.stream()
            .anyMatch(d -> String.valueOf(d.get("message")).contains("ghost"));
        assertTrue(hasGhost);
    }

    @Test
    void shouldDiagnoseDeleteWithoutWhere() {
        String sid = service.startSession(7L, "testdb");
        List<Map<String, Object>> diags = (List<Map<String, Object>>) service.sendRequest(
            sid, "diagnostics", Map.of("text", "DELETE FROM user"));
        boolean hasWhere = diags.stream()
            .anyMatch(d -> String.valueOf(d.get("message")).contains("WHERE"));
        assertTrue(hasWhere);
    }

    @Test
    void shouldDiagnoseEmptySqlAsWarning() {
        String sid = service.startSession(7L, "testdb");
        List<Map<String, Object>> diags = (List<Map<String, Object>>) service.sendRequest(
            sid, "diagnostics", Map.of("text", "   "));
        assertTrue(diags.stream()
            .anyMatch(d -> "WARNING".equals(String.valueOf(d.get("severity")))));
    }

    @Test
    void shouldRejectUnsupportedMethod() {
        String sid = service.startSession(7L, "testdb");
        assertThrows(ServiceException.class, () -> service.sendRequest(sid, "foo", Map.of()));
    }
}
