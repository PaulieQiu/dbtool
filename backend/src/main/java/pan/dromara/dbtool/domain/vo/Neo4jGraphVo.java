package pan.dromara.dbtool.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j 图结果 DTO（节点 + 关系集合）
 *
 * @author doubao
 */
@Data
@NoArgsConstructor
public class Neo4jGraphVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 图内节点
     */
    private List<Neo4jNodeVo> nodes = new ArrayList<>();

    /**
     * 图内关系
     */
    private List<Neo4jRelVo> rels = new ArrayList<>();

    public Neo4jGraphVo(List<Neo4jNodeVo> nodes, List<Neo4jRelVo> rels) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
        this.rels = rels == null ? new ArrayList<>() : rels;
    }

    public int nodeCount() {
        return nodes == null ? 0 : nodes.size();
    }

    public int relCount() {
        return rels == null ? 0 : rels.size();
    }

    public boolean isEmpty() {
        return nodeCount() == 0 && relCount() == 0;
    }
}
