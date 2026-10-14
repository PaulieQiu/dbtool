package pan.dromara.dbtool.ssh.impl;

import pan.dromara.dbtool.core.DbConnCrypto;
import pan.dromara.dbtool.domain.DbConn;
import pan.dromara.dbtool.mapper.DbConnMapper;
import pan.dromara.dbtool.ssh.SshTunnelService;

import cn.hutool.core.util.StrUtil;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 隧道实现(JSch 本地端口转发)
 * <p>隧道生命周期: createTunnel 建立并缓存 -> 连接池/测试连接复用本地端口 ->
 * 连接编辑/删除/池关闭时由 DbConnectionManager.close 级联关闭; 应用停机时 closeAllTunnels。</p>
 *
 * @author doubao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbSshTunnelServiceImpl implements SshTunnelService {

    private static final int SSH_CONNECT_TIMEOUT_MS = 10000;

    /** 是否校验 SSH 主机密钥(默认 no 兼容既有隧道; 生产建议 yes/ask 并配置 known_hosts 防中间人) */
    @Value("${dbtool.ssh.strictHostKeyChecking:no}")
    private String strictHostKeyChecking = "no";

    private final DbConnMapper dbConnMapper;

    /** 活跃隧道: connId -> Session + 本地端口 */
    private final Map<Long, TunnelHolder> tunnels = new ConcurrentHashMap<>();

    @Override
    public Integer createTunnel(Long connId) {
        if (connId == null) {
            return null;
        }
        // 已存在且连接正常: 直接复用
        TunnelHolder existing = tunnels.get(connId);
        if (existing != null && existing.session.isConnected()) {
            return existing.localPort;
        }
        // 已存在但已断开: 移除旧引用(其 Session 已断开, 无需显式关闭), 避免 computeIfAbsent 复用过期句柄
        if (existing != null) {
            tunnels.remove(connId, existing);
        }
        // computeIfAbsent 原子保证: 同一 connId 并发请求只建立一次隧道,
        // 后到线程复用先到结果, 避免并发建立覆盖 tunnels 引用导致先建 Session 泄漏
        return tunnels.computeIfAbsent(connId, this::buildTunnel).localPort;
    }

    /**
     * 建立隧道(由 computeIfAbsent 调用, 同一 connId 天然串行)
     */
    private TunnelHolder buildTunnel(Long connId) {
        DbConn conn = dbConnMapper.selectById(connId);
        if (conn == null) {
            throw new ServiceException("数据库连接不存在: " + connId);
        }
        if (!"1".equals(conn.getSshUseTunnel())) {
            throw new ServiceException("该连接未启用 SSH 隧道");
        }
        if (StrUtil.isBlank(conn.getSshHost()) || StrUtil.isBlank(conn.getSshUser())) {
            throw new ServiceException("SSH 隧道配置不完整(缺少跳板机主机或用户名)");
        }
        try {
            JSch jsch = new JSch();
            Session session = jsch.getSession(conn.getSshUser(), conn.getSshHost(),
                conn.getSshPort() == null ? 22 : conn.getSshPort());
            String sshPassword = conn.getSshPassword() == null ? "" : DbConnCrypto.decrypt(conn.getSshPassword());
            session.setPassword(StrUtil.nullToEmpty(sshPassword));
            session.setConfig("StrictHostKeyChecking", strictHostKeyChecking);
            session.setConfig("ConnectTimeout", String.valueOf(SSH_CONNECT_TIMEOUT_MS));
            session.connect(SSH_CONNECT_TIMEOUT_MS);
            // 本地端口 0 = 由操作系统分配空闲端口; 转发到数据库真实地址
            int localPort = session.setPortForwardingL(0, conn.getHost(),
                conn.getPort() == null ? defaultPort(conn.getDbType()) : conn.getPort());
            log.info("SSH 隧道建立: connId={}, 本地 {} -> {}:{}", connId, localPort, conn.getHost(), conn.getPort());
            return new TunnelHolder(session, localPort);
        } catch (Exception e) {
            throw new ServiceException("SSH 隧道建立失败(" + conn.getSshHost() + ":" + conn.getSshPort() + "): " + e.getMessage());
        }
    }

    @Override
    public void closeTunnel(Long connId) {
        TunnelHolder holder = tunnels.remove(connId);
        if (holder != null) {
            try {
                if (holder.session.isConnected()) {
                    holder.session.disconnect();
                }
                log.info("SSH 隧道关闭: connId={}, 本地端口 {}", connId, holder.localPort);
            } catch (Exception e) {
                log.warn("SSH 隧道关闭异常: connId={}, {}", connId, e.getMessage());
            }
        }
    }

    @Override
    public boolean isTunnelActive(Long connId) {
        TunnelHolder holder = tunnels.get(connId);
        return holder != null && holder.session.isConnected();
    }

    @Override
    public Integer getLocalPort(Long connId) {
        TunnelHolder holder = tunnels.get(connId);
        return holder != null && holder.session.isConnected() ? holder.localPort : null;
    }

    @Override
    public void closeAllTunnels() {
        for (Long connId : tunnels.keySet()) {
            closeTunnel(connId);
        }
    }

    private int defaultPort(String dbType) {
        String type = StrUtil.nullToEmpty(dbType).toUpperCase();
        return switch (type) {
            case "MYSQL", "MARIADB" -> 3306;
            case "POSTGRESQL" -> 5432;
            case "ORACLE" -> 1521;
            case "SQLSERVER" -> 1433;
            case "REDIS" -> 6379;
            case "ELASTICSEARCH" -> 9200;
            case "DM" -> 5236;
            default -> 3306;
        };
    }

    /** 隧道句柄 */
    private static final class TunnelHolder {
        private final Session session;
        private final int localPort;

        private TunnelHolder(Session session, int localPort) {
            this.session = session;
            this.localPort = localPort;
        }
    }
}
