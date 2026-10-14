# DBTool · 数据库工作台

> 一个 Web 版的在线数据库管理工作台，对标 DBeaver 的核心体验：连接管理、元数据浏览、表结构编辑、SQL 查询与结果网格、Redis / Elasticsearch 可视化、跨库 Schema 对比与数据迁移。
>
> 本项目由 **豆包（Doubao）AI 编程助手** 协助完成全部代码与文档，并基于 **RuoYi-Vue-Plus** 框架抽取 `ruoyi-dbtool` 模块二次开发，抽离为可独立运行的开源版本。

---

## 一、特性总览

| 模块 | 能力 |
| --- | --- |
| **驱动管理** | 56 种数据库类型注册表（SQL 28 / NoSQL 2 / 图 2 / 大数据 5 / 分析型 13 / 时序 3 / 嵌入式 2 / 全文 1）；驱动可从 Maven Central 自动下载或手动上传 jar |
| **数据源管理** | 连接 CRUD、连通性测试、密码 AES 加密存储、SSH 跳板机隧道、连接池复用、本地 Redis 自动登记 |
| **数据库工作台** | 左侧「连接 → 库 → 表」树；右侧「列结构 / 索引 / 约束 / 外键 / 引用 / 触发器 / DDL / 数据」Tab；Monaco SQL 编辑器（智能补全、格式化、执行、取消）；AG-Grid 结果网格（双击编辑、行选择、CSV 导出、排序过滤） |
| **Redis 可视化** | SCAN 键扫描、键值读写、TTL、INFO、CLI |
| **ES 可视化** | 索引浏览、mapping/settings、文档 CRUD、_search、Dev Tools 代理、SQL |
| **结构对比 & 数据迁移** | 跨连接 Schema Diff、迁移脚本预览、跨库表数据同步 |
| **Excel 导入** | 多工作表解析、预览后入库 |
| **备份恢复** | MySQL mysqldump 封装、任务列表、文件下载 |
| **SQL 历史 / 脚本库** | 服务端化持久化，替代浏览器 localStorage |
| **AI SQL 助手** | 自然语言转 SQL、SQL 解释（可对接任意 OpenAI 兼容网关） |
| **Neo4j 图工作台** | Cypher 查询、节点/关系图形化展开、邻居节点扩散、图结果表格化展示 |

---

## 二、技术架构

```
┌──────────────────────────────────────────────────────────────┐
│  前端 (frontend/)                                            │
│  Vue 3 + Vite + Element Plus + Pinia                         │
│  ├─ monaco-editor       SQL 编辑器                           │
│  ├─ ag-grid-community   结果网格                             │
│  ├─ sql-formatter        SQL 格式化                           │
│  └─ highlight.js         SQL 高亮                             │
└────────────────────────┬─────────────────────────────────────┘
                         │  HTTP / JSON
┌─────────────────────────┴─────────────────────────────────────┐
│  后端 (backend/)                                             │
│  Spring Boot 3.2 + JDK 17                                   │
│  ├─ ruoyi-dbtool 模块（抽取自 RuoYi-Vue-Plus）                │
│  │   ├─ controller   REST 接口（前缀 /dbtool）                │
│  │   ├─ service     业务逻辑                                 │
│  │   ├─ core        连接池 / 方言适配器 / 驱动加载 / 类型注册   │
│  │   ├─ ssh         JSch SSH 隧道                            │
│  │   ├─ diff        Schema 对比                               │
│  │   └─ ai          AI SQL 助手                              │
│  ├─ MyBatis-Plus    元数据库（存连接配置/驱动/历史）          │
│  ├─ Sa-Token        鉴权（默认放行，可自行扩展）              │
│  ├─ Redisson        一次性确认凭证、会话注册表                 │
│  └─ HikariCP + JDBC 直连用户配置的目标数据源                  │
└────────────────────────┬─────────────────────────────────────┘
                         │
        ┌────────────────┼─────────────────┐
        ▼                ▼                 ▼
   MySQL/PG/...      Redis            Elasticsearch
   (业务元数据库)    (缓存/确认凭证)   (被管理的数据源)
```

