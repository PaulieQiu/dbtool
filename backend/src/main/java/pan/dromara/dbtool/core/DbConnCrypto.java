package pan.dromara.dbtool.core;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据库连接密码 加解密工具
 * <p>AES 加密存储。密钥优先取环境变量 {@code DBTOOL_CRYPTO_KEY} 或系统属性
 * {@code dbtool.crypto.key}(需 16/24/32 字节)，缺省/长度非法时回退内置默认值以兼容存量密文。
 * <b>安全提示:</b> 生产环境务必外部化配置密钥，勿使用内置默认值。</p>
 *
 * @author doubao
 */
public class DbConnCrypto {

    private static final Logger log = LoggerFactory.getLogger(DbConnCrypto.class);

    /**
     * AES 密钥: 外部化覆盖或回退默认(16字节)
     */
    private static final String KEY = resolveKey();

    private static final AES AES = SecureUtil.aes(KEY.getBytes());

    private DbConnCrypto() {
    }

    /**
     * 解析 AES 密钥: 优先外部配置(环境变量/系统属性), 长度非法或缺省时回退默认, 避免静态初始化失败
     */
    private static String resolveKey() {
        String env = System.getenv("DBTOOL_CRYPTO_KEY");
        if (env != null && (env.length() == 16 || env.length() == 24 || env.length() == 32)) {
            return env;
        }
        String prop = System.getProperty("dbtool.crypto.key");
        if (prop != null && (prop.length() == 16 || prop.length() == 24 || prop.length() == 32)) {
            return prop;
        }
        // static 初始化早于日志框架装配，此处用 System.err 确保安全警告一定可见(仅类加载时打印一次)
        System.err.println("[dbtool][安全警告] 未配置外部 AES 密钥(环境变量 DBTOOL_CRYPTO_KEY 或系统属性 dbtool.crypto.key),"
            + " 已回退内置默认密钥。生产环境务必外部化配置密钥, 否则存储的连接密码存在被反编译破解的风险!");
        return "dbtool-crypto-16";
    }

    /**
     * 加密
     */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return AES.encryptBase64(plain);
    }

    /**
     * 解密
     */
    public static String decrypt(String cipher) {
        if (cipher == null || cipher.isEmpty()) {
            return "";
        }
        try {
            return AES.decryptStr(cipher);
        } catch (Exception e) {
            // 解密失败说明可能是明文或密钥变更: 原样返回以便兼容, 但需告警便于排查
            log.warn("连接密码解密失败(可能为明文或 AES 密钥已变更), 原样返回: {}", e.getMessage());
            return cipher;
        }
    }
}
