package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.bo.DbStructureChangeBo;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DbStructureChangeConfirmationTest {

    @Test
    void bindsApprovalPayloadToOperationAndObjectNames() {
        DbStructureChangeBo dropColumn = change("DROP_COLUMN", "users", "email");
        String payload = DbStructureChangeConfirmation.canonicalPayload(dropColumn);

        assertTrue(payload.contains("\"operation\":\"DROP_COLUMN\""));
        assertTrue(payload.contains("\"table\":\"users\""));
        assertTrue(payload.contains("\"columnName\":\"email\""));

        dropColumn.setColumnName("password");
        assertNotEquals(payload, DbStructureChangeConfirmation.canonicalPayload(dropColumn));
    }

    @Test
    void rejectsUnsupportedOrIncompleteOperationPayloads() {
        assertThrows(ServiceException.class,
            () -> DbStructureChangeConfirmation.canonicalPayload(change("TRUNCATE_TABLE", "users", null)));
        assertThrows(ServiceException.class,
            () -> DbStructureChangeConfirmation.canonicalPayload(change("DROP_COLUMN", "users", null)));
    }

    private static DbStructureChangeBo change(String operation, String table, String column) {
        DbStructureChangeBo request = new DbStructureChangeBo();
        request.setConnId(9L);
        request.setOperation(operation);
        request.setSchema("app");
        request.setTable(table);
        request.setColumnName(column);
        return request;
    }
}
