package pan.dromara.dbtool.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 节点 DTO（图结果/邻居展开通用）
 * <p>id 为本次结果内的会话标识，仅用于图视图内部定位与邻居展开，不作为可持久化业务主键；</p>
 * <p>elementId 为驱动返回的服务端 elementId，同样仅作临时定位。</p>
 *
 * @author doubao
 */
@Data
@NoArgsConstructor
public class Neo4jNodeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话内节点标识（如 n0），图视图元素 id
     */
    private String id;

    /**
     * 驱动返回的节点 id()（同一次执行内可定位）
     */
    private Long nodeId;

    /**
     * 驱动返回的 elementId（临时定位，不持久化）
     */
    private String elementId;

    /**
     * 节点标签
     */
    private List<String> labels = new ArrayList<>();

    /**
     * 节点属性（值已转为可 JSON 序列化的类型化标量）
     */
    private Map<String, Object> properties = new LinkedHashMap<>();

    /**
     * 是否有属性
     */
    public boolean hasProps() {
        return properties != null && !properties.isEmpty();
    }

    /**
     * 便捷显示名：优先取 name/title/标签，其次标签集合，最后回退 id
     */
    public String displayName() {
        for (String key : new String[]{"name", "title", "label"}) {
            Object v = properties.get(key);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        if (labels != null && !labels.isEmpty()) {
            return labels.get(0) + ":" + String.valueOf(nodeId == null ? id : nodeId);
        }
        return id == null ? String.valueOf(nodeId) : id;
    }
}
