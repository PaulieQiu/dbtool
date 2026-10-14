package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.bo.DbGridChangeBo;
import pan.dromara.dbtool.domain.vo.DbColumnVo;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class DbGridChangePlannerTest {

    private static final DbConn MYSQL_CONNECTION = connection("MYSQL");
    private static final List<DbColumnVo> COLUMNS = List.of(
        column("id", true),
        column("name", false),
        column("note", false)
    );

    @Test
    void acceptsSimpleSingleTableQueriesAndAliases() {
        DbGridChangePlanner.validateRequest(request("SELECT * FROM `app`.`users` WHERE id > 10"), MYSQL_CONNECTION);
        DbGridChangePlanner.validateRequest(request("SELECT * FROM users AS u ORDER BY id LIMIT 50"), MYSQL_CONNECTION);
    }

    @Test
    void rejectsJoinMultiStatementAndMismatchedTableQueries() {
        assertThrows(ServiceException.class, () -> DbGridChangePlanner.validateRequest(
            request("SELECT * FROM users u JOIN roles r ON r.id = u.role_id"), MYSQL_CONNECTION));
        assertThrows(ServiceException.class, () -> DbGridChangePlanner.validateRequest(
            request("SELECT * FROM users; DELETE FROM users"), MYSQL_CONNECTION));
        DbGridChangeBo mismatched = request("SELECT * FROM accounts");
        assertThrows(ServiceException.class, () -> DbGridChangePlanner.validateRequest(mismatched, MYSQL_CONNECTION));
    }

    @Test
    void createsPreparedUpdatePlanAndUsesNullSafeOriginalRowPredicate() {
        DbGridChangeBo.RowChange change = new DbGridChangeBo.RowChange();
        change.setOperation("UPDATE");
        change.setValues(Map.of("name", "O'Reilly; --"));
        Map<String, Object> originalValues = new LinkedHashMap<>();
        originalValues.put("id", 7);
        originalValues.put("name", "before");
        change.setOriginalValues(originalValues);
        change.getOriginalValues().put("note", null);
        DbGridChangeBo request = request("SELECT * FROM users WHERE id = 7");
        request.setChanges(List.of(change));

        DbGridChangePlanner.PlannedGridChange plan = DbGridChangePlanner.buildPlan(request, MYSQL_CONNECTION, COLUMNS).get(0);

        assertEquals("UPDATE `app`.`users` SET `name` = ? WHERE `id` = ? AND `name` = ? AND `note` IS NULL", plan.sql());
        assertEquals(List.of("O'Reilly; --", 7, "before"), plan.parameters());
        assertFalse(plan.sql().contains("O'Reilly"));
    }

    @Test
    void rejectsTablesWithoutPrimaryKeyAndUnknownColumns() {
        DbGridChangeBo.RowChange insert = new DbGridChangeBo.RowChange();
        insert.setOperation("INSERT");
        insert.setValues(Map.of("invalid_column", "value"));
        DbGridChangeBo request = request("SELECT * FROM users");
        request.setChanges(List.of(insert));

        assertThrows(ServiceException.class, () -> DbGridChangePlanner.buildPlan(
            request, MYSQL_CONNECTION, List.of(column("name", false))));
        assertThrows(ServiceException.class, () -> DbGridChangePlanner.buildPlan(request, MYSQL_CONNECTION, COLUMNS));
    }

    @Test
    void keepsNullInsertParametersAndBindsApprovalDigestToValues() {
        DbGridChangeBo.RowChange insert = new DbGridChangeBo.RowChange();
        insert.setOperation("INSERT");
        insert.setValues(new java.util.LinkedHashMap<>(Map.of("name", "example")));
        insert.getValues().put("note", null);
        DbGridChangeBo request = request("SELECT * FROM users");
        request.setChanges(List.of(insert));

        List<DbGridChangePlanner.PlannedGridChange> plan = DbGridChangePlanner.buildPlan(request, MYSQL_CONNECTION, COLUMNS);
        List<Object> expectedParameters = new ArrayList<>();
        expectedParameters.add("example");
        expectedParameters.add(null);
        assertEquals(expectedParameters, plan.get(0).parameters());
        assertEquals(64, DbGridChangePlanner.digest(plan).length());

        insert.getValues().put("name", "changed");
        List<DbGridChangePlanner.PlannedGridChange> changedPlan =
            DbGridChangePlanner.buildPlan(request, MYSQL_CONNECTION, COLUMNS);
        assertNotEquals(DbGridChangePlanner.digest(plan), DbGridChangePlanner.digest(changedPlan));
    }

    @Test
    void locatesCompositePrimaryKeyRowsUsingAllOriginalValues() {
        DbGridChangeBo.RowChange delete = new DbGridChangeBo.RowChange();
        delete.setOperation("DELETE");
        Map<String, Object> originalValues = new LinkedHashMap<>();
        originalValues.put("tenant_id", 4);
        originalValues.put("record_id", 12);
        originalValues.put("note", null);
        delete.setOriginalValues(originalValues);
        DbGridChangeBo request = request("SELECT * FROM users");
        request.setChanges(List.of(delete));

        DbColumnVo tenant = column("tenant_id", true);
        DbColumnVo record = column("record_id", true);
        DbColumnVo note = column("note", false);
        DbGridChangePlanner.PlannedGridChange plan =
            DbGridChangePlanner.buildPlan(request, MYSQL_CONNECTION, List.of(tenant, record, note)).get(0);

        assertEquals("DELETE FROM `app`.`users` WHERE `tenant_id` = ? AND `record_id` = ? AND `note` IS NULL", plan.sql());
        assertEquals(List.of(4, 12), plan.parameters());
    }

    private static DbConn connection(String type) {
        DbConn conn = new DbConn();
        conn.setId(1L);
        conn.setDbType(type);
        conn.setDatabaseName("app");
        return conn;
    }

    private static DbGridChangeBo request(String sql) {
        DbGridChangeBo request = new DbGridChangeBo();
        request.setConnId(1L);
        request.setSchema("app");
        request.setTable("users");
        request.setSourceSql(sql);
        request.setChanges(List.of(new DbGridChangeBo.RowChange()));
        return request;
    }

    private static DbColumnVo column(String name, boolean primary) {
        DbColumnVo column = new DbColumnVo();
        column.setColumnName(name);
        column.setColumnKey(primary ? "PRI" : null);
        column.setPrimary(primary);
        return column;
    }
}
