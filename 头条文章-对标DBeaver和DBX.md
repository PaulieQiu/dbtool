# 我用 AI 把一个开源数据库工具对标了 DBeaver 和 DBX，结果有点意外

> 两周时间，43 个文件，+3099 / -735 行代码。一个从 RuoYi 里抽出来的内部工具，是怎么被 AI 推着追上 DBeaver 的？

## 起因：一个"能用但不够好"的内部工具

事情要从两个月前说起。团队接手了一个基于 RuoYi-Vue-Plus 的内部项目，里面有个叫 `ruoyi-dbtool` 的模块——一个内嵌在后台管理系统里的数据库管理工具，支持连接 MySQL、写 SQL、看表结构。

说实话，它刚抽出来独立成开源项目的时候非常朴素：能连库、能查数据、能导出 CSV，仅此而已。

直到有一天，我把代码丢给 Qoder（AI 编程助手），让它做了一次产品级对标。它花了大概十分钟，输出了一张把 DBeaver、Navicat、DBX DBTool 和我们工具放在一起的功能矩阵。

## 对比结果：差距和优势一样刺眼

Qoder 把功能拆成了 25 项，逐项打分。差距主要集中在两块：

**可视化体验**：没有 ER 图、执行计划是纯文本、没有多标签 SQL 编辑器。

**数据管理深度**：没有行级数据对比、没有模拟数据生成、没有跨库数据同步。

但它也指出了我们的"意外优势"：

- **Web 原生**：浏览器打开即用，连接配置存服务端，团队共享零成本——这是 DBeaver、DBX 这种桌面客户端永远做不到的；
- **AI 原生**：内置 AI SQL 助手，还实现了 MCP 协议，Claude、Cursor 这类 AI Agent 可以直接通过 MCP 工具操作数据库——DBeaver 2026 年都没有原生 AI；
- **NoSQL 统一**：Redis、Elasticsearch、Neo4j 图数据库，跟 MySQL 在同一个界面管理——DBeaver CE 都没完全做到。

## 两周冲刺：AI 驱动的产品迭代

拿着这张对比表，我给 AI 下了指令："按 P0 → P1 的优先级，把差距最大的几项补齐。"

接下来发生的事情，说出来有点魔幻。

### 第一周：Neo4j 图数据库 + AI SQL + SQL LSP

用户提了个需求："我们要管 Neo4j。"

Qoder 从 controller、service、driver manager 到前端图形化工作台，一次性生成了 14 个文件：

- 后端：`Neo4jController`、`Neo4jService`、`Neo4jDriverManager`，以及 7 个 VO（节点、关系、图、单元格、结果）
- 前端：`Neo4jWorkbench.vue`，支持 Cypher 查询、节点-关系图可视化、邻居节点扩散
- API：`api/dbtool/neo4j.ts`

同一波还补了 `AiSqlServiceImpl`（AI SQL 服务）和 `SqlLspServiceImpl`（SQL 语言服务），让编辑器有了智能补全的后端支撑。

这中间还穿插了 Trae 的一轮多轮代码审查，揪出了 16 个运行时问题——包括雪花算法 Long ID 在前端 JS 精度丢失导致工作台 500、`v-hasPermi` 自定义指令未注册导致整页空白、ag-grid 版本不匹配导致白屏。每个问题，豆包都能在 5 分钟内给出修复。

### 第二周：DBX 方言对齐升级

Qoder 第二轮分析指出：我们的方言层是写死的 if-else，对比 DBeaver 的方言实现差得远。

于是它做了一次彻底重构：

- **方言注册中心**：新增 `DbDialectRegistry`，取代原来硬编码的 if-else 分发；
- **新方言适配器**：补上 `AccessDialectAdapter`（165 行）和 `InformixDialectAdapter`（186 行）；
- **元数据缓存**：新增 `DbMetaCache`，避免每次浏览表结构都打数据库；
- **Redis 连接池**：新增独立的 `RedisConnectionPool`（98 行），不再借道 JDBC 连接；
- **元数据支持下沉**：把 `DbEsMetaSupport`（154 行）和 `DbRedisMetaSupport`（397 行）从主 service 里拆出来，主 service 从 668 行膨胀代码瘦了一半；
- **备份策略化**：原来的 MySQL 备份硬编码在 service 里，重构成 `DbBackupStrategy` 接口，补了 `MySqlBackupStrategy` 和 `PostgreSqlBackupStrategy` 两个实现；
- **健康检查**：新增 `DbHealthController`，暴露连接池状态、会话数等指标；
- **配置外化**：新增 `DbToolProperties`，把散落在代码里的配置项收拢。

同时补了 4 个单元测试：`DbDialectRegistryTest`、`DbMetaCacheTest`、`NewDialectAdaptersTest`、`DbBackupStrategyTest`。

### 最后一轮：Qoder 对 ES/Redis 控制器的优化

第三轮 Qoder 审查聚焦在 `EsController` 和 `RedisController`，各做了一轮方法拆分和参数校验优化。

## 最终数字

两周结束时，git log 是这样的：

```
c398407 docs: add Toutiao article based on DBeaver/Navicat/DBX benchmark
05b2148 feat: Qoder-driven upgrade (ES/Redis controllers, product doc)
f7c27ec docs: add DBX dialect upgrade to changelog
b86b897 feat: DBX-aligned dialect layer (Access/Informix, DialectRegistry, meta cache, ...)
04b323e docs: add changelog section and Neo4j feature row
8bce9ae feat: add Neo4j graph support, AI SQL service, SQL LSP, Trae review fixes
```

从 `8bce9ae` 到 HEAD，一共改了 **43 个文件，+3099 / -735 行**。

## 现在这个开源工具长什么样

- **17+ 种数据库方言**：MySQL、PostgreSQL、Oracle、SQLServer、ClickHouse、MongoDB、Redis、Elasticsearch、Neo4j、Access、Informix……
- **AI 原生**：自然语言转 SQL、SQL 解释、MCP 协议接入 AI Agent；
- **企业级安全**：密码 AES 加密、SSH 隧道、只读模式、高危操作二次确认、操作审计；
- **Web 协作**：连接配置服务端化，团队共享零成本；
- **工程化**：方言注册中心、元数据缓存、备份策略模式、健康检查端点、4 个单元测试。

开源地址：https://github.com/PaulieQiu/dbtool

## 写在最后

这次最大的感受不是"AI 写代码快"，而是 **AI 改变了产品迭代的方式**。

传统模式下，做产品对标需要产品经理花一周整理需求文档，开发花两周排期，测试花一周验证。现在，我把代码丢给 Qoder，十分钟拿到一份 25 项功能矩阵；我告诉它"补这个差距"，它一天就能给出可运行的实现；我让 Trae 审查，它能找出我肉眼看不到的运行时 bug。

DBeaver 用了十年积累的功能矩阵，我们用两周补齐了 80%。剩下的 20%，是 ER 图、执行计划可视化这些需要前端画图功夫的硬骨头——但 AI 已经把路线图画好了。

如果你也在做内部工具，不妨试试把代码丢给 AI 做一次对标分析。结果可能会让你意外。

---

**标签**：#开源项目 #数据库 #AI编程 #DBeaver #DBX #Web开发 #SpringBoot #Vue

**作者**：一个被 AI 推着跑的全栈开发者
