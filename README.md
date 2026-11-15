# 汇智 ERP 管理系统

一个用 Java 写的、可以直接跑起来的轻量级 ERP。后端 Spring Boot 3.5 + JdbcTemplate，前端原生 HTML/CSS/JS 单页后台，默认使用文件型 H2 数据库 —— **不需要安装 MySQL、不需要 Node.js、不需要配置任何环境变量**，双击 `start.bat` 即可使用。

在进销存基础上借鉴 **金蝶 K/3** 的模块体系，补齐了**财务总账、应收应付、生产制造、组织与权限**四大块，并实现 ERP 最核心的「业财一体化」：采购入库、销售出库、收付款核销、生产领料都会在同一事务里自动生成会计凭证，财务报表随之实时变化。

> 📖 **想理解代码为什么这么写**：见 [代码导读.md](代码导读.md)，包含金蝶模块对照、表关系、凭证模板、报表勾稽公式、逐类讲解与扩展点。源码内每个新增类都写了详细的中文注释。
>
> 🏗 **想了解 Redis / Spring Cloud 怎么接、能不能拆微服务**：见 [架构演进.md](架构演进.md)，包含性能与工程质量优化清单、三种运行模式、Spring Cloud 版本兼容与渐进式拆分路线图。
>
> 📝 **要把这个项目写进简历**：见 [简历写法.md](简历写法.md)，包含可直接复制的项目描述、技术栈写法、5 个 STAR 难点、面试高频追问与避坑清单。
>
> 工程化现状：**65 个 Java 源文件 / 23 张表 / 69 个 REST 接口 / 25 个页面 / 38 项自动化测试全部通过**，附 Swagger 在线文档、Excel 导出、AI 经营分析与容器化配置。

## 一、快速开始

### 方式一：双击启动（推荐）

双击项目根目录的 `start.bat`，首次运行会自动下载依赖并构建（约 2~3 分钟），之后每次都是秒级启动。

浏览器打开 **http://localhost:8080**

| 项目 | 说明 |
| --- | --- |
| 默认账号 | `admin` |
| 默认密码 | `admin123` |
| 数据库文件 | `D:\erp-data\erpdb.mv.db`（自动创建，重启数据不丢） |
| Maven 本地仓库 | `D:\maven\repository`（项目级配置，不占 C 盘） |

### 方式二：命令行

```bat
cd /d E:\erp
set JAVA_HOME=E:\develop\JDK
D:\maven\apache-maven-3.9.6\bin\mvn.cmd -gs .mvn\settings.xml -s .mvn\settings.xml clean package -DskipTests
java -jar target\erp-system.jar --server.port=8080
```

> 需要 JDK 17 及以上（本机 JDK 25 已验证）。改端口：设置环境变量 `ERP_PORT` 后双击 `start.bat`，或直接改 `--server.port`。
> 注意：某些终端会注入 `SERVER_PORT` 环境变量并覆盖配置文件，所以启动脚本统一用命令行参数固定端口。

### 方式三：Redis / Spring Cloud 增强模式（可选）

默认模式**不需要 Redis**。如果你要验证分布式会话、缓存共享或 Spring Cloud 组件，用另外两个脚本（本机已装 Redis 8.4.0，脚本会自动检测并在必要时拉起它）：

| 脚本 | 启动参数 | 效果 |
| --- | --- | --- |
| `start.bat` | 无 | 零配置单体，会话/缓存走进程内存 |
| `start-redis.bat` | `--spring.profiles.active=redis` | 会话与缓存迁到 Redis |
| `start-cloud.bat` | `--spring.profiles.active=redis,cloud` | 上面 + OpenFeign / Resilience4J 熔断 / Actuator |

启动后打开 **系统管理 → 系统运维**，能看到当前 Profile、会话存储类型、缓存实现和 Feign 熔断状态。完整说明与「为什么不直接拆微服务」见 [架构演进.md](架构演进.md)。

## 二、功能清单

