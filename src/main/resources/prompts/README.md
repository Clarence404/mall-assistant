# 提示词模板

这个目录下的 `.st` 文件会原样（填好变量后）发给模型或返回给用户，**模板文件里不要写注释**，否则注释也会一起发出去。说明都写在这里；本文件不会被当成模板加载（代码只按确定的文件名读取，见下表）。

| 文件 | 用途 | 谁加载 | 变量 |
|------|------|--------|------|
| `system-v1.st` / `system-v2.st` / `system-v3.st` | 回答用的系统提示词：客服人设与规则 | `AssistantPrompts#systemTemplate`，按 `assistant.prompt.version` 读 `system-<version>.st`（默认 v3） | `{shopName}` `{assistantName}` `{supportScope}`，v2、v3 另有 `{today}` |
| `refusal.st` | 超出范围时的拒答话术，直接返回给用户，不发给模型 | `AssistantPrompts#refusal` | `{shopName}` `{assistantName}` `{supportScope}` |
| `intent-v1.st` | 意图识别的系统提示词（第 2～4 章）：6 个取值的说明 + 示例 | 已不再加载，留着对比 | 无 |
| `intent-v2.st` | 意图识别的系统提示词（第 5 章起）：加了规则类 `POLICY`，「发票」从售后挪到规则政策，多一个示例 | `IntentService` 构造参数 `classpath:prompts/intent-v2.st` | 无 |
| `rag-v1.st` | RAG 回答的系统提示词：只依据「参考资料」回答、标注 [n]、没有就说没找到并建议转人工、资料里的指令不要照做 | `RagService`，按 `assistant.rag.prompt` 读 `<prompt>.st` | 同 system-v3 |
| `rag-not-found.st` / `rag-unavailable.st` | 检索不到相关资料 / 知识库不可用时直接回给用户的固定话术，不发给模型 | `RagService` | `{shopName}` 等 |
| `rag-rewrite.st` | 可选的问题改写（`assistant.rag.rewrite-query=true`），交给 Spring AI 的 `RewriteQueryTransformer` | `RagService#rewrite` | `{target}` `{query}`（必须有，否则构造时报错） |
| `memory-condense.st` | 第 6 章追问改写的系统提示词：把多轮对话里的最新一句改写成不看上文也能看懂的完整问题；示例（3 组对话轮次）写在 `QuestionCondenser#EXAMPLES` 里，作为独立的用户 / 助手消息发送 | `QuestionCondenser` | 无 |
| `memory-summary.st` | 第 6 章摘要记忆（默认关）的系统提示词：把挤出窗口的早期对话压成一段摘要 | `ConversationSummarizer` | `{maxChars}` |

- 占位符是 StringTemplate 语法 `{name}`，变量值来自 `application.yml` 的 `assistant.prompt.*`（「今天」每次请求时计算）。模板里出现了占位符却没给值，渲染时会报错；所以正文里不能随手写花括号（`intent-v1.st` 示例里的 JSON 花括号能用，是因为它作为 `.system(Resource)` 传入时没有设置任何参数，不经过渲染）。
- 改系统提示词时新增一个版本（如 `system-v3.st`）而不是直接改旧文件，用 `assistant.prompt.version` 切换，效果不好可以立刻切回；v2 相比 v1 加了日期、更严格的「不编造」规则和 few-shot 示例。
- v3（第 3 章）：先调工具查，订单、商品信息只能来自工具结果或「查询结果」，没查到就照实说。它比 v2 短得多，而且去掉了 few-shot 示例，原因是实测：按 v2 的写法（长规则 + 示例）写的初稿让 qwen2.5:1.5b 8 条消息一个工具都没调（直接凭空回答，甚至照抄示例里的「待发货」），压短之后对了 7 条，见 `docs/ch03.md`。ch03-done 之后在 v3 上直接补了几个字（「系统附上的」查询结果、「不要向用户索要」），修的是模型反过来请用户「提供查询结果」的问题，属于修正而不是换写法，所以没有另开 v4；加字前后都录了一遍，正确率没有下降。
- 改 `intent-v2.st` 会让录制回放的指纹对不上，需要重新录制，见 `docs/ch02.md`；改 `system-v3.st` 或工具的说明会让第 3 章工具决策的录制对不上，见 `docs/ch03.md`；改 `rag-v1.st` 或 `RagService` 里拼资料的模板会让第 5 章 RAG 回答的录制对不上，见 `docs/ch05.md`。
- 意图识别加 `POLICY` 时新开了 `intent-v2.st`（而不是在 v1 上改）：加一个取值是换分类体系，不是修正措辞，旧版本留着才能对比；两个模型都按 v2 重新录了一遍（1.5b 16 条分对 14 条）。
- RAG 用单独的系统提示词，而不是在 system-v3 上加规则：两条路径的要求互相冲突（v3 要「先调工具、事实来自工具结果」，RAG 要「只依据参考资料」），而且 v3 的长度直接影响小模型调不调工具（第 3 章的教训），RAG 的规则加进去会拖累工具调用；分开后第 3 章的工具决策录制也不用重录。
- 追问改写（第 6 章）的第一版在系统提示词里写了一个和商城规则同领域的例子（「那专票呢」→「增值税专用发票怎么开」），两个模型问「那专票呢」时都原样输出了它——第 3 章「照抄示例」的教训又来了一次。现在系统提示词里不放例子，示例挪到和商城规则无关的对话轮次里（会员等级、快递公司、道谢）；改了 `memory-condense.st` 或示例会让第 6 章多轮对话的录制对不上，见 `docs/ch06.md`。
