package pan.dromara.dbtool.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * dbtool 模块外部化配置(prefix=dbtool)
 * <p>把散落在各 service 的魔法常量(超时、分页上限等)统一收敛到配置, 支持通过
 * application.yml 的 {@code dbtool.*} 调整, 无需改代码。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "dbtool")
public class DbToolProperties {

    /** 元数据查询/执行超时(秒) */
    private int queryTimeoutSeconds = 120;

    /** 网格分页最大页大小 */
    private int gridMaxPageSize = 1000;

    /** 数据导出查询超时(秒) */
    private int exportTimeoutSeconds = 300;

    /** 事务会话内执行超时(秒) */
    private int transactionTimeoutSeconds = 120;

    /** 数据对比查询超时(秒) */
    private int compareTimeoutSeconds = 300;
}
