package pan.dromara.dbtool.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * ER 图节点(表) VO
 *
 * @author doubao
 */
@Data
public class DbErNodeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表注释
     */
    private String comment;

    /**
     * 列信息列表
     */
    private List<ColumnItem> columns;

    /**
     * ER 图中的列摘要
     */
    @Data
    public static class ColumnItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 列名
         */
        private String name;

        /**
         * 列类型(如 varchar(64))
         */
        private String type;

        /**
         * 是否主键
         */
        private Boolean pk;

        /**
         * 列注释
         */
        private String comment;
    }
}
