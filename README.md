# mall-assistant

Java Doc「AI」模块的配套练习项目：给 [mall-evolution](https://github.com/Clarence404/mall-evolution) 里的示例商城做一个智能客服，一章一章自己动手写出来。

**版本基线**：JDK 21、Spring Boot 4.1.2、Spring AI 2.0.2（MCP Java SDK 2.1.0）、Micrometer 1.17（Prometheus、Brave 链路 ID）、Testcontainers 2.0.5、Ollama 0.40（默认对话模型 `qwen2.5:0.5b`，向量模型 `bge-m3`）、pgvector 0.8.1（PostgreSQL 17，可选）、Redis 8.10（Spring Data Redis + Lettuce，可选）、Prometheus 3.5.0 与 Grafana 11.6.1（监控，可选）。

[![verify](https://github.com/Clarence404/mall-assistant/actions/workflows/verify.yml/badge.svg)](https://github.com/Clarence404/mall-assistant/actions/workflows/verify.yml) [测试报告](https://clarence404.github.io/mall-assistant/)

## 怎么用这个仓库

每一章有两个标签：

| 标签 | 内容 | `./mvnw verify` |
|------|------|-----------------|
| `chNN-start` | 上一章的参考答案 + 本章骨架：要你写的地方留了 `TODO chNN`，验收测试已经写好 | 本章的验收测试失败（预期） |
| `chNN-done` | 本章参考答案 | 全部通过 |

`main` 指向最新一章的 done。练习方式：

```bash
git clone https://github.com/Clarence404/mall-assistant.git
cd mall-assistant
git switch -c my-ch01 ch01-start     # 从起点拉自己的分支
# 按 docs/ch01.md 补全 TODO，直到 ./mvnw verify 通过
git diff ch01-done -- src/main       # 卡住了再对照参考答案
```

每章的说明在 `docs/chNN.md`：目标、背景阅读、分级提示（思路 → 关键 API → 参考答案）、验收标准、本地运行。

## 章节路线

| 章 | 主题 | 内容 | 对应文章 | 状态 |
|----|------|------|---------|------|
| 1 | 接入模型与流式对话 | ChatClient + 系统提示词、按 profile 切换 Ollama / OpenAI 兼容接口、阻塞与 SSE 流式接口、超时与友好报错 | [Spring AI](https://clarence404.github.io/java-doc/ai/2_frameworks/0_spring_ai.html)、[Ollama](https://clarence404.github.io/java-doc/ai/3_integration/0_ollama.html) | [`ch01-start`](https://github.com/Clarence404/mall-assistant/tree/ch01-start) / [`ch01-done`](https://github.com/Clarence404/mall-assistant/tree/ch01-done) |
| 2 | Prompt 与结构化输出 | 带变量、分版本的提示词模板，意图识别输出 Java 对象（原生结构化输出 + 重试兜底），超出范围直接拒答，录制回放测试 | [Prompt 工程](https://clarence404.github.io/java-doc/ai/1_concepts/1_prompt.html) | [`ch02-start`](https://github.com/Clarence404/mall-assistant/tree/ch02-start) / [`ch02-done`](https://github.com/Clarence404/mall-assistant/tree/ch02-done) |
| 3 | 工具调用 | `@Tool` 查订单、查商品（调 mall-evolution 网关），原生工具调用 + 按意图直接查询，令牌只走 ToolContext，查不到返回结构化结果、不编造，工具调用记录与录制回放；`main` 上补充了列出我的订单（`listMyOrders`，见 [`docs/ch03.md`](docs/ch03.md#补充列出我的订单)） | [Function Calling](https://clarence404.github.io/java-doc/ai/1_concepts/2_function_calling.html)、[Spring AI](https://clarence404.github.io/java-doc/ai/2_frameworks/0_spring_ai.html) | [`ch03-start`](https://github.com/Clarence404/mall-assistant/tree/ch03-start) / [`ch03-done`](https://github.com/Clarence404/mall-assistant/tree/ch03-done) |
| 4 | 知识入库 | 6 篇商城规则文档按标题与长度切块（带重叠）、带元数据向量化，按内容哈希增量入库；向量库默认内存（零依赖）、可选 pgvector（余弦距离 + HNSW），向量模型 bge-m3 / nomic-embed-text 按模型分表；入库、相似度检索、概况三个调试接口 | [Embedding](https://clarence404.github.io/java-doc/ai/4_core_tech/0_embedding.html)、[向量数据库](https://clarence404.github.io/java-doc/ai/4_core_tech/1_vector_db.html)、[Spring AI](https://clarence404.github.io/java-doc/ai/2_frameworks/0_spring_ai.html) | [`ch04-start`](https://github.com/Clarence404/mall-assistant/tree/ch04-start) / [`ch04-done`](https://github.com/Clarence404/mall-assistant/tree/ch04-done) |
| 5 | RAG | 规则类问题（新增意图 POLICY 与售后）先检索再回答：向量检索 + 关键词检索（BM25）用 RRF 融合，相似度门槛、去重与上下文预算，编号资料拼进提示词（资料是数据不是指令），检索不到不调模型、直接说没找到；`sources` 字段与 `source` 事件返回引用来源，引用编号事后校验；hit@k 检索评测与 RAG 回答录制回放 | [RAG](https://clarence404.github.io/java-doc/ai/4_core_tech/2_rag.html)、[知识入库](https://clarence404.github.io/java-doc/ai/7_practice/4_knowledge_ingest.html)、[Spring AI](https://clarence404.github.io/java-doc/ai/2_frameworks/0_spring_ai.html) | [`ch05-start`](https://github.com/Clarence404/mall-assistant/tree/ch05-start) / [`ch05-done`](https://github.com/Clarence404/mall-assistant/tree/ch05-done) |
| 6 | 多轮对话与记忆 | 页面生成会话 ID，Spring AI `ChatMemory`（内存 / JDBC 存进 PostgreSQL），按轮数和字数裁剪历史（可选摘要记忆），只存原话与最终回答；追问先改写成独立问题再识别意图和检索（结果校验防跑题），订单号核对与沿用；会话绑定商城用户、别人拿到 ID 也读不到；查看 / 清空接口、刷新恢复历史，流式回答完才写记忆、同一会话并发 409；RAG 门槛按向量模型分别配；多轮录制回放 | [Spring AI](https://clarence404.github.io/java-doc/ai/2_frameworks/0_spring_ai.html)、[RAG 实战](https://clarence404.github.io/java-doc/ai/7_practice/5_rag.html)、[Prompt 工程](https://clarence404.github.io/java-doc/ai/1_concepts/1_prompt.html) | [`ch06-start`](https://github.com/Clarence404/mall-assistant/tree/ch06-start) / [`ch06-done`](https://github.com/Clarence404/mall-assistant/tree/ch06-done) |
| 7 | Agent | 售后 Agent：自己写的循环（每一步用结构化输出给出「下一步动作」，步数、重复调用、总时长三道刹车），5 个只读工具直接执行（查订单、列订单、查支付单、查商品、查售后规则复用第 5 章检索），3 个写操作（取消待支付订单、改收货地址、提交售后申请）只生成确认卡片；用户在页面上点「确认」才执行，确认绑定用户与会话、5 分钟过期、幂等、参数不可改，聊天里打「确认」不算，结果写回会话记忆；售后申请单 memory / jdbc；`step` / `confirm` 事件与调试面板「Agent 步骤」；Spring AI 原生工具调用循环作对比；Agent 决策录制回放 | [AI Agent](https://clarence404.github.io/java-doc/ai/5_advanced/0_agent.html)、[Function Calling](https://clarence404.github.io/java-doc/ai/1_concepts/2_function_calling.html)、[多轮对话与记忆](https://clarence404.github.io/java-doc/ai/7_practice/6_memory.html) | [`ch07-start`](https://github.com/Clarence404/mall-assistant/tree/ch07-start) / [`ch07-done`](https://github.com/Clarence404/mall-assistant/tree/ch07-done) |
| 8 | MCP | 把商城能力暴露成 MCP Server（Spring AI MCP Server，WebMVC + Streamable HTTP，和 8088 同一进程，端点 `/mcp`）：`@McpTool` 暴露查知识库、查商品（不用登录）和列订单、查订单（要登录）；取消订单、改地址、售后只做「提议」——复用第 7 章的提议关与人工确认，返回确认 ID 和确认链接，用户本人在页面上点确认才执行；商城令牌放在 HTTP 头（不做工具参数），按第 6 章问商城确认身份；结果截断、每分钟次数上限、`Origin` 校验；6 篇规则文档作为 MCP 资源；客户端一侧可开关的 MCP Client（白名单、改名、说明与结果截短、不把 ToolContext 发出去）；真 MCP 客户端的协议级测试与回环测试 | [MCP 协议](https://clarence404.github.io/java-doc/ai/5_advanced/1_mcp.html)、[AI Agent](https://clarence404.github.io/java-doc/ai/5_advanced/0_agent.html)、[Agent 实战](https://clarence404.github.io/java-doc/ai/7_practice/7_agent.html) | [`ch08-start`](https://github.com/Clarence404/mall-assistant/tree/ch08-start) / [`ch08-done`](https://github.com/Clarence404/mall-assistant/tree/ch08-done) |
| 9 | 评估与上线 | 回归评测集（58 条 JSONL，覆盖意图、工具、RAG、多轮、Agent、注入、拒答，内容哈希做版本）与评测运行器（端到端判可判的、出报告、和基线比，退化超阈值即失败，可选 LLM 裁判）；真实模型评测录制 + CI 回放门禁；Token 用量（模型报告优先、取不到估算并标注，按用户 / 会话 / 意图记账，`usage` 字段与调试面板，每日预算 429）；对话接口按用户 / IP 限流、并发上限、模型熔断（`MODEL_CIRCUIT_OPEN`）与降级（规则类问题给原文）；Micrometer 指标走 `/actuator/prometheus`、traceId 进日志与响应头、结构化日志，Grafana 面板与告警规则；知识库资料与外部 MCP 工具的提示注入过滤；上线检查清单 | [LLM 应用评估与上线](https://clarence404.github.io/java-doc/ai/5_advanced/3_llm_eval.html)、[限流](https://clarence404.github.io/java-doc/high-avail/7_rate_limiting.html)、[熔断](https://clarence404.github.io/java-doc/high-avail/5_circuit_breaking.html)、[指标](https://clarence404.github.io/java-doc/observability/2_metrics.html) | [`ch09-start`](https://github.com/Clarence404/mall-assistant/tree/ch09-start) / [`ch09-done`](https://github.com/Clarence404/mall-assistant/tree/ch09-done) |
| 10 | 生产化：共享状态与监控落地 | 只在一个进程里有效的状态抽成接口（对话与 MCP 的限流、并发名额、Token 账、待确认操作、会话标记、归属缓存），内存实现默认，`assistant.state.store=redis` 时多个实例共享：Lua 脚本的滑动窗口与分布式名额、一轮记账、`SET NX` 抢占保证确认只执行一次、`SET NX PX` + 比较再删除的分布式锁；Redis 挂了限流与预算按策略放行或 503、确认一律拒绝、冷却 5 秒；熔断器每实例一份；同一套契约测试跑两种实现，两个客户端证明共享生效；Prometheus + Grafana 的 compose（file_sd 抓管理端口、provisioning 自动加载面板）、面板 PromQL 修正、告警单元测试与 CI 里的 promtool | [分布式锁](https://clarence404.github.io/java-doc/distributed/3_lock.html)、[Redis 应用场景](https://clarence404.github.io/java-doc/cache/4_redis_scenario.html)、[限流](https://clarence404.github.io/java-doc/high-avail/7_rate_limiting.html)、[告警](https://clarence404.github.io/java-doc/observability/4_alerting.html) | [`ch10-start`](https://github.com/Clarence404/mall-assistant/tree/ch10-start) / [`ch10-done`](https://github.com/Clarence404/mall-assistant/tree/ch10-done) |

## 本地运行

需要 JDK 21 和一个模型服务。默认用本机的 [Ollama](https://ollama.com/download)，**不需要 Docker**。

1. 安装 Ollama：Windows 下载 `OllamaSetup.exe` 直接安装（装在用户目录，不需要管理员权限），装好后它在后台监听 `http://localhost:11434`
2. 拉取模型（约 400 MB）：`ollama pull qwen2.5:0.5b`，可用 `ollama run qwen2.5:0.5b` 先在命令行试一句
3. 启动：`./mvnw spring-boot:run`（Windows PowerShell 用 `.\mvnw.cmd spring-boot:run`），默认 profile 是 `ollama`
4. 打开 <http://localhost:8088/>，在页面上提问；右上角可切换流式 / 阻塞，底部「调试面板」显示耗时、错误码、识别出的意图、工具调用与引用来源

第 3 章起要查订单、查商品，还需要先启动 [mall-evolution](https://github.com/Clarence404/mall-evolution)（四个服务，网关 9000，见它的 README「本地运行」），再在页面右上角用演示账号 `13800000000` / `mall123` 登录；没启动它时对话照常可用，只是工具会返回「商城服务暂时不可用」。网关地址用 `assistant.mall.base-url`（环境变量 `MALL_API_BASE_URL`）修改。

第 4 章起还要一个向量模型（把知识文档变成向量）：`ollama pull bge-m3`（约 1.2 GB，1024 维，中文好）；想要更小的可以用 `nomic-embed-text`（约 270 MB，768 维，以英文为主，中文检索明显差一些），设置环境变量 `OLLAMA_EMBEDDING_MODEL=nomic-embed-text`。启动时会自动入库（日志「知识入库完成」），向量模型没 pull 也能启动，只是知识库不可用；页面最下面的「知识库」可以入库、看概况、试检索。向量库默认在内存里（存成 `target/knowledge/*.json`），**不需要 Docker 和数据库**；想用 pgvector 见 [`docs/ch04.md`](docs/ch04.md#本地运行)。换向量模型后要重新入库（不同模型的向量不能混用，本项目按模型分表 / 分文件，切换后自动入库到新表）。

换模型：设置环境变量 `OLLAMA_CHAT_MODEL`（如 `qwen2.5:1.5b`，约 1 GB），先 `ollama pull` 好。0.5b 能跑通全部流程，但第 2 章起的意图识别只分对一半左右；1.5b 明显更准（第 5 章重录的 16 条消息分对 14 条）。

第 5 章起，规则类问题（意图 `POLICY` 与 `AFTER_SALES`）走 RAG：先在第 4 章的知识库里检索，再让模型只依据检索到的资料回答并标出 [1]、[2]；检索不到时不调模型，直接回「没有找到相关规定」。所以**向量模型要先 pull 好**（同第 4 章），否则规则类问题只会回「规则库暂时查不到」。配置在 `application.yml` 的 `assistant.rag.*`：混合检索开关 `hybrid`、相似度门槛 `min-score`（0.6 是按 bge-m3 实测定的，换 nomic-embed-text 要重新定）、交给模型几块 `top-k`、资料字数预算 `context-budget-chars`、问题改写 `rewrite-query`（默认关）等，见 [`docs/ch05.md`](docs/ch05.md)。

第 6 章起是多轮对话：页面生成会话 ID（存在浏览器 localStorage，输入框上方的「新对话」换一个、「清空」删掉服务端的记录），提问时放在请求体的 `conversationId` 里；不带它就和第 5 章一样单轮。配置在 `application.yml` 的 `assistant.memory.*`：记忆存在哪 `store`（默认 `memory`，重启就没了；`jdbc` 存进 PostgreSQL，默认和第 4 章 pgvector 同一个库，在 `config/application-local.yml` 里配好 pgvector 的地址和账号后加 `assistant.memory.store: jdbc` 即可）、会话过期 `ttl`（30 分钟）、每会话最多存几条 `max-messages`、每次带几轮 `window-turns` 和多少字 `budget-chars`、追问改写 `condense.*`、摘要记忆 `summary.*`（默认关）。登录用户的会话绑定到商城用户（问商城 `/api/users/me`），所以查订单的多轮对话需要商城在线。RAG 的相似度门槛第 6 章起按向量模型分别配（`assistant.rag.model-profiles`：bge-m3 0.6、nomic-embed-text 0.72），见 [`docs/ch06.md`](docs/ch06.md)。

第 7 章起是售后 Agent：登录后说「订单 … 买错了，帮我取消」「订单 … 地址填错了，改成 …」「订单 … 东西坏了要退款」，Agent 会一步步查订单、查规则，最后提出一个写操作，页面出现一张确认卡片，**点「确认」才会真正执行**（取消、改地址调商城网关；退款只在本地记一张「待人工审核」的售后申请单，不动钱）。每一步显示在调试面板「Agent 步骤」里。配置在 `application.yml` 的 `assistant.agent.*`：循环方式 `mode`（`loop` 自己写的循环，默认；`native` Spring AI 原生工具调用，命令行模型不支持）、步数上限 `max-steps`（6）、同一调用的次数上限 `max-same-calls`（2）、总时长 `timeout`（120 秒）、每一步的输出上限 `max-output-tokens`（400）、确认卡片的有效期 `confirmation-ttl`（5 分钟）；售后申请单存在哪是 `assistant.after-sales.store`（默认 `memory`，`jdbc` 存进 PostgreSQL 的 `after_sales_application` 表，连接默认沿用第 6 章记忆的），见 [`docs/ch07.md`](docs/ch07.md)。没登录时办不了事，售后类问题照旧走 RAG 回答规则并提示登录。

第 8 章起本应用同时是一个 MCP Server：启动后 MCP 客户端连 `http://localhost:8088/mcp`（Streamable HTTP）就能列出 8 个工具。查知识库、查商品不用登录；查订单要在 MCP 客户端的请求头里带 `Authorization: Bearer <商城令牌>`（令牌不做成工具参数，对面的模型看不到它）；取消订单、改地址、售后申请只能「提议」，返回确认 ID 和确认链接 `http://localhost:8088/?confirm=<ID>`，用户在页面上登录同一个账号点「确认」才执行，`getConfirmation` 查结果。用 MCP Inspector 试：`npx @modelcontextprotocol/inspector --cli http://localhost:8088/mcp --transport http --method tools/list`（带令牌加 `--header "Authorization: Bearer <令牌>"`；订单号这种一串数字的参数要写成 `--tool-arg 'orderNo="…"'`，否则会被当成数字、过不了参数校验）。配置在 `application.yml` 的 `spring.ai.mcp.server.*`（端点、协议，**`protocol: STREAMABLE` 必须写**，不写会按 SSE 装配）与 `assistant.mcp.*`：确认页面地址 `confirm-url`、结果长度 `max-result-chars`（2000）、每分钟次数 `calls-per-minute`（30）、允许的浏览器来源 `allowed-origins`、会话空闲清理 `session-idle-timeout`（10 分钟）。客户端一侧默认关闭（`spring.ai.mcp.client.enabled=false`）；打开后连 `spring.ai.mcp.client.streamable-http.connections.*` 里的外部 MCP Server，只有 `assistant.mcp.client.allowed-tools` 白名单里的工具（加 `ext_` 前缀）会交给对话模型，`GET /api/mcp/client/tools` 可以看连上了哪些，见 [`docs/ch08.md`](docs/ch08.md)。

第 9 章起对话接口有上线保护和监控：每个调用方（登录用户按商城用户，匿名按 IP）每分钟最多问 20 次、全局同时最多处理 8 轮、每天最多 10 万 Token（`assistant.guard.*`、`assistant.usage.*`，超了 429 / 503，错误码 `RATE_LIMITED` / `TOO_BUSY` / `TOKEN_BUDGET_EXCEEDED`）；模型连续失败 5 次熔断 30 秒（`MODEL_CIRCUIT_OPEN`），规则类问题调不了模型时直接给检索到的原文（`assistant.guard.degrade`）；知识库资料和外部 MCP 工具的输出先过提示注入过滤（`assistant.guard.injection`）。回答里带 `usage`（这一轮每次模型调用的 Token，流式在 `done` 事件里），调试面板有「Token 用量」一栏和 traceId；`GET /api/usage/me` 查自己今天的用量，`/actuator/tokens` 看全部，`/actuator/prometheus` 是 Prometheus 指标（生产上这两个 Actuator 端点不要对外，用 `management.server.port` 放到内网端口）；结构化日志设 `LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`。Grafana 面板与告警规则在 `monitoring/`（第 10 章起可以用 `monitoring/docker-compose.yml` 跑起来）。回归评测：`EVAL_OLLAMA_URL=http://localhost:11434 ./mvnw verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=EvalIT`，见 [`docs/ch09.md`](docs/ch09.md)。

第 10 章起限流、并发名额、Token 账、待确认操作、「会话正在回答」标记和归属缓存可以放进 Redis，多个实例共享：`assistant.state.store`（默认 `memory`，和第 9 章一样；`redis` 时在 `config/application-local.yml` 里写 `assistant.state.redis.url: redis://:<口令>@<主机>:6379/<库号>`，或环境变量 `ASSISTANT_STATE_STORE` / `REDIS_URL`）、键前缀 `key-prefix`（`mall-assistant:`）、Redis 出错时限流与预算的策略 `on-redis-error`（`allow` 放行，默认；`reject` 返回 503 `STATE_UNAVAILABLE`；待确认操作一律 503 `CONFIRMATION_UNAVAILABLE`）、命令超时与冷却、名额和会话标记的租期 `lease`（5 分钟）。监控：`monitoring/` 下 `cp .env.example .env` 填 Grafana 管理员口令，改 `prometheus/targets/default.json` 写上应用的管理端口，`docker compose up -d`；Prometheus 9090、Grafana 3000，见 [`docs/ch10.md`](docs/ch10.md)。

提示词版本：`assistant.prompt.version`（默认 `v3`，可改回 `v2` / `v1` 对比）；商城名、服务范围等模板变量在 `application.yml` 的 `assistant.prompt.*`。工具调用的两个开关在 `assistant.tools.*`（原生工具调用、按意图直接查询，见 [`docs/ch03.md`](docs/ch03.md)）。

### 端口规划

和 [mall-evolution](https://github.com/Clarence404/mall-evolution) 同时在本机运行时，端口互不冲突：

| 项目 | 服务 | 端口 |
|------|------|------|
| mall-assistant | 智能客服（本仓库） | 8088，可用环境变量 `SERVER_PORT` 修改 |
| mall-evolution | 订单与商品 mall-app | 8080 |
| mall-evolution | 库存服务 / 支付服务 | 8082 / 8083 |
| mall-evolution | 网关（对外入口，第 3 章起智能客服调用它，`MALL_API_BASE_URL` 可改） | 9000 |
| 模型 | Ollama | 11434 |
| 向量库（第 4 章，可选） | PostgreSQL + pgvector（`store=pgvector` 时，`PGVECTOR_URL` 可改） | 5432 |
| 共享状态（第 10 章，可选） | Redis（`assistant.state.store=redis` 时，`REDIS_URL` 可改；可以和 mall-evolution 共用一台，换一个库号） | 6379 |
| 监控（第 10 章，可选） | Prometheus / Grafana（`monitoring/docker-compose.yml`） | 9090 / 3000 |

### 在 IntelliJ IDEA 里运行

运行配置的 VM 参数里带了 `--enable-native-access=ALL-UNNAMED`：用 JDK 24 及以上运行时，依赖里的 Netty 等库会加载本地库，JVM 会打印「A restricted method in java.lang.System has been called」的警告，加上这个参数就不再提示；JDK 21 也认识它，没有副作用。

仓库带了共享运行配置（`.run/`），用 IDEA 打开项目、等 Maven 导入完成（项目 SDK 选 JDK 21）后，右上角直接选：

| 运行配置 | 作用 |
|---------|------|
| 智能客服（本地 Ollama） | profile `ollama,local`，可在配置里改 `OLLAMA_CHAT_MODEL` 与向量模型 `OLLAMA_EMBEDDING_MODEL`（`bge-m3` / `nomic-embed-text`） |
| 智能客服（OpenAI 兼容接口） | profile `openai,local`，环境变量 `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL` 留空，填上才能启动 |
| 智能客服（Claude CLI） | profile `claude-cli,local`，用本机已登录的 Claude Code 当模型，见下文「用命令行工具当模型」 |
| 智能客服（Codex CLI） | profile `codex-cli,local`，用本机已登录的 Codex CLI 当模型 |
| 测试：mvn verify | 跑全部检查与测试，等同 `./mvnw verify` |

注意：这些配置存放在入库的 `.run/*.run.xml` 里，在里面填的值会出现在 `git diff` 中。密钥和内部地址不要填进运行配置，改用下面的本地配置文件。

第 4 章：知识库用 pgvector 时，把 `assistant.knowledge.store: pgvector` 和连接信息写进 `config/application-local.yml`（见 `config/application-local.example.yml` 最后一段），不要写进运行配置；两个命令行模型的运行配置也列了 `OLLAMA_EMBEDDING_MODEL`（命令行工具没有向量模型，知识库用 Ollama 的）。

第 10 章：共享状态放进 Redis 写进 `config/application-local.yml`（见 `config/application-local.example.yml` 最后一段），不要写进运行配置；想在本机起第二个实例对比，用命令行 `java -jar` 换端口（`--server.port=8091 --management.server.port=9191`），8088 留给 IDEA 里的这个。

第 9 章：上线保护、Token、监控都随应用一起启动，不用另外的运行配置；本机调试嫌限流碍事，在 `config/application-local.yml` 里放宽（见 `config/application-local.example.yml` 最后一段），想看熔断和降级就在 Program arguments 里加 `--spring.ai.ollama.chat.model=一个不存在的模型名`。

第 8 章：MCP Server 随应用一起启动，不用另外的运行配置；想关掉用 `--spring.ai.mcp.server.enabled=false`。打开客户端示例（连另一个 MCP Server）写进 `config/application-local.yml`：`spring.ai.mcp.client.enabled: true`、`spring.ai.mcp.client.streamable-http.connections.<名字>.url` 和 `assistant.mcp.client.allowed-tools`（见 `application.yml` 里注释掉的例子）。

第 7 章：售后申请单存进 PostgreSQL 写进 `config/application-local.yml`（`assistant.after-sales.store: jdbc`，连接默认沿用记忆的）；对比方案 A 用 `--assistant.agent.mode=native`，关掉 Agent 用 `--assistant.agent.enabled=false`。

第 6 章：记忆存进 PostgreSQL 同样写进 `config/application-local.yml`（`assistant.memory.store: jdbc`，连接默认沿用 pgvector 的）；关掉追问改写对比效果用 `--assistant.memory.condense.enabled=false`，打开摘要记忆用 `--assistant.memory.summary.enabled=true`。

第 5 章：想对比只用向量检索的效果，在 Program arguments 里加 `--assistant.rag.hybrid=false`；整个关掉 RAG 用 `--assistant.rag.enabled=false`。

第 3 章：先在 mall-evolution 的项目里用它自带的运行配置「全部服务（先起中间件）」启动四个服务（两个项目可以同时开在两个 IDEA 窗口里），再启动智能客服。想临时关掉某种工具用法，在运行配置的 Program arguments 里加 `--assistant.tools.direct-lookup=false` 或 `--assistant.tools.native-calling=false`。

### 指向远程 Ollama 或 OpenAI 兼容接口（本地配置，不入库）

所有外部地址都是带默认值的占位符（如 `${OLLAMA_BASE_URL:http://localhost:11434}`），默认指向本机。要连共享开发机上的 Ollama、或者用云端 OpenAI 兼容接口时：

```bash
cp config/application-local.example.yml config/application-local.yml   # 已被 .gitignore 忽略
# 编辑 config/application-local.yml：填远程 Ollama 的 base-url，或取消注释 openai 一段填 base-url / api-key / 模型名
```

以 `local` profile 启动时生效：IDEA 的两个运行配置已经带上 `local`；命令行用 `./mvnw spring-boot:run -Dspring-boot.run.profiles=ollama,local`（或 `openai,local`）。Spring Boot 会自动加载工作目录下 `./config/` 里的 `application-{profile}.yml`，所以要在仓库根目录启动；没有这个文件也能正常启动。

不想建文件时，`openai` profile 也可以只用环境变量：`OPENAI_BASE_URL`（含 `/v1`，如 `https://api.deepseek.com/v1`）、`OPENAI_API_KEY`、`OPENAI_MODEL`，然后 `--spring.profiles.active=openai`。

### 用命令行工具当模型（Claude Code / Codex）

手头没有 Ollama、也没有 API key，但本机装了 AI 编程命令行工具并已登录时，可以直接拿它当对话模型：`CliChatModel` 每次调用起一个进程，内容走 stdin，结果从 stdout 的 JSON 里解析，业务代码不变。只用于本机学习，**不要用于生产**。

| profile | 前提 | 模型 |
|---------|------|------|
| `claude-cli` | [Claude Code](https://docs.claude.com/en/docs/claude-code) 已安装并登录：`claude -p "你好"` 能正常回答 | `CLAUDE_CLI_MODEL`，默认 `haiku` |
| `codex-cli` | [Codex CLI](https://github.com/openai/codex) 已安装并登录：`codex login status` 显示已登录 | `CODEX_CLI_MODEL`，默认用 codex 自己的默认模型 |

启动：IDEA 选「智能客服（Claude CLI）」/「智能客服（Codex CLI）」，或命令行 `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=claude-cli"`。命令默认在 PATH 里找；Windows 下 npm 装的是 `claude.cmd` / `codex.cmd` 包装，程序会从里面读出脚本路径，直接执行 `node.exe <脚本>`，不经过 cmd.exe（否则系统提示词里的换行、Schema 里的引号会被 cmd.exe 再解释一遍）。找不到时用 `CLAUDE_CLI_COMMAND` / `CODEX_CLI_COMMAND` 指定完整路径。其余配置在 `application-claude-cli.yml` / `application-codex-cli.yml` 的 `assistant.cli.*`：超时（claude 默认 120 秒、codex 默认 60 秒，超时杀掉整棵进程树，返回 `MODEL_TIMEOUT`）、追加参数、环境变量等。

**只当纯文本模型用**：这些工具本来是能读写文件、执行命令的编码代理，安全相关的参数写死在代码里，配置改不掉：

| 工具 | 参数 | 作用 |
|------|------|------|
| claude | `-p --output-format json` / `stream-json --verbose --include-partial-messages` | 非交互，输出 JSON；流式时逐段输出 |
| | `--tools ""` | 禁用全部内置工具（Bash、读写文件、联网……），init 事件里 `tools` 为空 |
| | `--strict-mcp-config --mcp-config {"mcpServers":{}}` | 不加载任何 MCP 服务 |
| | `--setting-sources ""`、`--disable-slash-commands` | 不加载 user / project / local settings（hooks、权限规则、插件）和 skills |
| | `--permission-mode dontAsk`、`--no-session-persistence` | 未预先允许的一律拒绝，不卡在等确认；会话不写盘 |
| | `--system-prompt-file <文件>` | 客服提示词替换掉 Claude Code 自带的编码助手提示词；用户消息走 stdin |
| | 环境变量 `CLAUDE_CODE_DISABLE_CLAUDE_MDS` / `_AUTO_MEMORY` / `_THINKING` | 不读任何 CLAUDE.md 和自动记忆（个人指令不会混进来），关掉扩展思考更快 |
| codex | `exec --json --sandbox read-only` | 非交互、JSONL 事件；模型生成的命令只能在只读沙箱里跑 |
| | `--disable shell_tool` / `unified_exec` / `multi_agent` / `apps` / `plugins` 等、`-c web_search="disabled"` | 关掉执行命令、子代理、插件、联网搜索等功能 |
| | `--ignore-user-config --ignore-rules --ephemeral --skip-git-repo-check` | 不加载 `~/.codex/config.toml`（含 MCP）与规则文件；会话不写盘；不要求在 git 仓库里 |
| | `-c model_instructions_file="<文件>"` | 客服提示词替换掉 Codex 自带的编码代理指令（`exec` 没有系统提示词参数）；用户消息走 stdin（末尾的 `-`） |
| 两者 | 工作目录 = 每次新建的空临时目录，用完删除 | 读不到项目文件，也读不到项目里的 CLAUDE.md / AGENTS.md |

codex 没有「关掉全部工具」的单个开关，只读沙箱仍能读本机文件：以 `codex-cli` 启动的服务不要给别人用。

**结构化输出**：意图识别的 JSON Schema 原生交给工具，claude 用 `--json-schema`（结果在 `structured_output` 字段），codex 用 `--output-schema <文件>`；第 2 章的重试与兜底照常生效。设置 `assistant.cli.native-structured-output=false` 可改回把格式说明拼进提示词。

**没有向量模型**（第 4 章）：这两个 profile 的知识库用 Ollama 的向量模型（`OLLAMA_EMBEDDING_MODEL`，默认 `bge-m3`，地址同 `spring.ai.ollama.base-url`），Ollama 没启动时只是知识库不可用。

**多轮对话**（第 6 章）：`claude -p` / `codex exec` 一次只收一段输入，历史按「用户：… / 助手：…」拼成一段写进 stdin，系统提示词照旧走文件。每个追问多一次「追问改写」调用（每次 3～7 秒），嫌慢可以关掉 `assistant.memory.condense.enabled`；实测 claude（haiku）改写得比本地小模型准得多。

**Agent 用方案 B**（第 7 章）：每一步只要模型按 JSON Schema 输出「下一步动作」，不用原生工具调用，命令行模型照样能跑；`assistant.agent.mode=native` 在这两个 profile 下自动退回 `loop`。每一步一次进程调用（3～8 秒），一件事一般 3～5 步。

**RAG 不受影响**（第 5 章）：检索到的资料由代码直接写进提示词，模型只需要读写文字，命令行模型照样能按资料回答、标注引用；只是每次回答多几百到一千多字的资料，输入 token 更多。

**不支持原生工具调用**（第 3 章）：`CliChatModel` 只把模型当纯文本用，这两个 profile 设了 `assistant.tools.native-calling=false`，查订单、查商品只走「按意图直接查询」（代码查好结果放进提示词）；意图识别没抽到 SKU 的商品问题查不到真数据（没抽到订单号的订单问题会直接列出最近的订单）。商城令牌同样只在工具里用，不进 stdin、系统提示词文件和命令行参数（`CliProviderWebTest` 有断言）。

**慢、费 token**：每次调用都要启动一个进程，一次对话调两次（意图识别 + 回答），费用记在你自己的账号上。本机实测：

| profile | 意图识别 | 阻塞对话 | 流式（首个事件 / 全部） | 每次调用的输入 token |
|---------|---------|---------|------------------------|---------------------|
| `claude-cli`（haiku） | 约 3.5 秒 | 约 7 秒 | 约 3.8 / 7.5 秒，逐段推送 | 约 750～850 |
| `codex-cli`（默认模型） | 约 6～7 秒 | 约 14 秒 | 约 6.5 / 14 秒，整段推送 | 约 8000（换掉自带指令前约 1.7 万，剩下是 Codex 固定附带的环境与工具说明） |

codex 的 `--json` 只在一条消息完整生成后才输出它，没有逐字增量，流式接口的回答是一个 token 事件整段推送。实测中 codex 偶尔会卡住不返回，到 `assistant.cli.timeout`（codex 默认 60 秒，正常调用 6～14 秒就结束）后被杀掉，接口返回 `MODEL_TIMEOUT`，重试即可；日志里会带上它 stderr 的最后几行。

## 读代码的顺序

所有类都带了面向新手的中文注释（职责、上下游、哪一章引入，以及 AI 相关代码「为什么这么写」）。建议按一次请求的走向读：

1. `AssistantApplication`：启动入口，profile 决定接哪个模型（`application-*.yml`）
2. `chat/ChatController`：`/api/chat` 阻塞接口与 `/api/chat/stream` SSE 流式接口，流式出错时怎么变成 `error` 事件
3. `chat/ChatService`：一次对话的主流程：先识别意图，超出范围直接拒答；否则按意图直接查询，或把工具注册给模型（`answerRequest`），再带系统提示词调模型；流式里工具记录排在文字前面
4. `tool/MallTools`（连同 `OrderLookup`、`OrderListLookup`、`ProductLookup`、`OrderStatusText`）：`@Tool` 工具是什么、模型怎么决定调用、令牌为什么走 `ToolContext`、为什么查不到也返回结构化结果；再看 `ToolCallRecorder`（记录每次调用）、`ToolResultGuard`（事后检查）、`ToolProperties`（两个开关）
5. `mall/MallApiClient`（连同 `MallResult`、`MallModels`、`MallLoginController`、`MallToken`）：怎么调 mall-evolution 网关、错误怎么归类、为什么看不到别人的订单
6. `intent/IntentService`（连同 `IntentResult`、`Intent`）：结构化输出成 Java 对象、解析失败重试一次、校验模型输出、失败兜底
7. `prompt/AssistantPrompts`（连同 `PromptProperties`、`src/main/resources/prompts/`）：提示词模板、变量渲染与版本切换（v3 为什么这么短）；第 5 章的意图识别换成 `intent-v2.st`（加了规则类 POLICY）
8. `knowledge/`（第 4 章）：`KnowledgeIngestionService`（入库主流程）→ `MarkdownKnowledgeReader`（front matter）→ `MarkdownChunker`（按标题分节、按长度切、重叠）→ `ChunkDocumentFactory`（块文字、确定性 ID、元数据、内容哈希）→ `IngestPlanner`（增量判断）→ `KnowledgeStore` 的两个实现（`MemoryKnowledgeStore` / `PgVectorKnowledgeStore` 与 `PgVectorConfig`）；检索看 `KnowledgeSearchService`，向量模型的名字、维度、前缀看 `KnowledgeEmbedding` 与 `PrefixedEmbeddingModel`
9. `rag/`（第 5 章）：`RagService`（RAG 主流程：检索 → 拼提示词 → 检索不到就短路）→ `RagRetriever`（向量 + 关键词两路检索、门槛筛选）→ `KeywordIndex` 与 `Terms`（BM25 与中文切词）→ `RrfFusion`（RRF 融合）→ `ContextPacker`（去重、合并相邻块、上下文预算、编号）→ `CitationChecker`（引用校验）；`ChatService#ragAnswer` / `#ragStream` 是对话里的入口，`prompts/rag-v1.st` 是 RAG 的系统提示词
10. `memory/`（第 6 章）：`ConversationMemory`（打开会话、过期、并发、写回、查看、清空，为什么没用 `MessageChatMemoryAdvisor`）→ `MemoryConfig`（内存 / JDBC 存储与 `MessageWindowChatMemory`）与 `LazyJdbcChatMemoryRepository` → `HistoryWindow`（按轮数和字数裁剪）→ `MemoryMessages`（存什么不存什么）→ `QuestionCondenser`（追问改写与跑题检查）→ `ConversationEntities`（订单号核对与沿用）→ `ConversationOwners`（会话归属与记忆键）→ `ConversationSummarizer`（摘要记忆）；对话里的入口是 `ChatService#begin` / `#chat` / `#answerStream(ChatTurn)`
11. `agent/`（第 7 章）：`AgentService`（哪些话交给 Agent、聊天里说「确认」怎么办）→ `AgentLoop`（方案 B 的循环与三道刹车）→ `AgentPlanner`（每一步的提示词与结构化输出）→ `AgentDecision` / `AgentAction`（模型的提议与代码的校验）→ `AgentToolbox`（只读工具）→ `OrderRules`（什么状态能办什么）→ `WriteActionGuard`（写操作的提议关）→ `ConfirmationService` 与 `ConfirmationController`（人工确认：归属、过期、幂等、执行、写回记忆）→ `NativeAgentRunner`（方案 A，对比用）；`aftersales/`：售后申请单与 memory / jdbc 两种存储；对话里的入口是 `ChatService#agentAnswer` / `#agentStream`
12. `mcp/`（第 8 章）：`MallMcpTools`（8 个 `@McpTool`：说明是写给对面模型的提示词、只读提示、每个方法先进门再调业务、异常不外泄）→ `McpServerConfig`（Streamable HTTP 传输装上身份提取与 `Origin` 校验、规则文档作为资源）→ `McpAuth`（令牌从 HTTP 头进传输上下文）→ `McpGate` 与 `McpRateLimiter`（身份、登录、次数）→ `McpMallService`（查订单、查知识库、提出写操作：复用第 7 章的提议关，只登记不执行）→ `McpResults` / `McpViews`（结果格式与截断）；`agent/ConfirmationService#createForMcp` / `#snapshot` 与 `ConfirmationController` 的 GET（确认链接）；`mcp/client/`：`ExternalMcpTools`（外部工具的白名单、改名、截短）与 `McpClientConfig`（不把 ToolContext 发出去）；对话里的入口是 `ChatService#answerRequest`
13. 第 9 章，三个包：`guard/`（上线保护）：`InjectionFilter`（提示注入规则）→ `RagService#screen` 与 `ExternalMcpTools#screenResult`（挡在哪）→ `CallerResolver`（算谁的）→ `ChatGuard` 与 `SlidingWindowLimiter`（预算、次数、并发）→ `ModelCircuitBreaker` 与 `CircuitBreakerAdvisor`（熔断）→ `ChatService#degrade`（降级给原文）；`ops/`（运维）：`OpsConfig`（把两个 advisor 挂到 ChatClient 上）→ `UsageAdvisor` 与 `TokenEstimator`（Token 从哪来）→ `TurnRecord`（一轮的账本，ThreadLocal 与请求上下文两种带法）→ `UsageLedger`（总账与预算）→ `ChatMetrics`（指标）→ `TokenUsageEndpoint` / `UsageController` / `TraceIdFilter`；`eval/`（评测）：`EvalCase` / `EvalSet`（JSONL 与版本）→ `EvalRunner` → `EvalChecks`（判定）→ `EvalReport` / `EvalComparison`（汇总与基线）→ `EvalJudge` / `ChatClientJudge`（LLM 裁判）；入口是 `ChatController`（进门、记账、指标）
14. 第 10 章，`state/`（共享状态）：先看七个接口和它们的内存实现（`guard/RateLimiter` 与 `SlidingWindowLimiter`、`guard/ConcurrencyLimiter` 与 `SemaphoreConcurrencyLimiter`、`ops/UsageStore` 与 `MemoryUsageStore`、`agent/ConfirmationStore` 与 `MemoryConfirmationStore`、`memory/ConversationLocks`、`memory/OwnerCache`），再看 `StateProperties` → `MemoryStateConfig` / `RedisStateConfig`（二选一）→ `RedisRateLimiter` 与 `resources/redis/sliding-window.lua` → `RedisConcurrencyLimiter` 与 `slot-acquire.lua` → `RedisUsageStore` 与 `usage-add.lua` → `RedisConfirmationStore`（`SET NX` 抢占）与 `ConfirmationService#decide`（抢占、等待别人的结果）→ `RedisConversationLocks` 与 `unlock.lua` → `StateFailures`（放行还是拒绝、冷却）；监控看 `monitoring/docker-compose.yml`、`prometheus/`（file_sd、告警与 `alerts.test.yml`）、`grafana/provisioning/`
15. `chat/ChatClientConfig`：`ChatClient` 怎么由 Spring AI 自动配置的 `ChatModel` 构建出来
16. `error/`：模型调用失败分成「不可用」和「超时」两类（第 9 章加了「熔断中」），统一成 `ProblemDetail` 响应（第 4 章起还有知识库的三类错误，第 9 章起还有进门被挡下的 429 / 503）
17. `cli/`（选读）：用本机 Claude Code / Codex 命令行当模型：安全参数、stdin 传参、输出解析、超时杀进程树
18. `src/test/java`：从 `FakeChatModel` 和 `ChatServiceTest` 读起，再看 `ToolCallingFlowTest`（工具调用的两轮、令牌不进提示词）、`StubMallApi`（假商城网关）、`FakeModelWebTest` 系列接口测试、`IntentReplayTest` / `ToolDecisionReplayTest` 录制回放、`FakeCli` 与 `OllamaChatIT`；第 4 章看 `FakeEmbeddingModel`（字符 n-gram 哈希的假向量模型）、`KnowledgeIngestionServiceTest`（增量）、`PgVectorStoreIT`；第 5 章看 `RagChatFlowTest`（路由、短路、引用校验、流式顺序）、`RagServiceTest`（提示词结构、提示注入）、`RetrievalEval`（hit@k 评测）与 `RagReplayTest`（RAG 回答的录制回放）；第 6 章看 `MemoryFixtures`、`MultiTurnChatFlowTest`（历史在提示词里的位置、存什么、改写、沿用、流式写记忆）、`ConversationMemoryTest`（过期、并发、隔离、摘要）与 `ConversationReplayTest`（多轮对话的录制回放）；第 7 章看 `AgentFixtures`、`AgentLoopTest`（剧本驱动的多步循环与刹车）、`WriteActionGuardTest`、`ConfirmationServiceTest`（确认、过期、幂等、越权）、`AgentChatFlowTest`（路由、聊天里说确认、提示注入、流式顺序）与 `AgentReplayTest`（Agent 决策的录制回放）；第 8 章看 `McpFixtures`、`McpMallServiceTest`（只提议不执行、页面确认后才执行）、`McpGateTest`、`McpServerProtocolTest`（真的 MCP 客户端走 Streamable HTTP）与 `McpClientLoopbackTest`（客户端连自己的 `/mcp`）；第 9 章看 `InjectionFilterTest`（规则、回归、漏网与误杀）、`ChatGuardTest` / `ModelCircuitBreakerTest` / `DegradeTest`、`UsageAdvisorTest` / `UsageLedgerTest`、`GuardApiTest`（接口上的 429 / 503 / 降级 / usage / 指标），评测从 `EvalFixtures`（整套被测对象）、`EvalRecording`（录制与回放，含 float16 向量）读起，再看 `EvalGateTest`（CI 门禁）与 `EvalIT`（真实模型）；第 10 章看 `SharedStateContract`（两种实现共用的契约）与它的两个子类 `MemoryStateContractTest` / `RedisStateContractIT`、`RedisMultiInstanceIT`（两个实例共享）、`RedisDownTest` / `RedisDownApiTest`（Redis 挂了）、`MonitoringFilesTest`（面板与告警的指标名）

## 构建与测试

```bash
./mvnw -B verify          # Enforcer、单元测试、Testcontainers 集成测试、JaCoCo、Spotless（palantir）、Checkstyle
./mvnw spotless:apply     # 格式不对时一键修复
```

| 层 | 命名 | 需要 Docker | 覆盖 |
|----|------|------------|------|
| 单元 / 切片测试 | `*Test` | 否 | 用不联网的 `FakeChatModel` 替换模型：提示词模板、阻塞与流式回答、意图识别的解析 / 重试 / 兜底、超时与不可用的错误映射、SSE 事件序列、profile 切换 |
| 工具与商城 | `MallApiClientTest`、`MallToolsTest`、`ToolCallingFlowTest`、`ToolApiTest` 等 | 否 | `StubMallApi`（JDK 自带 HttpServer 起的假网关）代替 mall-evolution；`FakeChatModel.willCallTool` 模拟模型的调用意图，由 Spring AI 真实地执行工具循环：工具结果回传、令牌只在 ToolContext、直接查询、未登录、参数不合法、商城超时 / 5xx |
| 命令行提供方 | `Cli*Test`、`CliProviderWebTest` | 否 | 用假命令行程序 `FakeCli`（`java -cp target/test-classes`）代替 claude / codex：安全参数、stdin 中文、JSON / 流式 / JSONL 解析、结构化输出、退出码非 0、输出乱码、超时杀进程树 |
| 知识库 | `MarkdownChunkerTest`、`KnowledgeIngestionServiceTest`、`KnowledgeApiTest` 等 | 否 | 不联网的 `FakeEmbeddingModel`（字面越像向量越近）+ 内存向量库：切块边界与重叠、元数据、增量（跳过 / 更新 / 删除）、检索排序与分数、前缀、接口、启动时模型不可用不阻止启动 |
| RAG | `KeywordIndexTest`、`RrfFusionTest`、`ContextPackerTest`、`CitationCheckerTest`、`RagServiceTest`、`RagChatFlowTest`、`RagApiTest`、`RetrievalEvalTest` | 否 | 假对话模型 + 假向量模型 + 6 篇真实规则文档：BM25 与 RRF 手算对照、门槛、去重合并与预算、按意图路由、检索不到不调模型、提示词里的编号资料、恶意文档只在资料标签里、引用校验、`sources` 字段与 `source` 事件顺序、混合检索的 hit@k 不低于只用向量 |
| 多轮对话 | `HistoryWindowTest`、`QuestionCondenserTest`、`ConversationEntitiesTest`、`ConversationOwnersTest`、`ConversationMemoryTest`、`MultiTurnChatFlowTest`、`ConversationApiTest` | 否 | 假模型 + 内存记忆 + 可拨动的时钟 + 假商城：窗口与预算裁剪、只存原话、追问改写进入意图识别与检索、订单号核对与沿用、会话归属与越权 404、过期、并发 409、流式完成后才写、摘要记忆、`memory` 字段与事件 |
| Agent | `AgentActionTest`、`AgentLoopTest`、`AgentToolboxTest`、`WriteActionGuardTest`、`ConfirmationServiceTest`、`AgentChatFlowTest`、`AgentApiTest` | 否 | 假模型按剧本输出每一步 + 假商城（有状态的待支付、已发货订单，取消 / 改地址 / 支付单接口）：步数与重复上限、只读直接执行、写操作只生成确认、确认 / 拒绝 / 过期 / 重复确认 / 别人的确认 404 / 请求里塞参数无效、确认后写回记忆、聊天里说「确认」不执行、恶意资料只能导致一张确认卡片、`step` / `confirm` 事件顺序、方案 A |
| 上线保护 | `InjectionFilterTest`、`RagScreeningTest`、`BlockedStreamTest`、`AgentInjectionScreeningTest`、`ExternalMcpScreeningTest`、`ChatGuardTest`、`ModelCircuitBreakerTest`、`DegradeTest`、`UsageAdvisorTest`、`UsageLedgerTest`、`GuardApiTest` | 否 | 假模型 + 可以拨动的时钟：注入规则（第 5、7 章原文回归、变体、6 篇文档不误杀、漏网与误杀写成测试）、挡下与重新编号、Agent 查规则与外部 MCP 工具同样过滤、Token 估算与模型报告、记账与零点清零、预算 / 次数 / 并发三关、熔断三态与挂在 ChatClient 上的快速失败、降级给原文（阻塞、流式、意图识别就失败）；接口上的 429 与 `Retry-After`、流式 `error` 事件、`done` 里的 usage、traceId、`/actuator/prometheus` 与 `/actuator/tokens`。测试 classpath 的 `config/application.yml` 把限流、熔断、降级、预算对前八章的测试关掉 |
| 评测 | `EvalSetTest`、`EvalChecksTest`、`EvalReportTest`、`EvalRunnerTest`、`EvalGateTest` | 否 | 评测集格式与版本、每一项期望的判定、汇总与基线对比、运行器（多轮、Token、出错、裁判）；**评测门禁** `EvalGateTest`：回放 `src/test/resources/eval/recording-qwen2.5-1.5b.json`（真实模型的每次输出 + float16 向量）跑整份评测集，和 `baseline-qwen2.5-1.5b.json` 逐条一致，CI 把报告贴进运行摘要。真实模型的评测 `EvalIT` 只在设置了 `EVAL_OLLAMA_URL` 时运行（可录制、更新基线、请 Claude CLI 当裁判） |
| 共享状态 | `MemoryStateContractTest`、`RedisDownTest`、`RedisDownApiTest`、`MonitoringFilesTest` | 否 | 同一套契约（`SharedStateContract`）跑内存实现：限流、名额、Token 账、确认抢占、会话锁、归属缓存，含并发；Redis 连不上（指向没人监听的端口）时两种策略：放行（名额退回本地信号量）与拒绝（503 `STATE_UNAVAILABLE`），确认一律 503，冷却期不碰 Redis，健康检查仍是 UP；面板与告警里写的每个 `assistant_*` 指标应用真的会输出、面板能被 provisioning 加载 |
| MCP | `McpAuthTest`、`McpGateTest`、`McpRateLimiterTest`、`McpMallServiceTest`、`MallMcpToolsTest`、`ConfirmationSnapshotTest`、`ExternalMcpToolsTest`、`McpServerProtocolTest`、`McpClientLoopbackTest` | 否 | 单元：令牌只从请求头取、打码、身份与登录、次数（滑动窗口、并发）、查订单打码、知识库带来源、写操作只登记不执行（假商城收不到写请求）、页面确认后才执行、状态（待确认 / 已执行 / 已取消 / 过期）、外部工具的白名单与改名与截短、不带 ToolContext；协议级：MCP Java SDK 的真客户端走 Streamable HTTP 连 `RANDOM_PORT` 起的整个应用（initialize、tools/list 的 Schema 与提示、tools/call、resources、`Origin` 校验、令牌不进结果和日志）；回环：打开 MCP Client 连自己的 `/mcp`，远程工具经假模型的工具调用走一遍，ToolContext 里的令牌没有作为 `_meta` 发出去。MCP Server 不调模型，没有录制回放 |
| 录制回放 | `IntentReplayTest`、`ToolDecisionReplayTest`、`RagReplayTest`、`ConversationReplayTest`、`AgentReplayTest` | 否 | 回放 `src/test/resources/recordings/` 里真实模型（qwen2.5 0.5b / 1.5b）录下的原样输出：意图识别的 JSON、拿到工具清单后的调用决策、拿到编号资料后的 RAG 回答（连同 bge-m3 检索到的资料一起录）、整段多轮对话的每一次调用、Agent 办事的每一步（连同查规则检索到的资料），验证代码能处理真实输出；重新录制见 [`docs/ch02.md`](docs/ch02.md#录制回放)、[`docs/ch03.md`](docs/ch03.md#录制回放)、[`docs/ch05.md`](docs/ch05.md#录制回放)、[`docs/ch06.md`](docs/ch06.md#录制回放)、[`docs/ch07.md`](docs/ch07.md#录制回放) |
| 集成测试 | `*IT` | 是 | Testcontainers 起真实 Ollama、拉取 `qwen2.5:0.5b`，走一遍阻塞与流式接口、意图识别和工具调用（商城用假网关）；第 4 章起 `PgVectorStoreIT` 起 `pgvector/pgvector:0.8.1-pg17` 跑真实的 PgVectorStore，`OllamaEmbeddingIT` 用真实的 `nomic-embed-text` 入库、检索并跑第 5 章的 hit@k 评测和第 6 章的相似度门槛扫描，第 6 章起 `JdbcChatMemoryIT` 用同一个 pgvector 镜像跑 JDBC 记忆、第 7 章起 `JdbcAfterSalesStoreIT` 跑售后申请单的 JDBC 存储（没有 Docker 时也可以用环境变量指向现成的服务，见 [`docs/ch04.md`](docs/ch04.md#验收标准)）；第 10 章起 `RedisStateContractIT`（同一套契约跑 Redis 实现，外加租期收回、锁过期、键都带前缀且会过期）与 `RedisMultiInstanceIT`（两个 Redis 客户端当两个实例：合计限流、共享预算与名额、A 生成的确认在 A、B 同时点只执行一次）用 `redis:8.10.3-alpine`（没有 Docker 时设 `REDIS_IT_URL`，见 [`docs/ch10.md`](docs/ch10.md#验收标准)）；录制工具 `IntentRecordingIT`、`ToolDecisionRecordingIT`、`RagRecordingIT`、`ConversationRecordingIT`、`AgentRecordingIT` 只在设置了 `RECORD_OLLAMA_URL` 时运行 |

没有 Docker 的机器上集成测试自动跳过（`@Testcontainers(disabledWithoutDocker = true)`）。模型文件缓存在 `~/.cache/mall-assistant/ollama`（可用 `OLLAMA_MODELS_CACHE` 指定），只下载一次。

## 测试报告

每次推送 `main` 或章节 tag，GitHub Actions（`.github/workflows/verify.yml`）先用 `promtool` 校验监控配置并跑告警规则的单元测试（第 10 章），再在带 Docker 的 runner 上跑完整的 `./mvnw -B verify`，Ollama 集成测试不跳过，模型用 `actions/cache` 缓存。报告发布到 `https://clarence404.github.io/mall-assistant/reports/<ref>/`，如 [`reports/main/`](https://clarence404.github.io/mall-assistant/reports/main/)、[`reports/ch01-done/`](https://clarence404.github.io/mall-assistant/reports/ch01-done/)、[`reports/ch01-start/`](https://clarence404.github.io/mall-assistant/reports/ch01-start/)。

`chNN-start` 标签的验收测试本来就不通过：报告照常发布，运行不标红，摘要里注明「start 标签：测试未通过是预期的」。其他 ref 测试失败时报告照样发布，运行记为失败。

## 目录结构

```text
mall-assistant/
├── src/main/java/io/github/clarence404/assistant/
│   ├── chat/                 ChatClient 配置、ChatService（会话 → 改写 → 意图 → 拒答、Agent、RAG 或回答，回答时用工具 → 写记忆）、阻塞与 SSE 接口、事件定义
│   ├── tool/                 工具：MallTools（@Tool 查订单、列出我的订单、查商品）、调用记录、事后检查、开关
│   ├── mall/                 商城 mall-evolution 网关的客户端、登录代理接口、令牌工具
│   ├── prompt/               提示词模板：按版本选文件、渲染变量、拒答话术
│   ├── intent/               意图识别：IntentResult、结构化输出与重试兜底、/api/intent
│   ├── knowledge/            知识库（第 4 章）：读取、切块、元数据、增量入库、memory / pgvector 向量库、相似度检索、调试接口
│   ├── rag/                  检索增强回答（第 5 章）：混合检索（向量 + BM25，RRF 融合）、门槛、去重与上下文预算、编号资料、引用校验
│   ├── memory/               多轮对话与记忆（第 6 章）：ChatMemory 存储、窗口裁剪、追问改写、订单号沿用、会话归属、摘要记忆、会话接口
│   ├── agent/                售后 Agent（第 7 章）：循环与刹车、下一步动作的解析与校验、只读工具、写操作的提议关、人工确认与执行、原生工具调用对比；第 8 章起确认也登记 MCP 提出的操作
│   ├── aftersales/           售后申请单（第 7 章）：memory / jdbc 存储、查看接口
│   ├── mcp/                  MCP（第 8 章）：@McpTool 工具、传输层身份与来源校验、进门（登录与次数）、业务（只读与提议）、资源；client/：外部工具的白名单与截短
│   ├── guard/                上线保护（第 9 章）：提示注入过滤、调用方、预算 / 次数 / 并发三关、滑动窗口、熔断器与它的 advisor
│   ├── ops/                  运维（第 9 章）：Token 记账的 advisor 与估算、一轮的账本、总账与预算、指标、/actuator/tokens、/api/usage/me、traceId 响应头
│   ├── eval/                 评测（第 9 章）：用例与评测集、运行器、判定、报告与基线对比、LLM 裁判
│   ├── state/                共享状态（第 10 章）：memory / redis 二选一的装配、Redis 版的限流 / 名额 / 账 / 确认 / 会话锁 / 归属缓存、出错时的策略与冷却
│   ├── cli/                  命令行模型提供方：CliChatModel、claude / codex 的参数与输出解析、进程超时与清理
│   └── error/                模型失败分类（MODEL_UNAVAILABLE / MODEL_TIMEOUT）与 ProblemDetail
├── src/main/resources/
│   ├── application.yml       公共配置：超时、重试、ProblemDetail、工具调用上限、商城网关地址、知识库（切块、向量库、前缀）、RAG、记忆、Agent 与售后申请单、MCP Server 与 Client、第 9 章的上线保护（限流、熔断、降级、注入过滤）、Token 预算与 Actuator
│   ├── application-ollama.yml / application-openai.yml   按 profile 切换模型厂商
│   ├── application-claude-cli.yml / application-codex-cli.yml   用本机命令行工具当模型（可选）
│   ├── knowledge/            示例商城的规则文档（第 4 章入库的知识）
│   ├── redis/                第 10 章的 Lua 脚本：滑动窗口、分布式名额、一轮记账、比较再删除
│   ├── prompts/              客服人设 system-v1.st / v2 / v3、意图识别 intent-v1.st / v2、拒答话术 refusal.st、RAG 的 rag-v1.st 与固定话术、追问改写 memory-condense.st 与摘要 memory-summary.st、Agent 的 agent-v1.st 与 agent-native-v1.st
│   └── static/index.html     聊天页面（原生 HTML/CSS/JS，第 3 章起带登录框，第 4 章起带知识库调试区，第 5 章起回答里的引用编号可点，第 6 章起带会话 ID、刷新恢复历史，第 7 章起带 Agent 步骤与确认卡片，第 8 章起能打开 MCP 的确认链接，第 9 章起带 Token 用量、traceId 与被挡下的资料）
├── src/test/java/            FakeChatModel、StubMallApi、FakeEmbeddingModel、单元与接口测试、录制回放、RAG 评测、OllamaChatIT、PgVectorStoreIT
├── src/test/resources/recordings/   真实模型的录制输出（录制回放用）
├── src/test/resources/rag/          检索评测集（口语化问题 → 应命中的一节）
├── src/test/resources/eval/         第 9 章回归评测集 cases-v1.jsonl、两篇带提示注入的文档、1.5b 的录制与基线
├── src/test/resources/config/       测试专用的覆盖配置（对前八章的测试关掉限流、熔断、降级、预算）
├── monitoring/               第 9 章的 Grafana 面板 JSON、Prometheus 抓取配置与告警规则；第 10 章的 docker-compose.yml、Grafana provisioning、file_sd 目标、告警单元测试 alerts.test.yml
├── docs/chNN.md              每章的练习说明
├── .run/                     IntelliJ IDEA 共享运行配置
├── config/                   Checkstyle 规则（checkstyle/）、本地覆盖配置模板 application-local.example.yml（复制为同目录的 application-local.yml，不入库）
└── .github/                  verify 工作流与报告脚本
```

## 学完之后

十章下来，这个智能客服从「能聊天」长到了「敢上线」：接模型与流式（1）→ 提示词与结构化输出（2）→ 工具调用（3）→ 知识入库（4）→ RAG（5）→ 多轮记忆（6）→ Agent 与人工确认（7）→ MCP（8）→ 评测、Token、限流熔断降级、监控、注入过滤（9）→ 共享状态与监控落地，能多实例部署（10）。

**学完能做什么**：给一个已有的 Java 业务系统加一个懂业务、查得到真数据、按规则回答、会办事但不乱办的 AI 助手，并且知道怎么证明它没有变差、花了多少、出了事怎么查、哪里会被攻击。几条贯穿始终的原则：模型负责理解和措辞，代码负责事实、规则和执行；外来的文字都是数据，不是指令；宁可说不知道，也不要说错；改数据的事，人来拍板；能观测的才能评测，能评测的才敢上线。

**离生产还差什么**（教学项目有意省略的）：状态都在一个进程里（第 10 章已做：限流、名额、预算、确认、会话标记进了 Redis，记忆和售后申请单可以进 PostgreSQL，熔断器有意每实例一份）；身份借的是商城登录令牌（生产要 OAuth 2.1 与权限范围）；CPU 上的 1.5b 模型（意图九成、RAG 七成、Agent 三成，要换更强的模型或分层路由并重跑评测）；评测集小而「干净」（要从线上日志采样、人工标注、跟着业务长）；知识库没有审核与多租户权限；护栏只有规则（没有越狱检测模型、输出合规审查）；没有灰度与 A/B；成本优化（语义缓存、提示词压缩）没做；链路追踪没接 exporter、告警没接值班（第 10 章 Prometheus 与 Grafana 跑起来了、告警有单元测试，Alertmanager 还没接）。详见 [`docs/ch09.md`](docs/ch09.md#全系列收尾)、[`docs/ch10.md`](docs/ch10.md#收尾)。

## 许可证

[MIT](LICENSE)
