package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbMetaService;
import pan.dromara.dbtool.core.DbAccessPolicy;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Redis 可视化管理 控制器
 * <p>键浏览(SCAN)/键值编辑/删除/TTL/INFO/CLI, 界面参考 RedisInsight、TinyRDM、RedisFront、ARDM</p>
 *
 * @author doubao
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/dbtool/redis")
public class RedisController {

    private final IDbMetaService metaService;

    /**
     * 键列表(SCAN 增量, 支持 * 通配符)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/keys")
    public R<Map<String, Object>> keys(@RequestParam Long connId,
                                       @RequestParam(required = false) String db,
                                       @RequestParam(required = false) String pattern,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "200") int count) {
        return R.ok(metaService.redisKeys(connId, db, pattern, cursor, count));
    }

    /**
     * 键信息(TYPE/TTL/大小)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/keyInfo")
    public R<Map<String, Object>> keyInfo(@RequestParam Long connId,
                                          @RequestParam(required = false) String db,
                                          @RequestParam String key) {
        return R.ok(metaService.redisKeyInfo(connId, db, key));
    }

    /**
     * 键值读取(按类型)
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/value")
    public R<Map<String, Object>> value(@RequestParam Long connId,
                                        @RequestParam(required = false) String db,
                                        @RequestParam String key,
                                        @RequestParam(required = false) String type) {
        return R.ok(metaService.redisValue(connId, db, key, type));
    }

    /**
     * 键写入(新增/更新)
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/set")
    public R<Void> set(@RequestBody Map<String, Object> body) {
        Long connId = reqConnId(body);
        String db = body.get("db") == null ? null : String.valueOf(body.get("db"));
        String key = (String) body.get("key");
        String type = (String) body.get("type");
        String value = (String) body.get("value");
        Long ttl = reqLongOrNull(body.get("ttl"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> fields = (List<Map<String, String>>) body.get("fields");
        metaService.redisSet(connId, db, key, type, value, ttl, fields);
        return R.ok();
    }

    /**
     * 键删除
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/del")
    public R<Void> del(@RequestBody Map<String, Object> body) {
        Long connId = reqConnId(body);
        String db = body.get("db") == null ? null : String.valueOf(body.get("db"));
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) body.get("keys");
        metaService.redisDel(connId, db, keys);
        return R.ok();
    }

    /**
     * TTL 更新
     */
    @SaCheckPermission("dbtool:meta:edit")
    @PostMapping("/expire")
    public R<Void> expire(@RequestBody Map<String, Object> body) {
        Long connId = reqConnId(body);
        String db = body.get("db") == null ? null : String.valueOf(body.get("db"));
        String key = (String) body.get("key");
        Long ttl = reqLong(body.get("ttl"));
        metaService.redisExpire(connId, db, key, ttl);
        return R.ok();
    }

    /**
     * INFO 信息
     */
    @SaCheckPermission("dbtool:meta:list")
    @GetMapping("/info")
    public R<Map<String, Object>> info(@RequestParam Long connId,
                                       @RequestParam(required = false) String db) {
        return R.ok(metaService.redisInfo(connId, db));
    }

    /**
     * CLI 命令执行
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/cli")
    public R<Object> cli(@RequestBody Map<String, Object> body) {
        Long connId = reqConnId(body);
        String db = body.get("db") == null ? null : String.valueOf(body.get("db"));
        String command = (String) body.get("command");
        if (!DbAccessPolicy.isReadOnlyRedisCommand(command) && !StpUtil.hasPermission("dbtool:meta:edit")) {
            throw new NotPermissionException("执行写入 Redis 命令需要 dbtool:meta:edit 权限");
        }
        return R.ok(metaService.redisCli(connId, db, command));
    }

    /** 安全解析请求体中的 connId: 缺失或格式非法抛中文 ServiceException(避免 NumberFormatException 500) */
    private static Long reqConnId(Map<String, ?> body) {
        Object v = body == null ? null : body.get("connId");
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new ServiceException("connId 不能为空");
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("connId 参数格式不正确: " + v);
        }
    }

    /** 安全解析可选 Long 参数: 缺失/空返回 null; 格式非法抛中文 ServiceException */
    private static Long reqLongOrNull(Object v) {
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("参数格式不正确: " + v);
        }
    }

    /** 安全解析必填 Long 参数: 缺失或格式非法抛中文 ServiceException */
    private static Long reqLong(Object v) {
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new ServiceException("参数不能为空");
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("参数格式不正确: " + v);
        }
    }
}
