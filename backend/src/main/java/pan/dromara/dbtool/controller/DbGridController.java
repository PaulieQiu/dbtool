package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.service.IDbGridService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 服务端网格查询 控制器(P2)
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/grid")
public class DbGridController {

    private final IDbGridService gridService;

    public DbGridController(IDbGridService gridService) {
        this.gridService = gridService;
    }

    /**
     * 服务端过滤/排序/分页查询
     */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/query")
    public R<Map<String, Object>> query(@RequestBody Map<String, Object> body) {
        Long connId = parseLong(body == null ? null : body.get("connId"));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        Integer pageNum = parseInt(body == null ? null : body.get("pageNum"), 1);
        Integer pageSize = parseInt(body == null ? null : body.get("pageSize"), 100);
        if (connId == null || StrUtil.isBlank(sql)) {
            return R.fail("connId 与 sql 不能为空");
        }
        if (pageNum == null || pageSize == null) {
            return R.fail("pageNum/pageSize 参数格式不正确");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filters = body == null || !(body.get("filters") instanceof List)
            ? null : (List<Map<String, Object>>) body.get("filters");
        @SuppressWarnings("unchecked")
        Map<String, Object> sort = body == null || !(body.get("sort") instanceof Map)
            ? null : (Map<String, Object>) body.get("sort");
        return R.ok(gridService.query(connId, sql, pageNum, pageSize, filters, sort));
    }

    /** 安全解析 Long: 缺失或格式非法返回 null(不抛 NumberFormatException) */
    private static Long parseLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 安全解析 int: 缺失返回默认值; 格式非法返回 null 由调用方拒绝 */
    private static Integer parseInt(Object v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
