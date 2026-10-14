package pan.dromara.dbtool.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("dev")
class DbQueryExecutionRegistryTest {

    @Test
    void onlyOwnerCanCancelAnActiveStatement() throws Exception {
        DbQueryExecutionRegistry registry = new DbQueryExecutionRegistry();
        DbQueryExecutionRegistry.Execution execution = registry.begin("query-1", "user-1");
        Statement statement = mock(Statement.class);
        registry.attach("query-1", execution, statement);

        assertFalse(registry.cancel("query-1", "user-2"));
        assertTrue(registry.cancel("query-1", "user-1"));
        assertTrue(execution.isCancelled());
        verify(statement).cancel();
    }

    @Test
    void remembersCancellationRequestedBeforeStatementIsAttached() throws Exception {
        DbQueryExecutionRegistry registry = new DbQueryExecutionRegistry();
        DbQueryExecutionRegistry.Execution execution = registry.begin("query-2", "user-1");
        assertTrue(registry.cancel("query-2", "user-1"));

        Statement statement = mock(Statement.class);
        registry.attach("query-2", execution, statement);

        verify(statement).cancel();
        registry.finish("query-2", execution);
        assertFalse(registry.cancel("query-2", "user-1"));
    }
}
