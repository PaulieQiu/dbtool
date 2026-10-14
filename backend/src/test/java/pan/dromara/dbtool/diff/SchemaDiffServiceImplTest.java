package pan.dromara.dbtool.diff;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.domain.vo.DbColumnVo;
import pan.dromara.dbtool.domain.vo.DbIndexVo;
import pan.dromara.dbtool.domain.vo.DbTableVo;
import pan.dromara.dbtool.service.IDbMetaService;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SchemaDiffServiceImplTest {

    private final IDbMetaService metaService = mock(IDbMetaService.class);
    private final SchemaDiffServiceImpl service = new SchemaDiffServiceImpl(metaService);
    private DbConn source;
    private DbConn target;

    @BeforeEach
    void setUp() {
        source = new DbConn();
        source.setDbType("MYSQL");
        target = new DbConn();
        target.setDbType("MYSQL");
        when(metaService.getConn(1L)).thenReturn(source);
        when(metaService.getConn(2L)).thenReturn(target);
    }

    @Test
    void comparesColumnAddsAndDoesNotGenerateDestructiveDdl() {
        DbTableVo sourceTable = table("users");
        DbTableVo targetTable = table("users");
        DbColumnVo sourceColumn = column("email", "varchar(255)");
        DbColumnVo targetColumn = column("name", "varchar(64)");
        when(metaService.tables(1L, "source_db")).thenReturn(List.of(sourceTable));
        when(metaService.tables(2L, "target_db")).thenReturn(List.of(targetTable));
        when(metaService.columns(1L, "source_db", "users")).thenReturn(List.of(sourceColumn));
        when(metaService.columns(2L, "target_db", "users")).thenReturn(List.of(targetColumn));

        List<SchemaDiffService.SchemaDiffItem> items = service.diff(1L, 2L, "source_db", "target_db");

        assertEquals(List.of("ADD", "DROP"), items.stream().map(SchemaDiffService.SchemaDiffItem::getDiffType).toList());
        String script = service.generateMigrationScript(items, "source-to-target");
        assertTrue(script.contains("ADD COLUMN `email` varchar(255)"));
        assertFalse(script.contains("DROP COLUMN"));
    }

    @Test
    void rejectsDifferentDatabaseTypes() {
        target.setDbType("POSTGRESQL");

        assertThrows(ServiceException.class, () -> service.diff(1L, 2L, "source_db", "target_db"));
    }

    @Test
    void previewsOnlyAdditiveNonUniqueBtreeIndexes() {
        when(metaService.tables(1L, "source_db")).thenReturn(List.of(table("users")));
        when(metaService.tables(2L, "target_db")).thenReturn(List.of(table("users")));
        when(metaService.columns(1L, "source_db", "users")).thenReturn(List.of());
        when(metaService.columns(2L, "target_db", "users")).thenReturn(List.of());
        when(metaService.indexes(1L, "source_db", "users")).thenReturn(List.of(
            index("idx_email", "email,alias", 1),
            index("uk_code", "code", 0)
        ));
        when(metaService.indexes(2L, "target_db", "users")).thenReturn(List.of());

        List<SchemaDiffService.SchemaDiffItem> items = service.diff(1L, 2L, "source_db", "target_db");
        String script = service.generateMigrationScript(items, "source-to-target");

        assertEquals(2, items.size());
        assertTrue(script.contains("ADD INDEX `idx_email` USING BTREE (`email,alias`)"));
        assertFalse(script.contains("uk_code"));
    }

    private DbTableVo table(String name) {
        DbTableVo table = new DbTableVo();
        table.setTableName(name);
        table.setTableType("BASE TABLE");
        return table;
    }

    private DbColumnVo column(String name, String type) {
        DbColumnVo column = new DbColumnVo();
        column.setColumnName(name);
        column.setColumnType(type);
        column.setDataType(type);
        column.setNullable("YES");
        return column;
    }

    private DbIndexVo index(String name, String column, int nonUnique) {
        DbIndexVo index = new DbIndexVo();
        index.setIndexName(name);
        index.setColumnName(column);
        index.setSeqInIndex(1);
        index.setNonUnique(nonUnique);
        index.setIndexType("BTREE");
        return index;
    }
}
