package pan.dromara.dbtool.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 元数据结果短时缓存(库列表/表列表)
 * <p>缓存键 = 连接 + 类型 + 范围(schema), 短 TTL(默认 30s)防陈旧;
 * DDL 变更后调用 {@link #evict(Long)} 按连接失效, 避免 UI 长期显示旧结构。</p>
 *
 * @author doubao
 */
@Component
public class DbMetaCache {

    /** 缓存有效期(秒), 兼顾新鲜度与命中率 */
    private static final long TTL_SECONDS = 30;

    private record Key(Long connId, String kind, String scope) {
    }

    private final Cache<Key, Object> cache = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(TTL_SECONDS, TimeUnit.SECONDS)
        .build();

    /**
     * 命中返回缓存; 未命中执行 loader 并缓存(loader 异常直接传播, 不缓存失败)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Long connId, String kind, String scope, Supplier<T> loader) {
        return (T) cache.get(new Key(connId, kind, scope), k -> loader.get());
    }

    /** 按连接失效该连接下全部元数据缓存(DDL 后调用) */
    public void evict(Long connId) {
        if (connId == null) {
            return;
        }
        cache.asMap().keySet().removeIf(k -> connId.equals(k.connId()));
    }

    /** 全量清空(如应用重置) */
    public void clearAll() {
        cache.invalidateAll();
    }
}
