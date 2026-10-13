package pan.dromara.dbtool;

import pan.dromara.dbtool.core.Neo4jDriverManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.Neo4jQueryBo;
import pan.dromara.dbtool.domain.vo.Neo4jGraphVo;
import pan.dromara.dbtool.domain.vo.Neo4jNodeVo;
import pan.dromara.dbtool.domain.vo.Neo4jResultVo;
import pan.dromara.dbtool.service.IDbConnService;
import pan.dromara.dbtool.service.Neo4jService;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Neo4j 纯逻辑单元测试。
 * 覆盖：查询/超时参数边界、图结果判定、节点显示名，以及只读连接拒绝写操作。
 * （真实 Cypher 执行依赖真实 Neo4j 实例，不在本测试范围。）
 *
 * @author doubao
 */
class Neo4jLogicTest {

    // ==================== Neo4jQueryBo 边界 ====================

    @Test
    void shouldApplyResultLimitBounds() {
        Neo4jQueryBo bo = new Neo4jQueryBo();
        assertEquals(500, bo.effectiveResultLimit());
        bo.setResultLimit(0);
        assertEquals(500, bo.effectiveResultLimit());
        bo.setResultLimit(100);
        assertEquals(100, bo.effectiveResultLimit());
        bo.setResultLimit(6000);
        assertEquals(5000, bo.effectiveResultLimit());
    }

    @Test
    void shouldApplyTimeoutBounds() {
        Neo4jQueryBo bo = new Neo4jQueryBo();
        assertEquals(30, bo.effectiveTimeoutSeconds());
        bo.setTimeoutSeconds(0);
        assertEquals(30, bo.effectiveTimeoutSeconds());
        bo.setTimeoutSeconds(120);
        assertEquals(120, bo.effectiveTimeoutSeconds());
        bo.setTimeoutSeconds(500);
        assertEquals(300, bo.effectiveTimeoutSeconds());
    }

    // ==================== Neo4jResultVo / Neo4jGraphVo ====================

    @Test
    void shouldDetectGraphPresence() {
        assertFalse(new Neo4jResultVo().hasGraph());

        Neo4jResultVo empty = new Neo4jResultVo();
        empty.setGraph(new Neo4jGraphVo());
        assertFalse(empty.hasGraph());

        Neo4jResultVo withNodes = new Neo4jResultVo();
        withNodes.setGraph(new Neo4jGraphVo(List.of(new Neo4jNodeVo()), List.of()));
        assertTrue(withNodes.hasGraph());
    }

    @Test
    void shouldCountGraphElements() {
        Neo4jGraphVo empty = new Neo4jGraphVo(null, null);
        assertEquals(0, empty.nodeCount());
        assertEquals(0, empty.relCount());
        assertTrue(empty.isEmpty());

        Neo4jGraphVo g = new Neo4jGraphVo(List.of(new Neo4jNodeVo()), List.of());
        assertEquals(1, g.nodeCount());
        assertFalse(g.isEmpty());
    }

    @Test
    void shouldBuildDisplayNameFromNamePropertyThenLabels() {
        Neo4jNodeVo named = new Neo4jNodeVo();
        named.setProperties(java.util.Map.of("name", "Alice"));
        assertEquals("Alice", named.displayName());
        assertTrue(named.hasProps());

        Neo4jNodeVo labeled = new Neo4jNodeVo();
        labeled.setLabels(List.of("Person"));
        labeled.setNodeId(3L);
        assertEquals("Person:3", labeled.displayName());

        Neo4jNodeVo idOnly = new Neo4jNodeVo();
        idOnly.setId("n0");
        assertEquals("n0", idOnly.displayName());
        assertFalse(idOnly.hasProps());
    }

    // ==================== 只读连接拒绝写 ====================

    @Test
    void shouldRejectWriteOnReadOnlyConnection() {
        IDbConnService connService = mock(IDbConnService.class);
        Neo4jDriverManager driverManager = mock(Neo4jDriverManager.class);

        DbConn conn = new DbConn();
        conn.setConnName("ro");
        conn.setAccessMode("READ_ONLY");
        when(connService.getById(7L)).thenReturn(conn);

        Neo4jService svc = new Neo4jService(connService, driverManager);
        Neo4jQueryBo bo = new Neo4jQueryBo();
        bo.setConnId(7L);
        bo.setCypher("CREATE (n)");
        bo.setWrite(true);

        assertThrows(ServiceException.class, () -> svc.query(bo));
    }
}
