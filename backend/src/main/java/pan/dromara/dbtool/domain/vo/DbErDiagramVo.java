package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * ER 图响应 VO(表节点 + 外键连线)
 *
 * @author doubao
 */
@Data
public class DbErDiagramVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表节点列表
     */
    private List<DbErNodeVo> nodes;

    /**
     * 外键连线列表
     */
    private List<DbErEdgeVo> edges;

    /**
     * 表总数(截断前)
     */
    private Integer totalTableCount;

    /**
     * 是否因数量过多被截断
     */
    private Boolean truncated;
}
