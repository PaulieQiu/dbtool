package pan.dromara.dbtool.backup;

import org.junit.jupiter.api.Test;
import pan.dromara.dbtool.domain.DbConn;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备份策略(MySQL/PostgreSQL)单元测试。
 * 覆盖：supports / fileExt / 密码环境变量名 / 备份恢复命令构建。
 *
 * @author doubao
 */
class DbBackupStrategyTest {

    private DbConn conn(String type, String host, int port, String user) {
        DbConn c = new DbConn();
        c.setId(1L);
        c.setDbType(type);
        c.setHost(host);
        c.setPort(port);
        c.setUsername(user);
        c.setPassword("secret");
        return c;
    }

    @Test
    void mysqlSupportsMysqlAndMariaDb() {
        MySqlBackupStrategy s = new MySqlBackupStrategy();
        assertTrue(s.supports("MYSQL"));
        assertTrue(s.supports("mariadb"));
        assertFalse(s.supports("POSTGRESQL"));
        assertEquals(".sql", s.fileExt());
        assertEquals("MYSQL_PWD", s.passwordEnvName());
    }

    @Test
    void mysqlDumpCommandContainsCoreArgs() {
        MySqlBackupStrategy s = new MySqlBackupStrategy();
        List<String> cmd = s.buildDumpCommand(conn("MYSQL", "127.0.0.1", 3306, "root"),
            "testdb", Paths.get("D:/bak/test.sql"), "secret");
        // 第一个元素可能是 mysqldump 或探测到的绝对路径
        assertTrue(cmd.get(0).contains("mysqldump"));
        assertTrue(cmd.contains("--host=127.0.0.1"));
        assertTrue(cmd.contains("--port=3306"));
        assertTrue(cmd.contains("--user=root"));
        assertTrue(cmd.contains("--single-transaction"));
        assertTrue(cmd.contains("testdb"));
        // 密码不进入命令行参数(走环境变量)
        assertFalse(cmd.stream().anyMatch(a -> a.contains("secret")));
    }

    @Test
    void mysqlRestoreCommandUsesClient() {
        MySqlBackupStrategy s = new MySqlBackupStrategy();
        List<String> cmd = s.buildRestoreCommand(conn("MYSQL", "127.0.0.1", 3306, "root"),
            "testdb", "secret");
        assertTrue(cmd.get(0).contains("mysql"));
        assertTrue(cmd.contains("--host=127.0.0.1"));
        assertTrue(cmd.contains("testdb"));
        assertFalse(cmd.contains("--single-transaction"));
    }

    @Test
    void postgresSupportsPostgresTypes() {
        PostgreSqlBackupStrategy s = new PostgreSqlBackupStrategy();
        assertTrue(s.supports("POSTGRESQL"));
        assertTrue(s.supports("postgres"));
        assertTrue(s.supports("pgsql"));
        assertFalse(s.supports("MYSQL"));
        assertEquals(".sql", s.fileExt());
        assertEquals("PGPASSWORD", s.passwordEnvName());
    }

    @Test
    void postgresDumpCommandUsesPgDump() {
        PostgreSqlBackupStrategy s = new PostgreSqlBackupStrategy();
        List<String> cmd = s.buildDumpCommand(conn("POSTGRESQL", "127.0.0.1", 5432, "postgres"),
            "appdb", Paths.get("D:/bak/app.sql"), "secret");
        assertEquals("pg_dump", cmd.get(0));
        assertTrue(cmd.contains("-U"));
        assertTrue(cmd.contains("postgres"));
        assertTrue(cmd.contains("--format=plain"));
        assertTrue(cmd.contains("--no-owner"));
        assertTrue(cmd.contains("appdb"));
        assertFalse(cmd.stream().anyMatch(a -> a.contains("secret")));
    }

    @Test
    void postgresRestoreCommandUsesPsql() {
        PostgreSqlBackupStrategy s = new PostgreSqlBackupStrategy();
        List<String> cmd = s.buildRestoreCommand(conn("POSTGRESQL", "127.0.0.1", 5432, "postgres"),
            "appdb", "secret");
        assertEquals("psql", cmd.get(0));
        assertTrue(cmd.contains("-h"));
        assertTrue(cmd.contains("127.0.0.1"));
        assertTrue(cmd.contains("-d"));
        assertTrue(cmd.contains("appdb"));
    }
}
