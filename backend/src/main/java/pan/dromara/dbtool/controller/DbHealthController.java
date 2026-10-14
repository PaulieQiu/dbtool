package pan.dromara.dbtool.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pan.dromara.dbtool.core.DbConnectionManager;

import java.util.Map;

/**
 * dbtool 连接池健康检查端点
 * <p>报告各连接池的活跃/空闲/总连接数, 供监控或运维排查连接泄漏/拥塞。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/health")
public class DbHealthController {

    private final DbConnectionManager connectionManager;

    public DbHealthController(DbConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * 连接池健康状态(需 dbtool:conn:list 权限, 含 connId 明细)
     */
    @SaCheckPermission("dbtool:conn:list")
    @GetMapping
    public R<Map<String, Object>> pool() {
        return R.ok(connectionManager.poolStats());
    }
}
