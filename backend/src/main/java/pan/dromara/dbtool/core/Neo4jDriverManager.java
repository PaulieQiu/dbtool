package pan.dromara.dbtool.core;

import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.ssh.SshTunnelService;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Neo4j 官方 Java Driver 管理器
 * <p>按连接配置 ID 缓存长生命周期 Driver，避免每次请求重复创建/销毁；</p>
 * <p>连接编辑/删除/池关闭时关闭对应 Driver。支持 Bolt URI 与 SSH 隧道端点。</p>
 *
 * @author doubao
 */
@Slf4j
@Component
public class Neo4jDriverManager {

    private static final int DEFAULT_PORT = 7687;

    private final SshTunnelService sshTunnelService;

    /**
     * Driver 缓存 (connId -> Driver)
     */
    private final Map<Long, Driver> driverCache = new ConcurrentHashMap<>();

    public Neo4jDriverManager(SshTunnelService sshTunnelService) {
        this.sshTunnelService = sshTunnelService;
    }

    /**
     * 获取缓存 Driver（缓存未命中则创建）
     */
    public Driver getDriver(DbConn conn) {
        if (conn == null || conn.getId() == null) {
            throw new ServiceException("Neo4j 连接配置为空");
        }
        Long id = conn.getId();
        Driver driver = driverCache.get(id);
        if (driver == null) {
            synchronized (this) {
                driver = driverCache.get(id);
                if (driver == null) {
                    driver = createDriver(conn);
                    driverCache.put(id, driver);
                    log.info("Neo4j Driver 创建: connId={}, connName={}", id, conn.getConnName());
                }
            }
        }
        return driver;
    }

    /**
     * 创建官方 Driver（Bolt URI + 基础认证）
     */
    public Driver createDriver(DbConn conn) {
        String uri = buildBoltUri(conn);
        String username = StrUtil.nullToEmpty(conn.getUsername());
        String password = DbConnCrypto.decrypt(conn.getPassword());
        Config config = Config.builder()
            .withConnectionTimeout(10, TimeUnit.SECONDS)
            .withMaxConnectionPoolSize(5)
            .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }

    /**
     * 构建 Bolt URI（含 SSH 隧道端点解析）
     */
    public String buildBoltUri(DbConn conn) {
        Endpoint ep = resolveEndpoint(conn);
        int port = ep.port == null ? DEFAULT_PORT : ep.port;
        return "bolt://" + ep.host + ":" + port;
    }

    /**
     * 解析实际端点：启用 SSH 隧道时返回本地转发端点
     */
    private Endpoint resolveEndpoint(DbConn conn) {
        if (conn != null && "1".equals(conn.getSshUseTunnel())) {
            Integer localPort = sshTunnelService.getLocalPort(conn.getId());
            if (localPort == null) {
                localPort = sshTunnelService.createTunnel(conn.getId());
            }
            if (localPort == null) {
                throw new ServiceException("SSH 隧道未就绪, 无法建立 Neo4j 连接");
            }
            return new Endpoint("127.0.0.1", localPort);
        }
        return new Endpoint(conn == null ? null : conn.getHost(), conn == null ? null : conn.getPort());
    }

    /**
     * 测试连接（独立 Driver 验证，不进入连接池缓存）
     *
     * @return 成功返回 null，失败返回中文化错误
     */
    public String test(DbConn conn) {
        try (Driver driver = createDriver(conn)) {
            driver.verifyConnectivity();
            return null;
        } catch (Exception e) {
            log.warn("Neo4j 连接测试失败: {}", e.getMessage());
            return friendlyError(e.getMessage());
        }
    }

    /**
     * Neo4j 连接错误信息中文化
     */
    private String friendlyError(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "无法连接到 Neo4j，请检查主机、端口、认证与网络";
        }
        String msg = raw;
        if (msg.contains("Unauthorized") || msg.contains("authentication failed")
            || msg.contains("AuthenticationRequired") || msg.contains("CredentialsExpired")) {
            return "Neo4j 认证失败（用户名或密码错误），请检查凭据";
        }
        if (msg.contains("Connection refused") || msg.contains("Connection reset")
            || msg.contains("Could not open a TCP connection")) {
            return "无法连接到 Neo4j 服务器（连接被拒绝/重置），请检查地址、端口(Bolt 7687)与网络/防火墙";
        }
        if (msg.contains("timed out") || msg.contains("Timed out") || msg.contains("timeout")) {
            return "连接 Neo4j 超时，请检查网络与防火墙";
        }
        if (msg.contains("UnknownHostException") || msg.contains("Unknown host")) {
            return "无法解析 Neo4j 主机地址，请检查主机名/IP 是否正确";
        }
        if (msg.contains("SecurityError") || msg.contains("SSL") || msg.contains("TLS")) {
            return "Neo4j 安全/证书配置错误（TLS/SSL），请检查连接配置与证书";
        }
        return msg;
    }

    /**
     * 关闭并移除指定连接的 Driver
     */
    public void close(Long connId) {
        Driver driver = driverCache.remove(connId);
        if (driver != null) {
            try {
                driver.close();
                log.info("Neo4j Driver 关闭: connId={}", connId);
            } catch (Exception ignored) {
            }
        }
    }

    /** 端点(host/port) */
    private static final class Endpoint {
        private final String host;
        private final Integer port;

        private Endpoint(String host, Integer port) {
            this.host = host;
            this.port = port;
        }
    }
}
