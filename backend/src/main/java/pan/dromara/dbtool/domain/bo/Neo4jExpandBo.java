package pan.dromara.dbtool.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * Neo4j 邻居展开请求体（按本次结果节点定位一层邻居）
 *
 * @author doubao
 */
@Data
public class Neo4jExpandBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "连接不能为空")
    private Long connId;

    private String database;

    /**
     * 待展开节点的驱动 node id()（同一执行会话内有效）
     */
    @NotNull(message = "节点标识不能为空")
    private Long nodeId;

    /**
     * 邻居关系数上限（默认 50）
     */
    private Integer relLimit;

    /**
     * 邻居节点数上限（默认 50）
     */
    private Integer nodeLimit;

    /**
     * 客户端执行 ID（同一次结果会话）
     */
    private String executionId;

    public int effectiveRelLimit() {
        return relLimit == null || relLimit <= 0 ? 50 : Math.min(relLimit, 200);
    }

    public int effectiveNodeLimit() {
        return nodeLimit == null || nodeLimit <= 0 ? 50 : Math.min(nodeLimit, 200);
    }
}
