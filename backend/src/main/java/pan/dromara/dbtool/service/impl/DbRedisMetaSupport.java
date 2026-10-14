package pan.dromara.dbtool.service.impl;

import pan.dromara.dbtool.core.DbAccessPolicy;
import pan.dromara.dbtool.core.DbConnectionManager;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.mapper.DbConnMapper;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Redis 元数据/可视化管理 支持类
 * <p>从 {@link DbMetaServiceImpl} 拆分, 承载 Redis 键浏览、值读写、TTL、INFO、CLI 等逻辑,
 * 主类仅保留 public 委托方法, 职责单一化。</p>
 *
 * @author doubao
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbRedisMetaSupport {

    /** Redis 逻辑库数量(0-15) */
    public static final int REDIS_DB_COUNT = 16;

    private static final Pattern DANGEROUS_REDIS_CMD = Pattern.compile(
        "^(flushall|flushdb|shutdown|debug|monitor|config\\s+set|slaveof|replicaof|keys)\\b", Pattern.CASE_INSENSITIVE);

    private final DbConnMapper dbConnMapper;
    private final DbConnectionManager connectionManager;

    /** 判断是否为 Redis 连接 */
    public static boolean isRedis(DbConn conn) {
        return conn != null && "REDIS".equalsIgnoreCase(conn.getDbType());
    }

    public static String redisError() {
        return "Redis 为 NoSQL 数据库, 不支持该操作(可查看库/键及类型/TTL)";
    }

    private DbConn getConn(Long connId) {
        DbConn conn = dbConnMapper.selectById(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接配置不存在: " + connId);
        }
        return conn;
    }

    private void ensureRedis(DbConn conn) {
        if (!isRedis(conn)) {
            throw new ServiceException("该连接不是 Redis 类型");
        }
    }

    private String redisOne(DbConn conn, String db, String... args) {
        List<String> repl = connectionManager.redisCommand(conn, db, args);
        return repl.isEmpty() ? "" : repl.get(0);
    }

    private String redisClean(String v) {
        if (v == null) {
            return "";
        }
        if (v.startsWith("+") || v.startsWith(":") || v.startsWith("-")) {
            return v.substring(1);
        }
        return v;
    }

    /**
     * Redis 键列表(SCAN 增量迭代, 支持 * 通配符)
     */
    public Map<String, Object> redisKeys(Long connId, String db, String pattern, String cursor, int count) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String cur = StrUtil.blankToDefault(cursor, "0");
        int cnt = count <= 0 ? 200 : Math.min(count, 1000);
        List<String> repl = connectionManager.redisCommand(conn, db, "SCAN", cur, "MATCH", StrUtil.blankToDefault(pattern, "*"), "COUNT", String.valueOf(cnt));
        Map<String, Object> result = new LinkedHashMap<>();
        String next = repl.isEmpty() ? "0" : redisClean(repl.get(0));
        List<String> keys = repl.size() > 1 ? new ArrayList<>(repl.subList(1, repl.size())) : List.of();
        result.put("cursor", next);
        result.put("keys", keys);
        return result;
    }

    /**
     * Redis 键信息(TYPE/TTL/内存占用/元素数量)
     */
    public Map<String, Object> redisKeyInfo(Long connId, String db, String key) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("key", key);
        info.put("type", redisClean(redisOne(conn, db, "TYPE", key)));
        String ttl = redisClean(redisOne(conn, db, "TTL", key));
        info.put("ttl", ttl);
        String size = redisClean(redisOne(conn, db, "MEMORY", "USAGE", key));
        if (size.startsWith("ERR") || size.isBlank()) {
            size = "";
        }
        info.put("size", size);
        return info;
    }

    /**
     * Redis 键值读取(按类型结构化返回)
     */
    public Map<String, Object> redisValue(Long connId, String db, String key, String type) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String t = StrUtil.blankToDefault(type, redisClean(redisOne(conn, db, "TYPE", key)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("type", t);
        switch (t) {
            case "string" -> {
                byte[] bytes = connectionManager.redisBytes(conn, db, key);
                if (bytes == null) {
                    result.put("value", null);
                    break;
                }
                String utf8 = new String(bytes, StandardCharsets.UTF_8);
                if (isUtf8Text(bytes, utf8)) {
                    result.put("value", utf8);
                } else {
                    // 二进制内容: 返回 Hex 供前端查看/编辑
                    result.put("binary", true);
                    result.put("hex", toHex(bytes));
                }
            }
            case "hash" -> {
                List<String> fv = connectionManager.redisCommand(conn, db, "HGETALL", key);
                List<Map<String, String>> fields = new ArrayList<>();
                for (int i = 0; i + 1 < fv.size(); i += 2) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("field", fv.get(i));
                    m.put("value", fv.get(i + 1));
                    fields.add(m);
                }
                result.put("fields", fields);
            }
            case "list" -> {
                List<String> items = connectionManager.redisCommand(conn, db, "LRANGE", key, "0", "-1");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", i);
                    m.put("value", items.get(i));
                    rows.add(m);
                }
                result.put("items", rows);
            }
            case "set" -> result.put("members", connectionManager.redisCommand(conn, db, "SMEMBERS", key));
            case "zset" -> {
                List<String> ms = connectionManager.redisCommand(conn, db, "ZRANGE", key, "0", "-1", "WITHSCORES");
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i + 1 < ms.size(); i += 2) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("member", ms.get(i));
                    m.put("score", ms.get(i + 1));
                    rows.add(m);
                }
                result.put("items", rows);
            }
            case "stream" -> result.put("note", "Stream 类型暂不支持在线编辑, 请使用 CLI");
            default -> {
                String v = redisOne(conn, db, "GET", key);
                result.put("value", v);
            }
        }
        return result;
    }

    /**
     * Redis 键写入(新增/更新, 按类型; ttl 为 null 不动, >=0 设置, <0 持久化)
     */
    public void redisSet(Long connId, String db, String key, String type, String value, Long ttl, List<Map<String, String>> fields) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        ensureRedis(conn);
        String t = StrUtil.blankToDefault(type, "string");
        switch (t) {
            case "string" -> {
                if (value == null) {
                    throw new ServiceException("值不能为空");
                }
                if (value.startsWith("__HEX__")) {
                    // 二进制内容: Hex 解码后原样写回
                    connectionManager.redisSetBytes(conn, db, key, fromHex(value.substring(7)));
                } else {
                    connectionManager.redisCommand(conn, db, "SET", key, value);
                }
            }
            case "hash" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    for (Map<String, String> f : fields) {
                        connectionManager.redisCommand(conn, db, "HSET", key,
                            StrUtil.blankToDefault(f.get("field"), ""),
                            StrUtil.blankToDefault(f.get("value"), ""));
                    }
                }
            }
            case "list" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    List<String> args = new ArrayList<>();
                    args.add("RPUSH");
                    args.add(key);
                    for (Map<String, String> f : fields) {
                        args.add(StrUtil.blankToDefault(f.get("value"), ""));
                    }
                    connectionManager.redisCommand(conn, db, args.toArray(new String[0]));
                }
            }
            case "set" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    List<String> args = new ArrayList<>();
                    args.add("SADD");
                    args.add(key);
                    for (Map<String, String> f : fields) {
                        args.add(StrUtil.blankToDefault(f.get("value"), ""));
                    }
                    connectionManager.redisCommand(conn, db, args.toArray(new String[0]));
                }
            }
            case "zset" -> {
                connectionManager.redisCommand(conn, db, "DEL", key);
                if (fields != null && !fields.isEmpty()) {
                    for (Map<String, String> f : fields) {
                        connectionManager.redisCommand(conn, db, "ZADD", key,
                            StrUtil.blankToDefault(f.get("score"), "0"),
                            StrUtil.blankToDefault(f.get("value"), ""));
                    }
                }
            }
            default -> throw new ServiceException("不支持的键类型: " + t);
        }
        if (ttl != null && ttl >= 0) {
            connectionManager.redisCommand(conn, db, "EXPIRE", key, String.valueOf(ttl));
        } else if (ttl != null && ttl < 0) {
            connectionManager.redisCommand(conn, db, "PERSIST", key);
        }
    }

    /**
     * Redis 键删除
     */
    public void redisDel(Long connId, String db, List<String> keys) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        ensureRedis(conn);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>();
        args.add("DEL");
        args.addAll(keys);
        connectionManager.redisCommand(conn, db, args.toArray(new String[0]));
    }

    /**
     * Redis TTL 更新(>=0 设置过期秒, <0 持久化)
     */
    public void redisExpire(Long connId, String db, String key, Long ttl) {
        DbConn conn = getConn(connId);
        DbAccessPolicy.requireWritable(conn);
        ensureRedis(conn);
        if (ttl != null && ttl >= 0) {
            connectionManager.redisCommand(conn, db, "EXPIRE", key, String.valueOf(ttl));
        } else {
            connectionManager.redisCommand(conn, db, "PERSIST", key);
        }
    }

    /**
     * Redis INFO 信息(解析为 section -> k/v)
     */
    public Map<String, Object> redisInfo(Long connId, String db) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String raw = redisOne(conn, db, "INFO");
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> current = new LinkedHashMap<>();
        for (String line : raw.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                current = new LinkedHashMap<>();
                result.put(line.substring(1).trim(), current);
                continue;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                current.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
        return result;
    }

    /**
     * Redis CLI 通用命令执行(返回平铺结果)
     */
    public Object redisCli(Long connId, String db, String command) {
        DbConn conn = getConn(connId);
        ensureRedis(conn);
        String cmd = StrUtil.trim(command);
        if (cmd.isBlank()) {
            throw new ServiceException("命令不能为空");
        }
        String[] parts = cmd.split("\\s+");
        String first = parts[0].toLowerCase();
        if (DbAccessPolicy.isReadOnly(conn) && !DbAccessPolicy.isReadOnlyRedisCommand(cmd)) {
            DbAccessPolicy.requireWritable(conn);
        }
        // KEYS 限量执行(用 SCAN 替代, 最多 1000 条), 其余危险命令拦截
        if ("keys".equals(first) || first.startsWith("keys*")) {
            String pattern = parts.length > 1 ? parts[1] : (first.equals("keys") ? "*" : first.substring(4));
            List<String> out = new ArrayList<>();
            String cur = "0";
            int guard = 0;
            while (guard++ < 200) {
                List<String> batch = connectionManager.redisCommand(conn, db, "SCAN", cur, "MATCH", pattern, "COUNT", "500");
                if (batch.isEmpty()) {
                    break;
                }
                String nextCur = redisClean(batch.get(0));
                for (int i = 1; i < batch.size(); i++) {
                    out.add(batch.get(i));
                }
                cur = nextCur;
                if ("0".equals(cur) || out.size() >= 1000) {
                    break;
                }
            }
            out.add(0, "(KEYS 已限量执行, 共 " + out.size() + " 条; 大库请优先使用 SCAN 避免阻塞)");
            return out;
        }
        if (DANGEROUS_REDIS_CMD.matcher(cmd).find()) {
            throw new ServiceException("危险命令已拦截: " + first + " (FLUSHALL/FLUSHDB/SHUTDOWN/DEBUG/MONITOR/CONFIG SET 等禁止在工作台执行)");
        }
        List<String> repl = connectionManager.redisCommand(conn, db, parts);
        List<String> cleaned = new ArrayList<>(repl.size());
        for (String v : repl) {
            cleaned.add(redisClean(v));
        }
        return cleaned;
    }

    // ==================== Redis 二进制值处理 ====================

    private boolean isUtf8Text(byte[] bytes, String decoded) {
        if (decoded.indexOf('\uFFFD') >= 0) {
            return false;
        }
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c == 0) {
                return false;
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private byte[] fromHex(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
