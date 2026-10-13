package pan.dromara.dbtool.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j Cypher 查询结果 DTO（图 + 表双视图数据源）
 *
 * @author doubao
 */
@Data
@NoArgsConstructor
public class Neo4jResultVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 执行 ID（客户端生成，用于取消/审计）
     */
    private String executionId;

    /**
     * 返回列名
     */
    private List<String> columns = new ArrayList<>();

    /**
     * 表视图行：每行单元格为类型化 Neo4jCellVo
     */
    private List<List<Neo4jCellVo>> rows = new ArrayList<>();

    /**
     * 图视图数据（节点 + 关系）
     */
    private Neo4jGraphVo graph;

    /**
     * 执行耗时（服务端结果可用耗时，ms）
     */
    private long resultAvailableAfterMs;

    /**
     * 结果是否被截断（达到行数/节点/关系上限）
     */
    private boolean truncated;

    /**
     * 可读错误信息（成功为空）
     */
    private String error;

    /**
     * 写操作影响计数摘要（如 {nodesCreated:1, relationshipsCreated:1}）
     */
    private Map<String, Long> counters = new LinkedHashMap<>();

    public boolean hasGraph() {
        return graph != null && !graph.isEmpty();
    }
}
