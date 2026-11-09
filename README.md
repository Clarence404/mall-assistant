# mall-assistant

Java Doc「AI」模块的配套练习项目：给 [mall-evolution](https://github.com/Clarence404/mall-evolution) 里的示例商城做一个智能客服，一章一章自己动手写出来。

**版本基线**：JDK 21、Spring Boot 4.0.9、Spring AI 2.0.2、Testcontainers 2.0.5、Ollama 0.40（默认模型 `qwen2.5:0.5b`）。

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
| 4 | 知识入库 | 商城规则文档切块、向量化、存入 pgvector | [Embedding](https://clarence404.github.io/java-doc/ai/4_core_tech/0_embedding.html)、[向量数据库](https://clarence404.github.io/java-doc/ai/4_core_tech/1_vector_db.html) | 计划中 |
| 5 | RAG | 检索增强回答、引用来源 | [RAG](https://clarence404.github.io/java-doc/ai/4_core_tech/2_rag.html) | 计划中 |
| 6 | 多轮对话与记忆 | 会话 ID、ChatMemory、上下文裁剪 | [Spring AI](https://clarence404.github.io/java-doc/ai/2_frameworks/0_spring_ai.html) | 计划中 |
| 7 | Agent | 售后退款流程，关键操作人工确认 | [AI Agent](https://clarence404.github.io/java-doc/ai/5_advanced/0_agent.html) | 计划中 |
| 8 | MCP | 把商城能力暴露成 MCP Server | [MCP 协议](https://clarence404.github.io/java-doc/ai/5_advanced/1_mcp.html) | 计划中 |
| 9 | 评估与上线 | 回归评测集、Token 用量、限流与监控 | [LLM 应用评估与上线](https://clarence404.github.io/java-doc/ai/5_advanced/3_llm_eval.html) | 计划中 |

## 本地运行

需要 JDK 21 和一个模型服务。默认用本机的 [Ollama](https://ollama.com/download)，**不需要 Docker**。

1. 安装 Ollama：Windows 下载 `OllamaSetup.exe` 直接安装（装在用户目录，不需要管理员权限），装好后它在后台监听 `http://localhost:11434`
2. 拉取模型（约 400 MB）：`ollama pull qwen2.5:0.5b`，可用 `ollama run qwen2.5:0.5b` 先在命令行试一句
3. 启动：`./mvnw spring-boot:run`（Windows PowerShell 用 `.\mvnw.cmd spring-boot:run`），默认 profile 是 `ollama`
4. 打开 <http://localhost:8088/>，在页面上提问；右上角可切换流式 / 阻塞，底部「调试面板」显示耗时、错误码、识别出的意图与工具调用

第 3 章起要查订单、查商品，还需要先启动 [mall-evolution](https://github.com/Clarence404/mall-evolution)（四个服务，网关 9000，见它的 README「本地运行」），再在页面右上角用演示账号 `13800000000` / `mall123` 登录；没启动它时对话照常可用，只是工具会返回「商城服务暂时不可用」。网关地址用 `assistant.mall.base-url`（环境变量 `MALL_API_BASE_URL`）修改。

换模型：设置环境变量 `OLLAMA_CHAT_MODEL`（如 `qwen2.5:1.5b`，约 1 GB），先 `ollama pull` 好。0.5b 能跑通全部流程，但第 2 章起的意图识别只分对一半左右；1.5b 明显更准（录制的 12 条消息分对 11 条）。

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

### 在 IntelliJ IDEA 里运行

运行配置的 VM 参数里带了 `--enable-native-access=ALL-UNNAMED`：用 JDK 24 及以上运行时，依赖里的 Netty 等库会加载本地库，JVM 会打印「A restricted method in java.lang.System has been called」的警告，加上这个参数就不再提示；JDK 21 也认识它，没有副作用。

仓库带了共享运行配置（`.run/`），用 IDEA 打开项目、等 Maven 导入完成（项目 SDK 选 JDK 21）后，右上角直接选：

| 运行配置 | 作用 |
|---------|------|
| 智能客服（本地 Ollama） | profile `ollama,local`，可在配置里改 `OLLAMA_CHAT_MODEL` |
| 智能客服（OpenAI 兼容接口） | profile `openai,local`，环境变量 `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL` 留空，填上才能启动 |
| 智能客服（Claude CLI） | profile `claude-cli,local`，用本机已登录的 Claude Code 当模型，见下文「用命令行工具当模型」 |
| 智能客服（Codex CLI） | profile `codex-cli,local`，用本机已登录的 Codex CLI 当模型 |
| 测试：mvn verify | 跑全部检查与测试，等同 `./mvnw verify` |

注意：这些配置存放在入库的 `.run/*.run.xml` 里，在里面填的值会出现在 `git diff` 中。密钥和内部地址不要填进运行配置，改用下面的本地配置文件。

第 3 章：先在 mall-evolution 的项目里用它自带的运行配置「全部服务（先起中间件）」启动四个服务（两个项目可以同时开在两个 IDEA 窗口里），再启动智能客服。想临时关掉某种工具用法，在运行配置的 Program arguments 里加 `--assistant.tools.direct-lookup=false` 或 `--assistant.tools.native-calling=false`。

### 指向远程 Ollama 或 OpenAI 兼容接口（本地配置，不入库）

所有外部地址都是带默认值的占位符（如 `${OLLAMA_BASE_URL:http://localhost:11434}`），默认指向本机。要连共享开发机上的 Ollama、或者用云端 OpenAI 兼容接口时：

```bash
cp application-local.example.yml application-local.yml   # 已被 .gitignore 忽略
# 编辑 application-local.yml：填远程 Ollama 的 base-url，或取消注释 openai 一段填 base-url / api-key / 模型名
```

以 `local` profile 启动时生效：IDEA 的两个运行配置已经带上 `local`；命令行用 `./mvnw spring-boot:run -Dspring-boot.run.profiles=ollama,local`（或 `openai,local`）。Spring Boot 会自动加载工作目录下的 `application-{profile}.yml`，所以要在仓库根目录启动；没有这个文件也能正常启动。

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
7. `prompt/AssistantPrompts`（连同 `PromptProperties`、`src/main/resources/prompts/`）：提示词模板、变量渲染与版本切换（v3 为什么这么短）
8. `chat/ChatClientConfig`：`ChatClient` 怎么由 Spring AI 自动配置的 `ChatModel` 构建出来
9. `error/`：模型调用失败分成「不可用」和「超时」两类，统一成 `ProblemDetail` 响应
10. `cli/`（选读）：用本机 Claude Code / Codex 命令行当模型：安全参数、stdin 传参、输出解析、超时杀进程树
11. `src/test/java`：从 `FakeChatModel` 和 `ChatServiceTest` 读起，再看 `ToolCallingFlowTest`（工具调用的两轮、令牌不进提示词）、`StubMallApi`（假商城网关）、`FakeModelWebTest` 系列接口测试、`IntentReplayTest` / `ToolDecisionReplayTest` 录制回放、`FakeCli` 与 `OllamaChatIT`

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
| 录制回放 | `IntentReplayTest`、`ToolDecisionReplayTest` | 否 | 回放 `src/test/resources/recordings/` 里真实模型（qwen2.5 0.5b / 1.5b）录下的原样输出：意图识别的 JSON、拿到工具清单后的调用决策，验证代码能处理真实输出；重新录制见 [`docs/ch02.md`](docs/ch02.md#录制回放)、[`docs/ch03.md`](docs/ch03.md#录制回放) |
| 集成测试 | `*IT` | 是 | Testcontainers 起真实 Ollama、拉取 `qwen2.5:0.5b`，走一遍阻塞与流式接口、意图识别和工具调用（商城用假网关）；录制工具 `IntentRecordingIT`、`ToolDecisionRecordingIT` 只在设置了 `RECORD_OLLAMA_URL` 时运行 |

没有 Docker 的机器上集成测试自动跳过（`@Testcontainers(disabledWithoutDocker = true)`）。模型文件缓存在 `~/.cache/mall-assistant/ollama`（可用 `OLLAMA_MODELS_CACHE` 指定），只下载一次。

## 测试报告

每次推送 `main` 或章节 tag，GitHub Actions（`.github/workflows/verify.yml`）在带 Docker 的 runner 上跑完整的 `./mvnw -B verify`，Ollama 集成测试不跳过，模型用 `actions/cache` 缓存。报告发布到 `https://clarence404.github.io/mall-assistant/reports/<ref>/`，如 [`reports/main/`](https://clarence404.github.io/mall-assistant/reports/main/)、[`reports/ch01-done/`](https://clarence404.github.io/mall-assistant/reports/ch01-done/)、[`reports/ch01-start/`](https://clarence404.github.io/mall-assistant/reports/ch01-start/)。

`chNN-start` 标签的验收测试本来就不通过：报告照常发布，运行不标红，摘要里注明「start 标签：测试未通过是预期的」。其他 ref 测试失败时报告照样发布，运行记为失败。

## 目录结构

```text
mall-assistant/
├── src/main/java/io/github/clarence404/assistant/
│   ├── chat/                 ChatClient 配置、ChatService（意图 → 拒答或回答，回答时用工具）、阻塞与 SSE 接口、事件定义
│   ├── tool/                 工具：MallTools（@Tool 查订单、列出我的订单、查商品）、调用记录、事后检查、开关
│   ├── mall/                 商城 mall-evolution 网关的客户端、登录代理接口、令牌工具
│   ├── prompt/               提示词模板：按版本选文件、渲染变量、拒答话术
│   ├── intent/               意图识别：IntentResult、结构化输出与重试兜底、/api/intent
│   ├── cli/                  命令行模型提供方：CliChatModel、claude / codex 的参数与输出解析、进程超时与清理
│   └── error/                模型失败分类（MODEL_UNAVAILABLE / MODEL_TIMEOUT）与 ProblemDetail
├── src/main/resources/
│   ├── application.yml       公共配置：超时、重试、ProblemDetail、工具调用上限、商城网关地址
│   ├── application-ollama.yml / application-openai.yml   按 profile 切换模型厂商
│   ├── application-claude-cli.yml / application-codex-cli.yml   用本机命令行工具当模型（可选）
│   ├── prompts/              客服人设 system-v1.st / v2 / v3、意图识别 intent-v1.st、拒答话术 refusal.st
│   └── static/index.html     聊天页面（原生 HTML/CSS/JS，第 3 章起带登录框）
├── src/test/java/            FakeChatModel、StubMallApi、单元与接口测试、录制回放、OllamaChatIT
├── src/test/resources/recordings/   真实模型的录制输出（录制回放用）
├── docs/chNN.md              每章的练习说明
├── application-local.example.yml   本地覆盖配置模板（复制为 application-local.yml，不入库）
├── .run/                     IntelliJ IDEA 共享运行配置
├── config/checkstyle/
└── .github/                  verify 工作流与报告脚本
```

## 许可证

[MIT](LICENSE)
