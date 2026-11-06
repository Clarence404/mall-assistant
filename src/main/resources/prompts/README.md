# 提示词模板

这个目录下的 `.st` 文件会原样（填好变量后）发给模型或返回给用户，**模板文件里不要写注释**，否则注释也会一起发出去。说明都写在这里；本文件不会被当成模板加载（代码只按确定的文件名读取，见下表）。

| 文件 | 用途 | 谁加载 | 变量 |
|------|------|--------|------|
| `system-v1.st` / `system-v2.st` / `system-v3.st` | 回答用的系统提示词：客服人设与规则 | `AssistantPrompts#systemTemplate`，按 `assistant.prompt.version` 读 `system-<version>.st`（默认 v3） | `{shopName}` `{assistantName}` `{supportScope}`，v2、v3 另有 `{today}` |
| `refusal.st` | 超出范围时的拒答话术，直接返回给用户，不发给模型 | `AssistantPrompts#refusal` | `{shopName}` `{assistantName}` `{supportScope}` |
| `intent-v1.st` | 意图识别的系统提示词：6 个取值的说明 + 示例 | `IntentService` 构造参数 `classpath:prompts/intent-v1.st` | 无 |

- 占位符是 StringTemplate 语法 `{name}`，变量值来自 `application.yml` 的 `assistant.prompt.*`（「今天」每次请求时计算）。模板里出现了占位符却没给值，渲染时会报错；所以正文里不能随手写花括号（`intent-v1.st` 示例里的 JSON 花括号能用，是因为它作为 `.system(Resource)` 传入时没有设置任何参数，不经过渲染）。
- 改系统提示词时新增一个版本（如 `system-v3.st`）而不是直接改旧文件，用 `assistant.prompt.version` 切换，效果不好可以立刻切回；v2 相比 v1 加了日期、更严格的「不编造」规则和 few-shot 示例。
- v3（第 3 章）：先调工具查，订单、商品信息只能来自工具结果或「查询结果」，没查到就照实说。它比 v2 短得多，而且去掉了 few-shot 示例，原因是实测：按 v2 的写法（长规则 + 示例）写的初稿让 qwen2.5:1.5b 8 条消息一个工具都没调（直接凭空回答，甚至照抄示例里的「待发货」），压短之后对了 7 条，见 `docs/ch03.md`。
- 改 `intent-v1.st` 会让录制回放的指纹对不上，需要重新录制，见 `docs/ch02.md`；改 `system-v3.st` 或工具的说明会让第 3 章工具决策的录制对不上，见 `docs/ch03.md`。
