# 我用 AI 做了一轮"代码体检"，把这个开源数据库工具重构成了生产级

> 一个从 RuoYi 里抽出来的数据库工作台，经过 Trae、Qoder 双 AI 审查 + 对照 DBeaver（DBX）逐行打磨，最终变成现在这个样子。

最近我把自己开源在 GitHub 上的 **[dbtool](https://github.com/PaulieQiu/dbtool)** 做了一轮彻底的"代码体检"。

这个项目原本是 RuoYi-Vue-Plus 里的一个内部模块 `ruoyi-dbtool`，对标 DBeaver 的核心体验：连数据库、看表结构、写 SQL、导数据。上个月我把它抽出来做成了独立开源项目，本来以为能跑就行，结果 AI 一审查，问题炸出一大堆。

这篇文章记录整个过程——**怎么让两个 AI 互相挑刺、怎么照着 DBeaver 这个行业标杆一项项补课、最后代码变成了什么样**。

---

## 一、起点：一个"能跑"的开源项目

项目最早长这样：

- Spring Boot 3.2 + Vue 3 + Element Plus，单体内抽出来的模块
- 支持 56 种数据库类型，MySQL / PostgreSQL / Oracle / ClickHouse / Redis / ES / Neo4j 全覆盖
- 有 SQL 工作台、表结构编辑、跨库对比、备份恢复、AI SQL 助手

README 写得挺漂亮，Demo 也能跑。但我心里清楚，这玩意儿离"生产可用"还差得远——**它只是把代码搬出来了，没真正按独立产品的标准打磨过**。

---

## 二、第一刀：让 Trae 和 Qoder 互相挑刺

我同时开了两个 AI 工具：

- **Trae**：偏架构与安全审查，擅长找设计层面的问题
- **Qoder**：偏细节与资源管理，擅长找内存泄漏、并发问题

两个 AI 分别对整个模块做了逐文件扫描。下面是它们联合开出的"处方单"——**一共 18 项问题**，按严重度排：

### 🔴 高危：会导致线上事故的

| # | 问题 | 后果 |
|---|------|------|
| 1 | 连接池用 `HashMap` 缓存，无淘汰机制 | 跑半年 OOM |
| 2 | LSP（SQL 语言服务）会话建了不删 | 内存只涨不跌 |
| 3 | 事务会话提交后不释放连接 | 连接池被占满 |
| 4 | Redis 每次操作都新建 Socket | 高频操作延迟爆炸 |
| 5 | 查询执行条目只增不减 | 长时间运行后 GC 压力大 |

### 🟡 中危：影响性能和可维护性的

| # | 问题 | 后果 |
|---|------|------|
| 6 | 方言查找线性遍历 19 个适配器 | 每次都 O(n) |
| 7 | 元数据每次查库都重新查 | 同一张表看一眼就打一次 DB |
| 8 | `DbMetaServiceImpl` 写了 2383 行 | 没人敢动 |
| 9 | 魔法常量散落各处 | 调个参数要改一堆文件 |
| 10 | 导出大表不 flush | 几十万行导出直接 OOM |

### 🟢 低危：代码味道

- Controller 里用 `Map` 接参，没有 DTO
- connId 解析不统一，有的 `Long.parseLong` 有的手写
- 没有健康检查端点，出问题不知道哪个连接池炸了

---

## 三、照着 DBeaver 补课：DBX 方言体系

AI 挑完刺，我又打开 DBeaver（开源版代号 DBX）的源码对比——**人家是做了 20 年的商业级工具，每一行都有讲究**。

对比下来发现我有几个根本性的差距：

### 差距 1：方言体系太"野"

DBeaver 有一套完整的 `JdbcDialect` 接口，每个数据库一个实现，统一处理引号转义、分页、类型映射。我原来的写法是 `if-else` 一路判断到底。

**改造方案**：

```java
// 原来：线性遍历
for (DbDialectAdapter adapter : adapters) {
    if (adapter.supports(dbType)) return adapter;
}

// 改造后：注册表 + O(1) 查找
public class DbDialectRegistry {
    private final Map<String, DbDialectAdapter> cache = new ConcurrentHashMap<>();
    public DbDialectAdapter get(String dbType) {
        return cache.computeIfAbsent(dbType, this::find);
    }
}
```

最终落地了 **19 个方言适配器**，包括新补的：
- `AccessDialectAdapter`（微软 Access）
- `InformixDialectAdapter`（IBM Informix）
- `openGauss` / `GaussDB` / `Vastbase` / `XUGU` 国产数据库

### 差距 2：未适配的方言"静默降级"

DBeaver 的做法是：**不认识的方言就明确告诉你"不支持"，而不是用通用 SQL 硬跑然后报错**。

我加了一个 `NullDialectAdapter`，诚实返回 `null`，控制器层收到就抛"暂不支持该数据库类型的元数据浏览"。**安全的失败，比错误的成功强。**

### 差距 3：元数据缓存策略

DBeaver 会在本地缓存表结构，30 秒内重复打开同一张表不打 DB。我原来每次都现查。

加了 `DbMetaCache`：

```java
@Cacheable(value = "dbMeta", key = "#connId + ':' + #type + ':' + #scope")
public List<?> listMeta(...) { ... }

// DDL 执行后主动失效
@CacheEvict(value = "dbMeta", key = "#connId")
public void executeDdl(...) { ... }
```

30 秒 TTL + DDL 主动失效，**既减少了 DB 压力，又保证了 schema 变更后能看到最新结构**。

---

## 四、逐项落地：18 个问题的修复清单

下面是真正改代码的过程。我把它整理成一个可以对照的 checklist：

### 资源管理类

| 问题 | 修复方案 | 关键代码 |
|------|----------|----------|
| 连接池泄漏 | Caffeine Cache | `maximumSize(100) + expireAfterAccess(30min) + removalListener` |
| LSP 会话泄漏 | 定时清理 | `@Scheduled(fixedDelay=300000)` 清 30 分钟空闲会话 |
| 事务会话泄漏 | 定时回收 + 回滚未提交 | 每 5 分钟扫描，超时未提交自动 rollback |
| Redis 高频握手 | 连接复用池 | 每连接 8 个空闲连接，30 秒超时淘汰 |
| 查询条目累积 | 双保险清理 | 定时扫 + `begin()` 惰性兜底 |

### 架构拆分类

把 2383 行的 `DbMetaServiceImpl` 拆成：

```
DbMetaServiceImpl       ← 协调器（瘦身后 ~800 行）
├── DbRedisMetaSupport  ← Redis 键浏览、TTL、危险命令拦截
├── DbEsMetaSupport     ← ES 字段递归、REST SQL
├── DbMetaCache          ← 元数据缓存委托
└── DbDialectRegistry    ← 方言查找委托
```

### 配置外部化

新建 `DbToolProperties`，把所有魔法常量收编：

```yaml
dbtool:
  pool:
    max-size: 100
    expire-after-access: 30m
  meta:
    cache-ttl: 30s
  compare:
    max-rows: 200000
    max-memory-mb: 512
  export:
    flush-every-rows: 1000
```

### 备份策略模式

原来 `DbBackupServiceImpl` 里 `if mysql ... else if postgresql ...` 一路判断，改成策略模式：

```java
public interface DbBackupStrategy {
    boolean supports(String dbType);
    void dump(Connection conn, String outputPath);
    void restore(Connection conn, String inputPath);
}

@Component
public class MySqlBackupStrategy implements DbBackupStrategy { ... }

@Component
public class PostgreSqlBackupStrategy implements DbBackupStrategy { ... }
```

**新增数据库类型只要加一个 Strategy 实现类，不用动老代码。**

### 健康检查

加了一个 `/dbtool/health` 端点，暴露每个连接池的活跃/空闲/总数：

```json
{
  "status": "UP",
  "pools": [
    {"connName": "本地mysql", "active": 2, "idle": 1, "max": 10},
    {"connName": "本地Redis", "active": 0, "idle": 2, "max": 8}
  ]
}
```

---

## 五、AI 审查中发现的几个"反直觉"问题

有几个问题是我自己看代码完全想不到的，记录一下：

### 1. 雪花算法 Long ID 精度丢失

后端主键用 MyBatis-Plus 雪花算法生成的 Long（如 `2109559163560800258`），序列化成 JSON 直接给前端，**JS Number 安全整数范围是 2^53 ≈ 9×10^15**，超过这个数末尾精度就丢了。

结果就是：前端拿到的 connId 是 `...300`，实际库里是 `...258`，点一下连接就 500"数据库连接配置不存在"。

**修复**：Jackson 全局把 Long 序列化成 String：

```java
@Bean
public Jackson2ObjectMapperBuilderCustomizer longToString() {
    return builder -> {
        SimpleModule m = new SimpleModule();
        m.addSerializer(Long.class, ToStringSerializer.instance);
        builder.modulesToInstall(m);
    };
}
```

这个 bug 我自己测了半天没找到，AI 一看日志里的 ID 数字不对就反应过来了。

### 2. Vue 自定义指令未注册导致整页白屏

RuoYi 里有个 `v-hasPermi` 自定义指令，前端到处都在用。独立版没注册，Vue 3 遇到未注册的指令会直接中断渲染——**整个页面空白，控制台只打个 warning**。

**修复**：在 `main.ts` 注册一个空的放行指令。

### 3. ag-grid 版本号"幽灵依赖"

`ag-grid-vue3@36` 依赖 `ag-grid-community@36`，但我 `package.json` 里写的是 `^31`，npm 装出来一个嵌套的 36 和一个顶层的 31，**两份代码打架**，结果网格渲染不出来。

这种问题人肉排查要半天，AI 直接 `npm ls` 一对比就看出来了。

---

## 六、最终数据

| 指标 | 改造前 | 改造后 |
|------|--------|--------|
| 主代码 Java 文件 | ~80 | ~110 |
| 测试类 | 0 | 9 |
| 方言适配器 | 4 | 19 |
| 支持数据库类型 | 28 | 68 |
| 连接池淘汰机制 | 无 | Caffeine TTL + removalListener |
| 元数据缓存 | 无 | 30s TTL + DDL 主动失效 |
| 健康检查端点 | 无 | `/dbtool/health` |
| 备份策略 | if-else | 策略模式 |
| 配置外部化 | 散落 | `DbToolProperties` 统一管理 |

---

## 七、一点感想

这次最大的收获不是代码本身，而是**学会了怎么用 AI 做代码审查**：

1. **不要只信一个 AI**。Trae 偏架构、Qoder 偏细节，两个一起上才能互补
2. **要有行业标杆做参照**。AI 知道"什么是好的"，但不知道"这个项目应该长什么样"。拿 DBeaver 当参照物，AI 的建议立刻具体了
3. **AI 找 bug 比找设计问题准**。精度丢失、版本冲突这种问题 AI 一眼看穿；但"这个类要不要拆"这种判断，还是得人来拍板
4. **每个修复都要有测试**。9 个测试类是这次新加的，不然改完不知道有没有把别的搞坏

项目地址：**[github.com/PaulieQiu/dbtool](https://github.com/PaulieQiu/dbtool)**，MIT 协议，欢迎 star 和提 issue。

---

*本文由豆包 AI 协助撰写，代码审查由 Trae + Qoder 联合完成，架构设计参照 RuoYi-Vue-Plus 与 DBeaver 开源版。*
