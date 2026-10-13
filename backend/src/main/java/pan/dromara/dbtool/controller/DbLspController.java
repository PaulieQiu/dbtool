package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.lsp.SqlLspService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SQL LSP 代理控制器(P3): 会话生命周期 + completion/hover/diagnostics
 * <p>连接需在 dbtool.ai.allowConns 白名单内。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/lsp")
public class DbLspController {

    private final SqlLspService sqlLspService;

    public DbLspController(SqlLspService sqlLspService) {
        this.sqlLspService = sqlLspService;
    }

    /** 建立 LSP 会话(加载库表列元数据) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/start")
    public R<String> start(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        return R.ok(sqlLspService.startSession(connId, schema));
    }

    /** 销毁 LSP 会话 */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/stop")
    public R<Void> stop(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.get("sessionId"));
        sqlLspService.stopSession(sessionId);
        return R.ok();
    }

    /** 发送 LSP 请求(completion/hover/diagnostics) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/request")
    public R<Object> request(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.get("sessionId"));
        String method = body == null ? null : String.valueOf(body.get("method"));
        Object params = body == null ? null : body.get("params");
        return R.ok(sqlLspService.sendRequest(sessionId, method, params));
    }

    /** 刷新会话元数据 */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/refresh")
    public R<Void> refresh(@RequestBody Map<String, Object> body) {
        String sessionId = body == null ? null : String.valueOf(body.get("sessionId"));
        sqlLspService.refreshMetadata(sessionId);
        return R.ok();
    }
}
