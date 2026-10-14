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
 * MySQL/MariaDB 备份策略: mysqldump 备份, mysql 恢复(stdin 喂入)
 *
 * @author doubao
 */
@Slf4j
@Component
public class MySqlBackupStrategy implements DbBackupStrategy {

    @Value("${dbtool.backup.mysqlHome:}")
    private String mysqlHome;

    @Override
    public String name() {
        return "MySQL";
    }

    @Override
    public boolean supports(String dbType) {
        return "MYSQL".equalsIgnoreCase(dbType) || "MARIADB".equalsIgnoreCase(dbType);
    }

    @Override
    public String fileExt() {
        return ".sql";
    }

    @Override
    public List<String> buildDumpCommand(DbConn conn, String schema, Path target, String password) {
        List<String> c = new ArrayList<>();
        c.add(bin("mysqldump"));
        c.add("--host=" + conn.getHost());
        c.add("--port=" + defaultPort(conn));
        c.add("--user=" + conn.getUsername());
        c.add("--single-transaction");
        c.add("--routines");
        c.add("--triggers");
        c.add("--default-character-set=utf8mb4");
        c.add("--result-file=" + target);
        c.add(schema);
        return c;
    }

    @Override
    public List<String> buildRestoreCommand(DbConn conn, String schema, String password) {
        List<String> c = new ArrayList<>();
        c.add(bin("mysql"));
        c.add("--host=" + conn.getHost());
        c.add("--port=" + defaultPort(conn));
        c.add("--user=" + conn.getUsername());
        c.add("--default-character-set=utf8mb4");
        c.add(schema);
        return c;
    }

    @Override
    public String passwordEnvName() {
        return "MYSQL_PWD";
    }

    private String defaultPort(DbConn conn) {
        return conn.getPort() == null ? "3306" : String.valueOf(conn.getPort());
    }

    /** 解析可执行文件: 配置了 mysqlHome 则取其 bin 下; 其次探测常见 Windows 安装路径; 否则依赖 PATH */
    private String bin(String binary) {
        if (StrUtil.isNotBlank(mysqlHome)) {
            return mysqlHome.replace('\\', '/') + "/bin/" + binary;
        }
        for (String candidate : new String[]{
            "C:/Program Files/MySQL/MySQL Server 8.0/bin/" + binary + ".exe",
            "C:/Program Files/MySQL/MySQL Server 5.7/bin/" + binary + ".exe",
            "C:/Program Files/MySQL/MySQL Server 5.6/bin/" + binary + ".exe"}) {
            if (java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(candidate))) {
                return candidate;
            }
        }
        return binary;
    }
}
