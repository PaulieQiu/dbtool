package pan.dromara.dbtool.core;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Redis 连接最小复用池(替代每次命令 new Socket)
 * <p>按连接(connId)维护空闲 Socket 栈: 借用优先复用空闲连接(含 db 切换), 归还放回池;
 * 池容量上限(每连接 8 个)与空闲超时(30s)自动关闭淘汰, 连接关闭时整体清理。</p>
 *
 * @author doubao
 */
@Component
public class RedisConnectionPool {

    /** 每连接最多空闲 Socket 数 */
    private static final int MAX_PER_CONN = 8;
    /** 空闲超时(毫秒): 超过则关闭淘汰 */
    private static final long IDLE_MS = 30_000;

    /** 池条目: socket + 当前 SELECT 的 db + 最后使用时间 */
    public static class Entry {
        public Socket socket;
        public String db;
        public long lastUsed;

        public Entry() {
        }

        public Entry(Socket socket, String db) {
            this.socket = socket;
            this.db = db;
        }
    }

    private final ConcurrentHashMap<Long, ConcurrentLinkedDeque<Entry>> pools = new ConcurrentHashMap<>();

    /** 借用一个空闲连接; 无空闲(或全部失效)返回 null, 由调用方新建 */
    public Entry borrow(Long connId) {
        ConcurrentLinkedDeque<Entry> stack = pools.get(connId);
        if (stack == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        while (true) {
            Entry e = stack.pollLast();
            if (e == null) {
                return null;
            }
            if (e.socket == null || e.socket.isClosed() || now - e.lastUsed > IDLE_MS) {
                closeQuietly(e.socket);
                continue;
            }
            e.lastUsed = now;
            return e;
        }
    }

    /** 归还连接; 池满或连接失效则关闭 */
    public void release(Long connId, Entry e) {
        if (e == null || e.socket == null || e.socket.isClosed()) {
            return;
        }
        ConcurrentLinkedDeque<Entry> stack = pools.computeIfAbsent(connId, k -> new ConcurrentLinkedDeque<>());
        e.lastUsed = System.currentTimeMillis();
        if (stack.size() >= MAX_PER_CONN) {
            closeQuietly(e.socket);
            return;
        }
        stack.addLast(e);
    }

    /** 连接关闭时清理该连接全部空闲连接 */
    public void close(Long connId) {
        ConcurrentLinkedDeque<Entry> stack = pools.remove(connId);
        if (stack == null) {
            return;
        }
        for (Entry e : stack) {
            closeQuietly(e.socket);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // 关闭失败无需处理
        }
    }
}