**关键设计**：
- 后端通过 `DbTypeRegistry` 集中维护 56 种数据库类型的驱动类、URL 模板、默认端口、Maven 坐标；新增类型只需加一行 `DbTypeInfo`。
- `DbConnectionManager` 按 `connId` 维护连接池，测试连接与工作查询复用同一池。
- `DbDialect` 方言适配器按数据库类型差异生成列 / 索引 / DDL 查询语句。
- 密码使用 AES 加密落库（`DbConnCrypto`）。
- 前端通过 Vite 代理把 `/dbtool/**` 转发到后端 `:8080`，避免跨域。

---

## 三、目录结构

```
dbtool-opensource/
├── README.md
├── LICENSE                     # MIT
├── backend/                    # Spring Boot 后端
│   ├── pom.xml
│   └── src/main/
│       ├── java/
│       │   ├── pan/dromara/dbtool/         # dbtool 业务代码
│       │   └── org/dromara/common/         # RuoYi 兼容 shim（轻量）
│       └── resources/application.yml
├── frontend/                   # Vue 3 前端
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── api/dbtool/
│       ├── views/dbtool/
│       ├── utils/request.ts
│       └── store/modules/user.ts
└── sql/
    └── schema.sql              # 一键建库脚本
```

---

## 四、环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 18+（推荐 20 LTS） |
| MySQL | 8.x（存元数据） |
| Redis | 6.x+（存确认凭证 / 会话） |

---

## 五、快速启动

### 1. 初始化元数据库

```bash
mysql -uroot -p < sql/schema.sql
```

脚本会自动创建 `dbtool` 库及 4 张元数据表：`sys_db_conn`、`sys_db_driver`、`dbtool_sql_history`、`dbtool_sql_script`。

### 2. 启动后端

```bash
cd backend
# 按需修改 src/main/resources/application.yml
#   - spring.datasource.url/username/password  元数据库连接
#   - spring.data.redis.host/port              Redis 连接
mvn spring-boot:run
# 默认监听 http://127.0.0.1:8080
```

打 jar 包：

```bash
mvn -DskipTests package
java -jar target/dbtool-backend.jar
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
# 默认监听 http://127.0.0.1:5173 ，已配置 /dbtool 代理到 8080
```

生产构建：

```bash
npm run build      # 产物在 frontend/dist，可托管到 Nginx 或后端静态目录
```

### 4. 访问

浏览器打开 `http://127.0.0.1:5173`，左侧菜单切换「数据源管理 / 数据库工作台 / 驱动管理」。首次使用先在「数据源管理」新增一个连接，再到「数据库工作台」浏览。

---

## 六、部署方式

### 方式 A：开发模式（前后端分离）

后端 `mvn spring-boot:run`，前端 `npm run dev`，适合本地开发。

### 方式 B：单机生产部署

1. 后端：`mvn -DskipTests package` → `java -jar dbtool-backend.jar --spring.profiles.active=prod`
2. 前端：`npm run build` → 把 `dist/` 放到 Nginx，Nginx 同时把 `/dbtool/` 反代到后端：

```nginx
server {
  listen 80;
  root /opt/dbtool/dist;
  location / { try_files $uri $uri/ /index.html; }
  location /dbtool/ { proxy_pass http://127.0.0.1:8080; }
}
```

### 方式 C：容器化（可选）

项目未内置 Dockerfile，但因是标准 Spring Boot + 静态资源，可自行用 `eclipse-temurin:17-jre` + `nginx:alpine` 构建。

---

## 七、配置说明

`backend/src/main/resources/application.yml` 关键项：

| 配置 | 说明 |
| --- | --- |
| `spring.datasource.*` | 元数据库（MySQL）连接 |
| `spring.data.redis.*` | Redis 连接（确认凭证 / 会话） |
| `dbtool.backup.dir` | MySQL 备份文件落盘目录 |
| `dbtool.ai.allowConns` | 允许使用 AI 助手的数据源 ID 白名单 |
| Sa-Token | 默认全部接口放行，接入生产请自行加登录拦截 |