| 模块域 | 功能 |
| --- | --- |
| 经营看板 | 本月销售额/采购额、库存总成本、订单数、商品数、往来单位数、低库存预警、待处理单据；近 7 日趋势图、热销 TOP5、最近单据 |
| 供应链 | 采购订单 / 销售订单（多明细、审核、取消、出入库）、多仓库实时库存、安全库存预警、库存盘点、库存流水 |
| **财务会计** | 会计科目维护、手工凭证录入（实时借贷平衡校验）、审核过账、业务自动凭证、科目余额表、明细分类账、**利润表 / 资产负债表**、会计期间期末结账 |
| **往来管理** | 应收单 / 应付单自动生成、收款 / 付款核销（不允许超额）、核销流水、**账龄分析**、逾期提示 |
| **生产制造** | 单层 BOM 维护（用量 + 损耗率）、生产任务单：审核 → 按 BOM 领料出库 → 完工入库，联动库存与总账 |
| **基础资料** | 商品 / 物料、供应商、客户、仓库、**部门、职员**；被引用的资料禁止删除 |
| **系统管理** | 登录鉴权（BCrypt 慢哈希、连续失败锁定）、**6 种角色的菜单权限 + 接口权限**、用户管理、操作日志 |
| **工程化能力** | Swagger 在线接口文档、统一分页、Jakarta Validation 声明式参数校验、**8 类数据 Excel 导出**、JUnit 5 + MockMvc 自动化测试、链路追踪、Docker / CI 配置 |
| **AI 经营分析** | 实时业务数据 → LLM 生成经营报告（总览/销售/库存/财务四类）；OpenAI 兼容协议可切换任意厂商；未配密钥自动降级本地规则引擎；结果缓存复用统一失效机制 |

### 业务流转

```
采购：草稿(DRAFT) --审核--> 已审核(CONFIRMED) --收货入库--> 已入库(RECEIVED)
                                    └── 任何未入库状态 --> 已取消(CANCELLED)
销售：草稿(DRAFT) --审核--> 已审核(CONFIRMED) --发货出库--> 已发货(SHIPPED)
                                    └── 任何未出库状态 --> 已取消(CANCELLED)

生产：草稿(DRAFT) --审核--> 已审核(CONFIRMED) --领料--> 已领料(ISSUED) --完工--> 已完工(FINISHED)
凭证：草稿(DRAFT) --审核过账--> 已过账(POSTED)   （业务单据生成的凭证直接是 POSTED）
往来：未结清(OPEN) --部分核销--> 部分结清(PARTIAL) --核销完--> 已结清(CLOSED)

入库 / 出库 / 盘点 / 核销 / 领料 都在同一个数据库事务里完成，以采购入库为例：
    更新 inventory 数量 → 写 stock_flow → 改订单状态 → 生成应付单 → 生成会计凭证
任一步失败全部回滚。出库使用带条件的 UPDATE（quantity >= 出库量）防止并发超卖。
```

### 业财一体化（借鉴金蝶「凭证模板」）

| 业务动作 | 自动生成的凭证 |
| --- | --- |
| 采购入库 | 借 1405 库存商品 / 贷 2202 应付账款，同时生成应付单 |
| 销售出库 | 借 1122 应收账款 / 贷 6001 主营业务收入；另结转 6401 主营业务成本 |
| 收付款核销 | 收款：借 1002 银行存款 / 贷 1122 应收账款；付款方向相反 |
| 生产领料 / 完工 | 借 5001 生产成本 / 贷 1405 库存商品；完工时反向结转 |

凭证模板集中在 `com.erp.common.BizPostingService`，改科目只动一处。详见 [代码导读.md](代码导读.md) 第 4、5 章。

## 三、技术栈与结构

```
Spring Boot 3.5.16 / web / jdbc / validation / actuator
H2 2.x（文件模式，MODE=MySQL）或 MySQL 8（可选 profile）
可选：Spring Data Redis 8.4.0（Lettuce）  /  Spring Cloud 2025.0.3 (Northfields)
       └─ OpenFeign + Resilience4J + Actuator + @RefreshScope
工程化：springdoc-openapi 2.8.17（Swagger UI）、Apache POI 5.4.1（xlsx）、
        spring-security-crypto（BCrypt）、JUnit 5 + MockMvc、
        LLM 接入（OpenAI 兼容协议，RestClient，无 SDK 绑定）
原生 HTML + CSS + JavaScript（无构建工具、无 CDN 依赖，可离线使用）
```

