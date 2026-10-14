package pan.dromara.dbtool.core.dialect;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据库方言注册表(共享组件)
 * <p>集中持有方言适配器列表与按 dbType 的 O(1) 缓存查找, 供 {@code DbMetaServiceImpl} 元数据路由与
 * {@code DbGridServiceImpl} 分页统一使用, 避免两处各自维护方言逻辑造成重复/不一致。</p>
 *
 * @author doubao
 */
@Component
public class DbDialectRegistry {

    /** 方言适配器注册表(优先匹配, 未命中回退 NULL_DIALECT) */
    private static final List<DbDialectAdapter> DIALECTS = List.of(
        new DmDialectAdapter(),
        new SqlServerDialectAdapter(),
        new OracleDialectAdapter(),
        new ClickHouseDialectAdapter(),
        new SqliteDialectAdapter(),
        new H2DialectAdapter(),
        new DuckDbDialectAdapter(),
        new VerticaDialectAdapter(),
        new ExasolDialectAdapter(),
        new FirebirdDialectAdapter(),
        new Db2DialectAdapter(),
        new HsqlDialectAdapter(),
        new PostgreSqlDialectAdapter(),
        new InformixDialectAdapter(),
        new AccessDialectAdapter(),
        new MySqlDialectAdapter()
    );

    /** 未适配方言的统一降级实例(各 SQL 方法返回 null, 由上层检测后明确报错/降级) */
    private static final DbDialectAdapter NULL_DIALECT = new NullDialectAdapter();

    /** 方言查找缓存: 按 dbType 大写命中, 首次线性遍历后 O(1) */
    private final Map<String, DbDialectAdapter> cache = new ConcurrentHashMap<>();

    /**
     * 按数据库类型选择方言适配器(忽略大小写); 未命中返回 {@link #NULL_DIALECT}, 不静默回退 MySQL
     */
    public DbDialectAdapter dialect(String dbType) {
        if (dbType == null) {
            return NULL_DIALECT;
        }
        String key = dbType.trim().toUpperCase(Locale.ROOT);
        DbDialectAdapter cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        DbDialectAdapter found = findDialect(dbType);
        cache.putIfAbsent(key, found);
        return found;
    }

    /**
     * 分页包装: 委托方言 paginate; 未适配方言(NULL_DIALECT 返回 null)回退通用 OFFSET/FETCH, 保持既有行为
     */
    public String paginate(String dbType, String sql, int offset, int size) {
        DbDialectAdapter a = dialect(dbType);
        String p = a.paginate(sql, offset, size);
        if (p != null) {
            return p;
        }
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY";
    }

    private DbDialectAdapter findDialect(String dbType) {
        for (DbDialectAdapter a : DIALECTS) {
            if (a.supports(dbType)) {
                return a;
            }
        }
        return NULL_DIALECT;
    }
}
