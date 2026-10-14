package pan.dromara.dbtool.backup;

import pan.dromara.dbtool.domain.DbConn;

import java.nio.file.Path;
import java.util.List;

/**
 * 数据库备份/恢复策略(按数据库类型路由)
 * <p>备份通过构建 dump 进程命令输出到目标文件; 恢复通过构建 restore 进程命令, 备份文件由调用方
 * 以 stdin 流式喂入。密码以明文传入(仅内存), 策略应优先放入环境变量(passwordEnvName),
 * 确需作为命令行参数(如达梦 USERID)时不得写入日志。</p>
 *
 * @author doubao
 */
public interface DbBackupStrategy {

    /** 策略显示名 */
    String name();

    /** 是否支持该数据库类型(忽略大小写) */
    boolean supports(String dbType);

    /** 备份文件扩展名(含点, 如 .sql) */
    String fileExt();

    /** 构建备份(dump)进程命令, 含可执行文件全路径; target 为备份目标文件 */
    List<String> buildDumpCommand(DbConn conn, String schema, Path target, String password);

    /** 构建恢复(restore)进程命令, 含可执行文件全路径; 备份文件由调用方通过 stdin 喂入 */
    List<String> buildRestoreCommand(DbConn conn, String schema, String password);

    /** 密码环境变量名(如 MYSQL_PWD/PGPASSWORD); 返回 null 表示无对应环境变量机制 */
    String passwordEnvName();
}
