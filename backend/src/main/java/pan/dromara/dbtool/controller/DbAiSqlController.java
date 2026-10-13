package pan.dromara.dbtool.controller;

import pan.dromara.dbtool.ai.AiSqlService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI-SQL 助手控制器(P2): 自然语言转 SQL / SQL 解释 / 优化 / 高危检测 / 测试数据生成
 * <p>所有接口只生成与分析 SQL，不执行；连接需在 dbtool.ai.allowConns 白名单内。</p>
 *
 * @author doubao
 */
@RestController
@RequestMapping("/dbtool/ai-sql")
public class DbAiSqlController {

    private final AiSqlService aiSqlService;

    public DbAiSqlController(AiSqlService aiSqlService) {
        this.aiSqlService = aiSqlService;
    }

    /** 自然语言转 SQL(基于库表列元数据) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/nl2sql")
    public R<String> nl2sql(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        String naturalLanguage = body == null ? null : String.valueOf(body.get("naturalLanguage"));
        return R.ok(aiSqlService.naturalLanguageToSql(connId, schema, naturalLanguage));
    }

    /** SQL 解释(类型/涉及表/风险/建议) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/explain")
    public R<String> explain(@RequestBody Map<String, Object> body) {
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        return R.ok(aiSqlService.explainSql(sql));
    }

    /** SQL 优化建议 */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/optimize")
    public R<String> optimize(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        return R.ok(aiSqlService.optimizeSql(connId, schema, sql));
    }

    /** 高危 SQL 检测(返回 LOW/MEDIUM/HIGH/CRITICAL) */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/detect-risk")
    public R<String> detectRisk(@RequestBody Map<String, Object> body) {
        String sql = body == null ? null : String.valueOf(body.get("sql"));
        return R.ok(aiSqlService.detectRisk(sql));
    }

    /** 生成测试数据 INSERT */
    @SaCheckPermission("dbtool:meta:list")
    @PostMapping("/test-data")
    public R<String> testData(@RequestBody Map<String, Object> body) {
        Long connId = body == null || body.get("connId") == null
            ? null : Long.valueOf(String.valueOf(body.get("connId")));
        String schema = body == null ? null : String.valueOf(body.get("schema"));
        String table = body == null ? null : String.valueOf(body.get("table"));
        Integer rowCount = body == null || body.get("rowCount") == null
            ? null : Integer.valueOf(String.valueOf(body.get("rowCount")));
        return R.ok(aiSqlService.generateTestData(connId, schema, table, rowCount));
    }
}