```
E:\erp
├─ pom.xml                      依赖与构建
├─ start.bat                    一键构建 + 启动（默认模式）
├─ start-redis.bat              Redis 模式
├─ start-cloud.bat              Redis + Spring Cloud 模式
├─ .mvn\settings.xml            项目级 Maven 镜像（阿里云 https）与本地仓库
├─ src\main\resources
│   ├─ application.yml          默认 H2 / 内存会话 / 内存缓存配置
│   ├─ application-redis.yml    Redis 会话与缓存
│   ├─ application-cloud.yml    Feign / 熔断 / Actuator
│   ├─ application-mysql.yml    切换 MySQL 的配置
│   ├─ schema.sql               23 张业务表（IF NOT EXISTS，老库升级安全）
│   └─ static\                  管理后台（index.html / app.css / app.js）
├─ src\test\java\com\erp        38 项自动化测试（纯单元 + MockMvc 集成）
├─ Dockerfile / docker-compose.yml / .github\workflows\ci.yml
├─ 代码导读.md                  金蝶模块对照 + 逐层代码讲解 + 扩展点
├─ 架构演进.md                  性能优化 / Redis / Spring Cloud / 微服务路线图
├─ 简历写法.md                  简历条目 / STAR 难点 / 面试追问 / 避坑清单
└─ src\main\java\com\erp
    ├─ ErpApplication.java      启动类
    ├─ common\                  统一响应 R、异常、鉴权拦截器、Db 封装、日志、单号生成、
    │                           RolePermission 权限、BizPostingService 凭证模板
    │                           SessionStore 会话策略（内存 / Redis）、TokenStore 门面、
    │                           CacheHub + CacheEvictInterceptor 统一缓存失效、
    │                           TraceFilter 链路追踪、PasswordService（BCrypt + 老哈希升级）、
    │                           LoginAttemptService 登录锁定、PageResult / PageSupport 分页
    ├─ excel\                   ExportService / ExportController（POI 导出 8 类数据）
    ├─ ai\                      AiAnalysisService（取数→prompt→LLM / 规则引擎降级）、
    │                           LlmClient（OpenAI 兼容协议 + 超时兜底）、AiProperties 配置
    ├─ config\                  DataInitializer（业务演示数据）
    │                           ModuleInitializer（新模块数据 + 历史单据补凭证）
    │                           CacheConfig（@EnableCaching + Redis JSON 序列化）
    │                           OpenApiConfig（Swagger 文档 + X-Token 认证方案）
    ├─ auth\                    登录 / 退出 / 当前用户（下发菜单权限）
    ├─ master\                  基础资料通用 CRUD（白名单防注入）
    ├─ purchase\ / sales\       采购 / 销售订单
    ├─ inventory\              库存查询 / 流水 / 盘点（StockService 为唯一变更入口）
    ├─ finance\                 凭证 / 科目 / 账簿 / 报表 / 期末结账
    ├─ settle\                  应收应付 / 收付款核销 / 账龄
    ├─ manufacture\             BOM / 生产任务单
    ├─ system\                  用户与角色管理
    ├─ cloud\                   OpenFeign 客户端 / 熔断探测 / 运维端点（cloud profile）
    └─ dashboard\              看板统计与日志查询
```

## 四、主要接口

所有 `/api/**` 接口（除登录）都需要请求头 `X-Token: <登录返回的 token>`；响应统一为 `{code, msg, data}`，`code=0` 表示成功。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/dashboard/summary` | 看板全部指标 |
| GET | `/api/master/options` | 仓库/供应商/客户/商品下拉数据 |
| GET/POST | `/api/master/{type}` | 列表 / 新增，type = product / supplier / customer / warehouse |
| GET/PUT/DELETE | `/api/master/{type}/{id}` | 详情 / 修改 / 删除 |
| GET/POST | `/api/purchase/orders` | 采购订单列表 / 新建 |
| GET | `/api/purchase/orders/{id}` | 订单详情（含明细） |
| POST | `/api/purchase/orders/{id}/confirm` · `/receive` · `/cancel` | 审核 / 入库 / 取消 |
| GET/POST | `/api/sales/orders` | 销售订单列表 / 新建 |
| POST | `/api/sales/orders/{id}/confirm` · `/ship` · `/cancel` | 审核 / 出库 / 取消 |
| GET | `/api/inventory/stock` | 实时库存（支持仓库、关键词、只看预警） |
| GET | `/api/inventory/flows` | 库存流水 |
| POST | `/api/inventory/adjust` | 库存盘点 |
| GET | `/api/logs` | 操作日志 |

**扩展模块接口**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/finance/subjects` | 会计科目列表 / 新增 |
| GET/POST | `/api/finance/vouchers` | 凭证列表 / 手工录入（草稿） |
| GET/DELETE | `/api/finance/vouchers/{id}` | 凭证详情（含分录） / 删除草稿 |
| POST | `/api/finance/vouchers/{id}/post` | 审核过账 |
| GET | `/api/finance/ledger/balance` | 科目余额表（按期间） |
| GET | `/api/finance/ledger/detail` | 明细账（科目 + 日期区间 + 滚动余额） |
| GET | `/api/finance/report/profit` · `/balance` | 利润表 / 资产负债表 |
| GET/POST | `/api/finance/periods` · `/periods/close` | 期间列表 / 结账与反结账 |
| GET | `/api/settle/arap` · `/summary` · `/aging` · `/records` | 往来单 / 汇总 / 账龄 / 核销记录 |
| POST | `/api/settle/settle` | 收付款核销（同步生成凭证） |
| GET/POST | `/api/mf/boms` | BOM 列表 / 新建；`GET /api/mf/boms/{id}` 查子件 |
| GET/POST | `/api/mf/orders` | 生产任务列表 / 新建 |
| POST | `/api/mf/orders/{id}/confirm` · `/issue` · `/finish` · `/cancel` | 审核 / 领料 / 完工 / 取消 |
| GET/POST | `/api/system/users` · `/api/system/roles` | 用户管理 / 角色字典 |
| PUT | `/api/system/users/{id}/role` · `/status` · `/password` | 改角色 / 启停 / 重置密码 |

