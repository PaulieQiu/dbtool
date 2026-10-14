package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * ER 图连线(外键关系) VO
 *
 * @author doubao
 */
@Data
public class DbErEdgeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 外键名
     */
    private String fkName;

    /**
     * 源表(本表)
     */
    private String sourceTable;

    /**
     * 源列(本表列)
     */
    private String sourceColumn;

    /**
     * 目标表(被引用表)
     */
    private String targetTable;

    /**
     * 目标列(被引用列)
     */
    private String targetColumn;

    /**
     * 删除规则(NO ACTION/CASCADE/SET NULL/SET DEFAULT/RESTRICT)
     */
    private String deleteRule;

    /**
     * 更新规则
     */
    private String updateRule;
}
