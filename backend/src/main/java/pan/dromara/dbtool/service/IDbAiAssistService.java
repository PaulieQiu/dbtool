package pan.dromara.dbtool.service;

import java.util.List;
import java.util.Map;

/**
 * AI SQL 助手(P5): 基于连接元数据离线生成/解释 SQL, 只读白名单 + 审计
 *
 * @author doubao
 */
public interface IDbAiAssistService {

    /**
     * 生成 SQL: 输入自然语言需求, 基于连接元数据(库/表/列)规则生成 SQL
     * <p>只返回 SQL 文本, 不执行; 写类需求生成模板并标注需人工确认。</p>
     */
    Map<String, Object> assist(Long connId, String schema, String request, Long userId);

    /**
     * 解释 SQL: 拆语句、识别类型、提取涉及表、风险分级
     */
    Map<String, Object> explain(Long connId, String sql, Long userId);

    /**
     * 元数据上下文(供前端提示/调试): 库列表、表列表(含列)
     */
    Map<String, Object> context(Long connId, String schema, Long userId);

    /**
     * 审计日志查询: 分页查询 AI/MCP 调用审计记录
     *
     * @param userId   当前用户ID(null=全部, 管理员可查所有)
     * @param action   动作过滤(AI_ASSIST/AI_EXPLAIN/MCP_CALL, null=全部)
     * @param pageNum  页码(从 1 开始)
     * @param pageSize 每页条数
     */
    Map<String, Object> auditList(Long userId, String action, int pageNum, int pageSize);

    /**
     * AI SQL 优化建议: 基于 EXPLAIN 结果和 SQL 文本给出索引/改写建议
     */
    Map<String, Object> optimize(Long connId, String sql, Long userId);
}
