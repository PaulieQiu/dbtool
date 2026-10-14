package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 查询执行计划结构化 VO(供前端渲染树形执行计划图)
 *
 * @author doubao
 */
@Data
public class DbExplainPlanVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 方言类型(MYSQL/POSTGRESQL/...)
     */
    private String dialect;

    /**
     * 原始 EXPLAIN 文本(回退/补充)
     */
    private String rawText;

    /**
     * 执行计划树形节点列表
     */
    private List<PlanNode> tree;

    /**
     * 执行计划节点
     */
    @Data
    public static class PlanNode implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 节点ID
         */
        private Integer id;

        /**
         * 操作类型(SELECT/TABLE SCAN/INDEX SCAN/JOIN 等)
         */
        private String operation;

        /**
         * 涉及的表名
         */
        private String table;

        /**
         * 查询代价(MySQL cost / PostgreSQL total_cost)
         */
        private Double cost;

        /**
         * 预估行数
         */
        private Long rows;

        /**
         * 访问类型(ALL/index/range/ref/const 等)
         */
        private String accessType;

        /**
         * 可能使用的索引
         */
        private List<String> possibleKeys;

        /**
         * 实际使用的索引
         */
        private String key;

        /**
         * 索引长度
         */
        private Integer keyLen;

        /**
         * 使用的列(ref 列)
         */
        private String ref;

        /**
         * 额外信息(Using where/Using filesort/Using temporary 等)
         */
        private String extra;

        /**
         * 告警信息(全表扫描/缺少索引等)
         */
        private String warning;

        /**
         * 子节点
         */
        private List<PlanNode> children;

        /**
         * 原始属性(方言特有字段透传)
         */
        private Map<String, Object> raw;
    }
}