**运维与可观测性接口**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ops/runtime` | 当前 Profile、Java 版本、会话存储类型、缓存实现、Cloud 状态 |
| GET | `/api/ops/cloud-probe` | 发起一次 OpenFeign 调用，返回熔断状态 / 是否降级（需 cloud profile） |
| GET | `/api/public/ping` | 免登录服务探测（供 Feign / 健康检查使用） |
| GET | `/actuator/health` · `/caches` · `/metrics` | 健康检查与运行指标 |

**接口文档与数据导出**

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| — | `/swagger-ui.html` | 在线接口页；右上角 Authorize 填入 token 即可调试所有接口 |
| — | `/v3/api-docs` | OpenAPI 3 JSON，可导入 Postman / Apifox |
| GET | `/api/export/types` | 可导出的数据集清单 |
| GET | `/api/export/{type}` | 下载 xlsx，type = purchase / sales / stock / flows / vouchers / arap / products / logs |
| GET | `/api/ai/status` | AI 引擎状态（大模型 / 本地规则）与可用分析类型 |
| GET | `/api/ai/analyze?type=` | 生成经营分析报告，type = overview / sales / inventory / finance |

> 导出类型走**白名单**（非法值直接拒绝，不拼进 SQL），单次上限 5 万行；Excel 带冻结首行、自动筛选、金额格式与中文状态翻译。前端在订单、库存、流水、凭证、往来、商品、日志页的工具栏自动挂「导出 Excel」按钮。

> **AI 经营分析**：菜单「经营概览 → AI 经营分析」。数据全部来自与看板同源的实时汇总 SQL，LLM 只做解读、不接触数据库；接入走 OpenAI 兼容协议（DeepSeek / 通义 / Kimi / OpenAI 改 `ERP_AI_BASE_URL` + `ERP_AI_MODEL` 即可切换），密钥只走环境变量 `ERP_AI_API_KEY`。**未配密钥或调用失败时自动降级到内置规则引擎**，返回体 `source` 字段如实标注引擎来源；结果挂进看板缓存区，任何业务写操作后自动重新生成，不重复消耗 token。

> **分页**：订单、库存、流水、凭证、用户、日志列表都支持可选 `page` / `size`（默认 20，上限 200）。不传时返回原来的裸数组（兼容既有调用），传了返回 `{list, total, page, size, pages}`。日志走数据库分页（`COUNT(*)` + `LIMIT/OFFSET`），其余因需先聚合明细走内存切页。

> 基础资料 `{type}` 新增支持 `department` / `employee`。除登录外的接口都会按角色做接口级鉴权，非管理员对基础资料只有读权限。
> 所有 API 响应都会带上 `X-Trace-Id` 响应头，日志里也打印同一个 ID，便于串联一次请求的完整链路。

H2 控制台默认开启：`http://localhost:8080/h2-console`（JDBC URL 见 application.yml，用户 `sa`，密码空）。

## 五、切换到 MySQL（可选）

1. 建库：`CREATE DATABASE erp DEFAULT CHARACTER SET utf8mb4;`
2. 设置环境变量后启动：

```bat
set ERP_DB_USER=root
set ERP_DB_PASSWORD=你的密码
java -jar target\erp-system.jar --spring.profiles.active=mysql --server.port=8080
```

`schema.sql` 使用 MySQL 兼容语法，启动时自动建表；`DataInitializer` 自动写入默认账号与演示数据。

## 六、数据与备份

