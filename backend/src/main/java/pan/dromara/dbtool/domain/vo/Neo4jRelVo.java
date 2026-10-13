package pan.dromara.dbtool.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Neo4j 关系 DTO
 *
 * @author doubao
 */
@Data
@NoArgsConstructor
public class Neo4jRelVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话内关系标识（如 r0），图视图元素 id
     */
    private String id;

    /**
     * 驱动返回的关系 id()
     */
    private Long relId;

    /**
     * 驱动返回的 elementId（临时定位）
     */
    private String elementId;

    /**
     * 关系类型（如 KNOWS）
     */
    private String type;

    /**
     * 起点节点会话内标识（指向 Neo4jNodeVo.id）
     */
    private String source;

    /**
     * 终点节点会话内标识
     */
    private String target;

    /**
     * 关系属性（值已类型化）
     */
    private Map<String, Object> properties = new LinkedHashMap<>();
}