---

## 八、与 RuoYi 的关系

本项目**不是** RuoYi 全量二开，而是从 RuoYi-Vue-Plus 中抽取 `ruoyi-dbtool` 模块后做了两件事：

1. **剥离框架依赖**：原模块依赖 `ruoyi-common-core / mybatis / security / web / log` 等多个公共模块；本仓库在 `org.dromara.common.*` 包下提供了等价的轻量 shim（统一响应 `R`、业务异常 `ServiceException`、审计基类 `BaseEntity`、`BaseMapperPlus`、`LoginHelper`、`BaseController`、`@Log` 注解），不再需要安装整个 RuoYi。
2. **独立可运行**：自带 `DbToolApplication` 启动类、`application.yml`、最小前端壳（菜单 + 三个视图挂载）。

如需把本模块重新集成回 RuoYi-Vue-Plus：直接把 `backend/src/main/java/pan/dromara/dbtool/` 拷回 `ruoyi-modules/ruoyi-dbtool`，删除 `org/dromara/common/` shim 目录即可。

---

## 九、致谢

- [RuoYi-Vue-Plus](https://gitee.com/dromara/RuoYi-Vue-Plus) — 后端脚手架与工程规范
- [DBeaver](https://dbeaver.io/) — 产品体验参考
- [Monaco Editor](https://microsoft.github.io/monaco-editor/) / [AG-Grid](https://www.ag-grid.com/) / [Element Plus](https://element-plus.org/)
- 由 **豆包 AI** 协助完成代码生成、重构与文档编写

---

## 十、更新记录

| 日期 | Commit | 内容 |
| --- | --- | --- |
| 2026-10-14 | `b86b897` | **DBX 方言对齐升级**：新增 `DbDialectRegistry` 方言注册中心、`AccessDialectAdapter` / `InformixDialectAdapter`；新增 `DbMetaCache` 元数据缓存、`RedisConnectionPool`、`DbRedisMetaSupport` / `DbEsMetaSupport`；备份模块策略化（`DbBackupStrategy` + MySQL/PostgreSQL 实现）；新增 `DbHealthController` 健康检查与 `DbToolProperties` 配置类；配套单元测试 |
| 2026-10-13 | `04b323e` | README 补充更新记录章节与 Neo4j 特性行 |
| 2026-10-13 | `8bce9ae` | **新增 Neo4j 图数据库能力**（Neo4jController / Neo4jService / Neo4jWorkbench.vue，Cypher 查询与节点-关系图可视化）；新增 AI SQL 服务 `AiSqlServiceImpl` 与 SQL LSP 服务 `SqlLspServiceImpl`；修复 Trae 多轮代码审查发现的连接管理 / 备份 / 导出 / 事务会话等问题；补充对应单元测试 |
| 2026-10-12 | `c2c9c02` | 公开仓库 `application.yml` 密码改回占位符，避免本地凭证泄露 |
| 2026-10-12 | `bab6133` | 修复雪花算法 Long ID 在前端 JS Number 精度丢失导致工作台 500（Jackson Long → String） |
| 2026-10-12 | `6455f55` | 注册空 `v-hasPermi` 指令；移除 `conn/index.vue` 中未导入的 `getCurrentInstance()` 调用 |
| 2026-10-12 | `857ff69` | 菜单顺序调整为：驱动管理 → 数据源管理 → 数据库工作台 |
| 2026-10-12 | `651f7bc` | 对齐 `ag-grid-community` 与 `ag-grid-vue3` 版本到 36.1.0，修复空白页 |
| 2026-10-12 | `e019235` | 独立运行版匿名登录过滤器 + 全部 `dbtool:*` 权限授予，绕开 Sa-Token 鉴权 |
| 2026-10-12 | `b0b88ce` | 初始开源版本：从 RuoYi-Vue-Plus 抽取 `ruoyi-dbtool` 模块，配套轻量 shim，完成 README / LICENSE / SQL 脚本 |

---

## 十一、License

MIT License，详见 [LICENSE](./LICENSE)。