- **备份**：停止服务后复制 `D:\erp-data\erpdb.mv.db` 即可；恢复时放回原路径。
- **重置演示数据**：删除该文件后重新启动，系统会重新建表并载入演示数据。
- **自定义数据目录**：设置环境变量 `ERP_DB_PATH`，例如 `set ERP_DB_PATH=E:\mydata\erpdb`。
- 演示数据在**首次启动且对应表为空时**写入，之后不会覆盖你的数据：8 个商品 + 2 种原材料物料、3 家供应商、3 家客户、2 个仓库、近一周历史单据、13 个会计科目、6 个部门 / 6 名职员、1 个示例 BOM、以及为历史单据补齐的凭证与往来单。
- 新模块数据由 `ModuleInitializer` **按数据集独立判空**初始化，所以老数据库直接升级也能获得科目表、演示 BOM 和期初凭证，原有业务数据完整保留。
- 内置角色：`ADMIN` / `PURCHASE` / `SALES` / `WAREHOUSE` / `FINANCE` / `PRODUCTION`，可在「用户与权限」页面新增账号体验不同菜单。

## 七、常见问题

- **端口被占用**：改 `application.yml` 的 `server.port`，或在启动时加 `--server.port=8081`。
- **首次构建慢**：Maven 依赖要下载到 `D:\maven\repository`，之后复用缓存。
- **忘记密码**：密码用 **BCrypt** 慢哈希存储，无法在 H2 控制台手写哈希还原。能登录时走「用户与权限 → 重置密码」或 `PUT /api/system/users/{id}/password`；完全无法登录时删除 `D:\erp-data\erpdb.mv.db` 重新初始化。老版本 SHA-256 哈希的账号**首次登录成功后会自动升级为 BCrypt**，无需改密。
- **中文乱码**：`start.bat` 已执行 `chcp 65001`；接口统一 UTF-8，一般无需额外处理。
- **没装 Redis 会不会启动失败**：不会。默认模式完全不连接 Redis；只有 `start-redis.bat` / `start-cloud.bat` 才需要。Redis 地址可用 `ERP_REDIS_HOST` / `ERP_REDIS_PORT` / `ERP_REDIS_PASSWORD` 覆盖。
- **改了代码但页面没变化**：静态资源带版本号（当前 `app.js?v=5`），重新打包后刷新即可；若仍异常可按 Ctrl+F5 强刷。另外**打包前要先停掉正在运行的服务**，否则 Windows 文件锁会让 jar 写入失败。
- **想验证缓存是否生效**：系统运维页看缓存实现；连续调用 `/api/dashboard/summary` 观察日志中的 SQL 次数变化，任意一次成功的写操作后会重新回源。

## 八、测试与部署

### 跑测试

```bat
cd /d E:\erp
mvn -B test
```

`src/test/resources/application.properties` 把数据源换成 **H2 内存库**，测试不会碰 `D:\erp-data` 下的正式数据，AI 固定走本地规则引擎保证确定性。当前 **38 项测试全部通过**：

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `CoreUnitTest` | 12 | 分页边界、`ArrayList$SubList` 序列化回归、BCrypt 与老 SHA-256 兼容、`BigDecimal`、AI 规则引擎（三段结构 / 风险触发 / 无误报 / 零销售建议） |
| `ErpIntegrationTest` | 26 | 完整 Spring 容器 + MockMvc：登录/锁定/401/403、@Valid 校验、下单→入库→生成凭证与应付单、超卖回滚、借贷平衡、已过账不可删、报表平衡、分页、Excel 导出与导出数据权限、AI 接口（401 / 降级 / 非法类型 / 全角色可用）、traceId 透传、Swagger 文档 |

### 容器化（本机未安装 Docker，配置已提供）

```bash
docker compose up -d --build     # 应用 + Redis，自动启用 redis,cloud profile
docker compose logs -f erp
```

`Dockerfile` 用多阶段构建（Maven 编译 → JRE 运行），数据挂在 `erp-data` 卷；`docker-compose.yml` 带 Redis 健康检查与应用就绪探针。`.github/workflows/ci.yml` 在 push / PR 时自动跑 `mvn test` 与打包并上传测试报告与 jar。

> 说明：本机没有 Docker 与 GitHub 远端，上述两项**只提供了可执行的配置，未在本机实际构建验证**；简历上请如实写「编写容器化与 CI 配置」，不要写「已落地流水线」。

### 演示与简历

按 [简历写法.md](简历写法.md) 第 6 节的 3 分钟话术走一遍：看板 → Swagger → 新建销售单发货 → 自动生成收入/成本凭证 → 资产负债表差额仍为 0 → 导出 Excel。这套闭环是面试时最有说服力的部分。
