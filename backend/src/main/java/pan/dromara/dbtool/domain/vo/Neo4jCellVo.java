package pan.dromara.dbtool.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Neo4j 结果单元格 DTO（带类型，避免 JS 精度丢失/结构歧义）
 * <p>type 取值：null/string/long/double/boolean/list/map/node/rel/path/other；</p>
 * <p>node/rel/path 时 value 为图引用 id（如 n0/r0），实际内容在 Neo4jResultVo.graph 中。</p>
 *
 * @author doubao
 */
@Data
@NoArgsConstructor
public class Neo4jCellVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 值类型
     */
    private String type;

    /**
     * 值（标量直接存；图元素存图内引用 id）
     */
    private Object value;

    public Neo4jCellVo(String type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static Neo4jCellVo of(String type, Object value) {
        return new Neo4jCellVo(type, value);
    }
}
