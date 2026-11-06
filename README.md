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
| 3 | 工具调用 | 调 mall-evolution 的接口查订单、查库存 | [Function Calling](https://clarence404.github.io/java-doc/ai/1_concepts/2_function_calling.html) | 计划中 |
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
4. 打开 <http://localhost:8088/>，在页面上提问；右上角可切换流式 / 阻塞，底部「调试面板」显示耗时、错误码与识别出的意图

换模型：设置环境变量 `OLLAMA_CHAT_MODEL`（如 `qwen2.5:1.5b`，约 1 GB），先 `ollama pull` 好。0.5b 能跑通全部流程，但第 2 章起的意图识别只分对一半左右；1.5b 明显更准（录制的 12 条消息分对 11 条）。

提示词版本：`assistant.prompt.version`（默认 `v2`，可改回 `v1` 对比）；商城名、服务范围等模板变量在 `application.yml` 的 `assistant.prompt.*`。

### 端口规划

和 [mall-evolution](https://github.com/Clarence404/mall-evolution) 同时在本机运行时，端口互不冲突：

| 项目 | 服务 | 端口 |
|------|------|------|
| mall-assistant | 智能客服（本仓库） | 8088，可用环境变量 `SERVER_PORT` 修改 |
| mall-evolution | 订单与商品 mall-app | 8080 |
| mall-evolution | 库存服务 / 支付服务 | 8082 / 8083 |
| mall-evolution | 网关（对外入口，第 3 章起智能客服调用它） | 9000 |
| 模型 | Ollama | 11434 |

### 在 IntelliJ IDEA 里运行

仓库带了共享运行配置（`.run/`），用 IDEA 打开项目、等 Maven 导入完成（项目 SDK 选 JDK 21）后，右上角直接选：

| 运行配置 | 作用 |
|---------|------|
| 智能客服（本地 Ollama） | profile `ollama,local`，可在配置里改 `OLLAMA_CHAT_MODEL` |
| 智能客服（OpenAI 兼容接口） | profile `openai,local`，环境变量 `OPENAI_BASE_URL` / `OPENAI_API_KEY` / `OPENAI_MODEL` 留空，填上才能启动 |
| 智能客服（Claude CLI） | profile `claude-cli,local`，用本机已登录的 Claude Code 当模型，见下文「用命令行工具当模型」 |
| 智能客服（Codex CLI） | profile `codex-cli,local`，用本机已登录的 Codex CLI 当模型 |
| 测试：mvn verify | 跑全部检查与测试，等同 `./mvnw verify` |

注意：这些配置存放在入库的 `.run/*.run.xml` 里，在里面填的值会出现在 `git diff` 中。密钥和内部地址不要填进运行配置，改用下面的本地配置文件。

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

启动：IDEA 选「智能客服（Claude CLI）」/「智能客服（Codex CLI）」，或命令行 `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=claude-cli"`。命令默认在 PATH 里找；Windows 下 npm 装的是 `claude.cmd` / `codex.cmd` 包装，程序会从里面读出脚本路径，直接执行 `node.exe <脚本>`，不经过 cmd.exe（否则系统提示词里的换行、Schema 里的引号会被 cmd.exe 再解释一遍）。找不到时用 `CLAUDE_CLI_COMMAND` / `CODEX_CLI_COMMAND` 指定完整路径。其余配置在 `application-claude-cli.yml` / `application-codex-cli.yml` 的 `assistant.cli.*`：超时（默认 120 秒，超时杀掉整棵进程树，返回 `MODEL_TIMEOUT`）、追加参数、环境变量等。

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
| 两者 | 工作目录 = 每次新建的空临时目录，用完删除 | 读不到项目文件，也读不到项目里的 CLAUDE.md / AGENTS.md |

codex 没有「关掉全部工具」的单个开关，只读沙箱仍能读本机文件：以 `codex-cli` 启动的服务不要给别人用。

**结构化输出**：意图识别的 JSON Schema 原生交给工具，claude 用 `--json-schema`（结果在 `structured_output` 字段），codex 用 `--output-schema <文件>`；第 2 章的重试与兜底照常生效。设置 `assistant.cli.native-structured-output=false` 可改回把格式说明拼进提示词。

**慢**：每次调用都要启动一个进程（claude 约 3 秒起），一次对话调两次（意图识别 + 回答）。本机实测 `claude-cli` + haiku：意图识别约 3.5 秒，阻塞对话约 7 秒，流式首个事件约 3.8 秒、全部约 7.5 秒。codex 的 `--json` 只在整段消息生成完才输出，流式接口是整段推送。费用记在你自己的账号上。

## 构建与测试

```bash
./mvnw -B verify          # Enforcer、单元测试、Testcontainers 集成测试、JaCoCo、Spotless（palantir）、Checkstyle
./mvnw spotless:apply     # 格式不对时一键修复
```

| 层 | 命名 | 需要 Docker | 覆盖 |
|----|------|------------|------|
| 单元 / 切片测试 | `*Test` | 否 | 用不联网的 `FakeChatModel` 替换模型：提示词模板、阻塞与流式回答、意图识别的解析 / 重试 / 兜底、超时与不可用的错误映射、SSE 事件序列、profile 切换 |
| 命令行提供方 | `Cli*Test`、`CliProviderWebTest` | 否 | 用假命令行程序 `FakeCli`（`java -cp target/test-classes`）代替 claude / codex：安全参数、stdin 中文、JSON / 流式 / JSONL 解析、结构化输出、退出码非 0、输出乱码、超时杀进程树 |
| 录制回放 | `IntentReplayTest` | 否 | 回放 `src/test/resources/recordings/` 里真实模型（qwen2.5 0.5b / 1.5b）录下的原样输出，验证代码能处理真实输出；重新录制见 [`docs/ch02.md`](docs/ch02.md#录制回放) |
| 集成测试 | `*IT` | 是 | Testcontainers 起真实 Ollama、拉取 `qwen2.5:0.5b`，走一遍阻塞与流式接口和意图识别（录制工具 `IntentRecordingIT` 只在设置了 `RECORD_OLLAMA_URL` 时运行） |

没有 Docker 的机器上集成测试自动跳过（`@Testcontainers(disabledWithoutDocker = true)`）。模型文件缓存在 `~/.cache/mall-assistant/ollama`（可用 `OLLAMA_MODELS_CACHE` 指定），只下载一次。

## 测试报告

每次推送 `main` 或章节 tag，GitHub Actions（`.github/workflows/verify.yml`）在带 Docker 的 runner 上跑完整的 `./mvnw -B verify`，Ollama 集成测试不跳过，模型用 `actions/cache` 缓存。报告发布到 `https://clarence404.github.io/mall-assistant/reports/<ref>/`，如 [`reports/main/`](https://clarence404.github.io/mall-assistant/reports/main/)、[`reports/ch01-done/`](https://clarence404.github.io/mall-assistant/reports/ch01-done/)、[`reports/ch01-start/`](https://clarence404.github.io/mall-assistant/reports/ch01-start/)。

`chNN-start` 标签的验收测试本来就不通过：报告照常发布，运行不标红，摘要里注明「start 标签：测试未通过是预期的」。其他 ref 测试失败时报告照样发布，运行记为失败。

## 目录结构

```text
mall-assistant/
├── src/main/java/io/github/clarence404/assistant/
│   ├── chat/                 ChatClient 配置、ChatService（意图 → 拒答或回答）、阻塞与 SSE 接口、事件定义
│   ├── prompt/               提示词模板：按版本选文件、渲染变量、拒答话术
│   ├── intent/               意图识别：IntentResult、结构化输出与重试兜底、/api/intent
│   ├── cli/                  命令行模型提供方：CliChatModel、claude / codex 的参数与输出解析、进程超时与清理
│   └── error/                模型失败分类（MODEL_UNAVAILABLE / MODEL_TIMEOUT）与 ProblemDetail
├── src/main/resources/
│   ├── application.yml       公共配置：超时、重试、ProblemDetail
│   ├── application-ollama.yml / application-openai.yml   按 profile 切换模型厂商
│   ├── application-claude-cli.yml / application-codex-cli.yml   用本机命令行工具当模型（可选）
│   ├── prompts/              客服人设 system-v1.st / system-v2.st、意图识别 intent-v1.st、拒答话术 refusal.st
│   └── static/index.html     聊天页面（原生 HTML/CSS/JS）
├── src/test/java/            FakeChatModel、单元与接口测试、录制回放、OllamaChatIT
├── src/test/resources/recordings/   真实模型的录制输出（录制回放用）
├── docs/chNN.md              每章的练习说明
├── application-local.example.yml   本地覆盖配置模板（复制为 application-local.yml，不入库）
├── .run/                     IntelliJ IDEA 共享运行配置
├── config/checkstyle/
└── .github/                  verify 工作流与报告脚本
```

## 许可证

[MIT](LICENSE)
