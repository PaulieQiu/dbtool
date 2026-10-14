package pan.dromara.dbtool.backup;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pan.dromara.dbtool.domain.DbConn;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 备份策略: pg_dump 备份(plain 格式), psql 恢复(stdin 喂入)
 * <p>依赖 pg_dump/psql 客户端, 可配置 {@code dbtool.backup.pgHome} 指定 bin 目录。</p>
 *
 * @author doubao
 */
@Slf4j
@Component
public class PostgreSqlBackupStrategy implements DbBackupStrategy {

    @Value("${dbtool.backup.pgHome:}")
    private String pgHome;

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public boolean supports(String dbType) {
        return "POSTGRESQL".equalsIgnoreCase(dbType)
            || "POSTGRES".equalsIgnoreCase(dbType)
            || "PGSQL".equalsIgnoreCase(dbType);
    }

    @Override
    public String fileExt() {
        return ".sql";
    }

    @Override
    public List<String> buildDumpCommand(DbConn conn, String schema, Path target, String password) {
        List<String> c = new ArrayList<>();
        c.add(bin("pg_dump"));
        c.add("-h");
        c.add(conn.getHost());
        c.add("-p");
        c.add(defaultPort(conn));
        c.add("-U");
        c.add(conn.getUsername());
        c.add("--format=plain");
        c.add("--no-owner");
        c.add("--no-privileges");
        c.add("--file=" + target);
        c.add(schema);
        return c;
    }

    @Override
    public List<String> buildRestoreCommand(DbConn conn, String schema, String password) {
        List<String> c = new ArrayList<>();
        c.add(bin("psql"));
        c.add("-h");
        c.add(conn.getHost());
        c.add("-p");
        c.add(defaultPort(conn));
        c.add("-U");
        c.add(conn.getUsername());
        c.add("-d");
        c.add(schema);
        return c;
    }

    @Override
    public String passwordEnvName() {
        return "PGPASSWORD";
    }

    private String defaultPort(DbConn conn) {
        return conn.getPort() == null ? "5432" : String.valueOf(conn.getPort());
    }

    /** 解析可执行文件: 配置了 pgHome 则取其 bin 下, 否则依赖 PATH */
    private String bin(String binary) {
        if (StrUtil.isBlank(pgHome)) {
            return binary;
        }
        return pgHome.replace('\\', '/') + "/bin/" + binary;
    }
}
