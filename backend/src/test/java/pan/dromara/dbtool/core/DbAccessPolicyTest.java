package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.service.EsService;

import cn.hutool.json.JSONObject;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class DbAccessPolicyTest {

    @Test
    void shouldAcceptClearlyReadOnlyStatements() {
        assertTrue(DbAccessPolicy.isReadOnlyStatement("SELECT id FROM users"));
        assertTrue(DbAccessPolicy.isReadOnlyStatement("-- report query\nSHOW TABLES"));
        assertTrue(DbAccessPolicy.isReadOnlyStatement("/* generated */ DESCRIBE users"));
        assertTrue(DbAccessPolicy.isReadOnlyStatement("EXPLAIN SELECT id FROM users"));
    }

    @Test
    void shouldRejectStatementsThatCanMutateDataOrSession() {
        assertFalse(DbAccessPolicy.isReadOnlyStatement("/* comment */ DELETE FROM users"));
        assertFalse(DbAccessPolicy.isReadOnlyStatement("WITH changed AS (DELETE FROM users) SELECT * FROM changed"));
        assertFalse(DbAccessPolicy.isReadOnlyStatement("EXPLAIN ANALYZE DELETE FROM users"));
        assertFalse(DbAccessPolicy.isReadOnlyStatement("SELECT id INTO backup_users FROM users"));
        assertThrows(ServiceException.class, () -> DbAccessPolicy.isReadOnlyStatement("/* unterminated"));
    }

    @Test
    void shouldRejectWritesForReadOnlyConnections() {
        DbConn conn = new DbConn();
        conn.setConnName("reporting");
        conn.setAccessMode("READ_ONLY");

        assertTrue(DbAccessPolicy.isReadOnly(conn));
        assertThrows(ServiceException.class, () -> DbAccessPolicy.requireWritable(conn));
        assertFalse(DbAccessPolicy.isReadOnly(new DbConn()));
    }

    @Test
    void shouldClassifyNoSqlReadOperations() {
        assertTrue(DbAccessPolicy.isReadOnlyRedisCommand("GET key"));
        assertTrue(DbAccessPolicy.isReadOnlyRedisCommand("keys*user:*"));
        assertFalse(DbAccessPolicy.isReadOnlyRedisCommand("SET key value"));
        assertTrue(EsService.isReadOnlyConsoleRequest("post", "/users/_search"));
        assertFalse(EsService.isReadOnlyConsoleRequest("DELETE", "/users/_doc/1"));
    }

    @Test
    void shouldConsumeHighRiskApprovalOnlyOnceForItsBoundOperation() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(bucket);
        when(bucket.getAndDelete())
            .thenReturn(confirmationPayload(7L, "DROP TABLE users", "user-1"))
            .thenReturn(null);

        DbOperationConfirmationService confirmationService = new DbOperationConfirmationService(redisson);
        String token = confirmationService.issue(7L, "DROP TABLE users", "user-1");

        assertTrue(confirmationService.consume(token, 7L, "DROP TABLE users", "user-1"));
        assertFalse(confirmationService.consume(token, 7L, "DROP TABLE users", "user-1"));
    }

    @Test
    void shouldRejectApprovalForAnotherUserConnectionOrSql() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(bucket);
        when(bucket.getAndDelete()).thenReturn(confirmationPayload(7L, "DROP TABLE users", "user-1"));

        DbOperationConfirmationService confirmationService = new DbOperationConfirmationService(redisson);

        assertFalse(confirmationService.consume("token", 8L, "DROP TABLE users", "user-1"));
    }

    @Test
    void shouldRejectApprovalForDifferentSqlAndUser() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket(anyString(), eq(StringCodec.INSTANCE))).thenReturn(bucket);
        when(bucket.getAndDelete())
            .thenReturn(confirmationPayload(7L, "DROP TABLE users", "user-1"))
            .thenReturn(confirmationPayload(7L, "DROP TABLE users", "user-1"));

        DbOperationConfirmationService confirmationService = new DbOperationConfirmationService(redisson);

        assertFalse(confirmationService.consume("token", 7L, "DROP TABLE accounts", "user-1"));
        assertFalse(confirmationService.consume("token", 7L, "DROP TABLE users", "user-2"));
    }

    private String confirmationPayload(Long connId, String sql, String userId) {
        JSONObject value = new JSONObject();
        value.set("connId", connId);
        value.set("sqlDigest", cn.hutool.crypto.SecureUtil.sha256(sql));
        value.set("userId", userId);
        return value.toString();
    }
}
