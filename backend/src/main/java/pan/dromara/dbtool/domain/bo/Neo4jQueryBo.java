package pan.dromara.dbtool.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Neo4j Cypher 查询请求体
 * <p>参数必须走参数绑定，禁止字符串拼接执行 Cypher。</p>
 *
 * @author doubao
 */
@Data
public class Neo4jQueryBo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 连接配置 ID
     */
    @NotNull(message = "连接不能为空")
    private Long connId;

    /**
     * 目标数据库（为空用连接默认库）
     */
    private String database;

    /**
     * Cypher 语句
     */
    @NotBlank(message = "Cypher 不能为空")
    private String cypher;

    /**
     * 参数（键值，值可为任意 JSON 类型）
     */
    private Map<String, Object> parameters = new LinkedHashMap<>();

    /**
     * 结果行数上限（默认 500）
     */
    private Integer resultLimit;

    /**
     * 超时秒数（默认 30）
     */
    private Integer timeoutSeconds;

    /**
     * 客户端生成执行 ID（用于取消/审计）
     */
    private String executionId;

    /**
     * 是否允许写操作（服务端按连接访问模式强制校验）
     */
    private Boolean write;

    public int effectiveResultLimit() {
        return resultLimit == null || resultLimit <= 0 ? 500 : Math.min(resultLimit, 5000);
    }

    public int effectiveTimeoutSeconds() {
        return timeoutSeconds == null || timeoutSeconds <= 0 ? 30 : Math.min(timeoutSeconds, 300);
    }
}
