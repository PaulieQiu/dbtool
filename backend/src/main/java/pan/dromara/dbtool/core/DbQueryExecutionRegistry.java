package pan.dromara.dbtool.core;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks active JDBC statements so the owning user can request cancellation.
 * <p>条目带创建/最后活动时间, 超时由 {@link #sweepExpired()} 定时清理, 同时 {@link #begin} 惰性顺带清理,
 * 避免调用方遗漏 finish 导致内存残留。</p>
 */
@Component
public class DbQueryExecutionRegistry {

    /** 执行条目最大空闲/存活时长(毫秒), 超时视为残留可清理 */
    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();

    public Execution begin(String executionId, String userId) {
        if (executionId == null || executionId.isBlank() || executionId.length() > 128
            || userId == null || userId.isBlank()) {
            throw new ServiceException("查询任务标识或用户无效");
        }
        // 惰性顺带清理过期条目, 不依赖 @EnableScheduling 是否开启
        sweepExpired();
        Execution execution = new Execution(executionId, userId);
        if (executions.putIfAbsent(executionId, execution) != null) {
            throw new ServiceException("查询任务标识已在使用");
        }
        return execution;
    }

    public boolean cancel(String executionId, String userId) {
        if (executionId == null || executionId.isBlank() || userId == null || userId.isBlank()) {
            return false;
        }
        Execution execution = executions.get(executionId);
        if (execution == null || !execution.userId.equals(userId)) {
            return false;
        }
        execution.cancelled.set(true);
        Statement statement = execution.statement.get();
        if (statement != null) {
            try {
                statement.cancel();
            } catch (Exception e) {
                throw new ServiceException("取消查询失败: " + e.getMessage());
            }
        }
        return true;
    }

    public void attach(String executionId, Execution execution, Statement statement) {
        execution.touch();
        execution.statement.set(statement);
        if (execution.cancelled.get()) {
            try {
                statement.cancel();
            } catch (Exception e) {
                throw new ServiceException("取消查询失败: " + e.getMessage());
            }
        }
    }

    public void finish(String executionId, Execution execution) {
        execution.statement.set(null);
        executions.remove(executionId, execution);
    }

    /**
     * 清理空闲/存活超过 {@link #IDLE_TIMEOUT_MS} 的残留执行条目。
     * 通过 @Scheduled 定时触发, 并由 {@link #begin} 惰性兜底调用。
     */
    @Scheduled(fixedDelay = 300000)
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Execution e : executions.values()) {
            if (now - e.lastActiveAt > IDLE_TIMEOUT_MS) {
                // remove(key, value) 并发安全, 仅在仍为同一实例时移除
                if (executions.remove(e.executionId, e)) {
                    e.statement.set(null);
                }
            }
        }
    }

    public static final class Execution {
        private final String executionId;
        private final String userId;
        private final long createdAt = System.currentTimeMillis();
        private volatile long lastActiveAt = createdAt;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Statement> statement = new AtomicReference<>();

        private Execution(String executionId, String userId) {
            this.executionId = executionId;
            this.userId = userId;
        }

        void touch() {
            this.lastActiveAt = System.currentTimeMillis();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
