package pan.dromara.dbtool.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DbMetaCache（元数据短时缓存）单元测试。
 * 覆盖：命中复用 loader、不同 key 各自加载、按连接失效、loader 异常传播且不缓存。
 *
 * @author doubao
 */
class DbMetaCacheTest {

    private final DbMetaCache cache = new DbMetaCache();

    @Test
    void cacheHitsReturnSameResultWithoutReload() {
        AtomicInteger count = new AtomicInteger();
        Object first = cache.get(1L, "tables", "schema", () -> {
            count.incrementAndGet();
            return "A";
        });
        Object second = cache.get(1L, "tables", "schema", () -> {
            count.incrementAndGet();
            return "B";
        });
        assertEquals("A", first);
        assertEquals("A", second);
        assertEquals(1, count.get());
    }

    @Test
    void differentKeysLoadIndependently() {
        AtomicInteger count = new AtomicInteger();
        cache.get(1L, "tables", "a", () -> {
            count.incrementAndGet();
            return "x";
        });
        cache.get(1L, "tables", "b", () -> {
            count.incrementAndGet();
            return "y";
        });
        cache.get(2L, "tables", "a", () -> {
            count.incrementAndGet();
            return "z";
        });
        assertEquals(3, count.get());
    }

    @Test
    void evictInvalidatesOnlyThatConnection() {
        AtomicInteger count = new AtomicInteger();
        cache.get(1L, "tables", "s", () -> {
            count.incrementAndGet();
            return "a";
        });
        cache.get(2L, "tables", "s", () -> {
            count.incrementAndGet();
            return "b";
        });
        cache.evict(1L);
        // conn1 失效后重新加载, conn2 仍命中
        assertEquals("a", cache.get(1L, "tables", "s", () -> {
            count.incrementAndGet();
            return "a";
        }));
        assertEquals("b", cache.get(2L, "tables", "s", () -> {
            count.incrementAndGet();
            return "b";
        }));
        assertEquals(3, count.get());
    }

    @Test
    void loaderExceptionPropagatesAndIsNotCached() {
        AtomicInteger count = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cache.get(1L, "tables", "s", () -> {
            count.incrementAndGet();
            throw new IllegalStateException("load fail");
        }));
        // 异常不缓存, 再次调用会重试 loader
        assertThrows(IllegalStateException.class, () -> cache.get(1L, "tables", "s", () -> {
            count.incrementAndGet();
            throw new IllegalStateException("load fail");
        }));
        assertEquals(2, count.get());
    }

    @Test
    void clearAllInvalidatesEverything() {
        AtomicInteger count = new AtomicInteger();
        cache.get(1L, "tables", "s", () -> {
            count.incrementAndGet();
            return "a";
        });
        cache.clearAll();
        cache.get(1L, "tables", "s", () -> {
            count.incrementAndGet();
            return "a";
        });
        assertEquals(2, count.get());
    }
}
