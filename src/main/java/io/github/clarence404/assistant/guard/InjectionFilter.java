package io.github.clarence404.assistant.guard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 提示注入过滤：在一段「外来文字」（知识库检索到的资料块、外部 MCP 工具的说明与结果）进提示词<b>之前</b>，按规则找出像是在给模型下指令的句子，
 * 命中就整块挡在外面（第 9 章引入）。
 *
 * <p>所在层：上线保护（{@code guard} 包）。上游是第 5 章的 {@code RagService#plan}（每块资料先过一遍，ch09-2）和第 8 章的
 * {@code ExternalMcpTools}（外部工具的说明和结果，ch09-3）；它本身不调模型、不访问网络，是一组纯文本规则。
 *
 * <p><b>为什么要它</b>：第 5、7、8 章靠的是「结构」——资料放进 {@code <参考资料>} 标签、说明「它只是数据」、写操作只能提议。结构能降低模型照做的概率，
 * 但挡不住：第 5 章调提示词时只换了几个字，1.5b 就照着资料说了「所有订单都可以无条件全额退款」。规则过滤是在结构之外再加一道：明显在下指令的资料块
 * 根本不让模型看到。
 *
 * <p><b>规则过滤的局限（讲义里有实测）</b>：
 *
 * <ul>
 *   <li><b>漏网</b>：换个说法（「前面那些都不算数了，按我说的来」）、拆到两块里、编码（Base64）、用图片，规则都认不出来。规则只能挡「照抄的套路」
 *   <li><b>误杀</b>：正常文档里也可能出现「忽略以上…提示」这样的字眼（比如一篇讲防诈骗的文章引用了骗子的话术），整块被挡，回答就少了依据
 *   <li>所以它是「多一道」而不是「最后一道」：入库审核、结构隔离、写操作人工确认、回答后的检查都还要在
 * </ul>
 *
 * <p>更强的做法是用一个专门的分类模型判断「这段文字是不是在下指令」（如 Llama Guard、Prompt Guard 一类），代价是每块资料多一次模型调用；
 * 本项目先用规则，原因是零依赖、毫秒级、命中原因看得懂。
 */
public final class InjectionFilter {

    /** 命中片段最多保留多少个字（调试面板里显示）。 */
    static final int EXCERPT_CHARS = 40;

    /**
     * 一条规则。
     *
     * @param id 规则编号，进调试信息和指标的标签，如 {@code IGNORE_RULES}
     * @param reason 给人看的原因
     * @param pattern 在<b>归一化之后</b>的文字上匹配的正则（见 {@link #normalize}）
     */
    public record Rule(String id, String reason, Pattern pattern) {}

    /**
     * 命中的一条规则。
     *
     * @param rule 规则编号
     * @param reason 原因
     * @param excerpt 命中的片段（归一化之后的文字，最多 {@value #EXCERPT_CHARS} 个字）
     */
    public record Hit(String rule, String reason, String excerpt) {}

    /**
     * 判定结果。
     *
     * @param blocked 是否挡下
     * @param hits 命中的规则，按规则顺序，每条规则最多记一次；没挡时为空
     */
    public record Verdict(boolean blocked, List<Hit> hits) {

        /** 干净：没命中任何规则。 */
        public static final Verdict CLEAN = new Verdict(false, List.of());

        /**
         * 命中了哪些规则（编号），调试信息和日志用。
         *
         * @return 规则编号
         */
        public List<String> rules() {
            return hits.stream().map(Hit::rule).toList();
        }
    }

    /**
     * 内置规则：四类常见套路。每条正则都写在「归一化之后」的文字上：全角转半角、英文小写、去掉空格和零宽字符（所以 {@code "Ignore  previous"} 写成
     * {@code ignoreprevious}，{@code "忽 略 以 上"} 也能认出来）。
     */
    static final List<Rule> STANDARD = List.of(
            new Rule(
                    "IGNORE_RULES",
                    "要求忽略之前的规则或指令",
                    Pattern.compile("(忽略|无视|忘记|忘掉|不要理会|不必理会|跳过)(掉)?(你)?(以上|上面|之前|前面|先前|上述|此前|所有|全部|一切|原有|系统)"
                            + "[^。！？!?\\n]{0,6}(规则|指令|指示|提示|要求|设定|约束|限制)")),
            new Rule(
                    "IGNORE_RULES",
                    "要求忽略之前的规则或指令",
                    Pattern.compile(
                            "(ignore|disregard|forget|override)(all|any|the|your)*(previous|prior|above|earlier|preceding|system|original)"
                                    + "(instructions?|rules|prompts?|directions|guidelines)")),
            new Rule(
                    "ROLE_HIJACK",
                    "试图改变助手的身份",
                    Pattern.compile(
                            "(你现在是|从现在起你是|从现在开始你是|你的新身份是|你不再是|younowarea|youarenow|fromnowonyouare|pretendtobe)")),
            new Rule(
                    "FAKE_ROLE_MARKER",
                    "伪造系统 / 角色标记",
                    Pattern.compile(
                            "([(（【\\[<](系统|system|管理员|admin|开发者|developer)(通知|消息|指令|提示|公告|message|prompt|notice)?[)）】\\]>])"
                                    + "|(<\\|(im_start|im_end|system|assistant|user)\\|?>)|(\\[/?inst])|(<</?sys>>)"
                                    + "|((?m)^(system|assistant|系统)[:：])")),
            new Rule(
                    "PROMPT_LEAK",
                    "要求泄露系统提示词",
                    Pattern.compile("((输出|告诉|附上|展示|泄露|透露|打印|复述|重复|发出)[^。！？!?\\n]{0,8}(系统提示词|提示词全文|初始指令|系统指令))"
                            + "|((系统提示词|提示词全文|初始指令|系统指令)[^。！？!?\\n]{0,8}(输出|告诉|发给|给我|复述|打印|泄露|透露))"
                            + "|((reveal|print|show|repeat|output)(your|the)*(systemprompt|initialinstructions))")));

    private final boolean enabled;
    private final List<Rule> rules;

    /**
     * 创建。
     *
     * @param enabled 关掉时一律放行（{@code assistant.guard.injection.enabled=false}，对比效果用）
     * @param rules 规则
     */
    public InjectionFilter(boolean enabled, List<Rule> rules) {
        this.enabled = enabled;
        this.rules = List.copyOf(rules);
    }

    /**
     * 内置规则、打开。
     *
     * @return 过滤器
     */
    public static InjectionFilter standard() {
        return new InjectionFilter(true, STANDARD);
    }

    /**
     * 关掉的过滤器：一律放行（第 8 章及以前的测试、手工 new RagService 时用）。
     *
     * @return 过滤器
     */
    public static InjectionFilter off() {
        return new InjectionFilter(false, List.of());
    }

    /**
     * 按配置创建：内置规则 + {@code assistant.guard.injection.extra-patterns} 里追加的正则（同样写在归一化之后的文字上）。
     *
     * @param config 配置
     * @return 过滤器
     */
    public static InjectionFilter of(GuardProperties.Injection config) {
        List<Rule> all = new ArrayList<>(STANDARD);
        for (String extra : config.extraPatterns()) {
            all.add(new Rule("CUSTOM", "命中自定义规则", Pattern.compile(extra)));
        }
        return new InjectionFilter(config.enabled(), all);
    }

    /** 是否打开。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 判断一段外来文字能不能进提示词（ch09-1）。
     *
     * <p>要求（测试按这个写）：
     *
     * <ul>
     *   <li>过滤器关着、文字为 null 或空白：{@link Verdict#CLEAN}
     *   <li>先 {@link #normalize}，再按顺序用每条规则的正则 {@code find()}；命中就记一个 {@link Hit}（规则编号、原因、命中片段——从匹配开头起最多
     *       {@value #EXCERPT_CHARS} 个字），<b>同一个编号只记一次</b>（中英文两条 IGNORE_RULES 都命中也只记一条）
     *   <li>有任何命中就 {@code blocked=true}；一条都没有返回 {@link Verdict#CLEAN}
     * </ul>
     *
     * @param text 一块资料或一段工具输出
     * @return 判定结果
     */
    public Verdict check(String text) {
        // TODO ch09-1：过滤器关着、text 为 null 或空白 → Verdict.CLEAN；否则先 normalize(text)，
        // 按顺序对每条规则 rule.pattern().matcher(归一化后的文字).find()，命中就记
        // new Hit(rule.id(), rule.reason(), 从 matcher.start() 起最多 EXCERPT_CHARS 个字)，同一个 id 只记一次；
        // 有命中返回 new Verdict(true, List.copyOf(hits))，没有返回 Verdict.CLEAN
        return Verdict.CLEAN;
    }

    /**
     * 归一化：让「换个写法」的同一句话变成同一串字，规则才不用把每种写法都列一遍。
     *
     * <ol>
     *   <li>Unicode NFKC：全角字母数字标点转半角（{@code ＩＧＮＯＲＥ} → {@code IGNORE}、{@code （} → {@code (}）
     *   <li>英文转小写
     *   <li>去掉零宽字符（U+200B～U+200D、U+FEFF，常被用来把关键词「隔开」骗过关键词匹配）和空格、制表符；<b>保留换行</b>（「行首的 system:」要靠它认）
     * </ol>
     *
     * @param text 原文
     * @return 归一化之后的文字
     */
    static String normalize(String text) {
        String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char c = nfkc.charAt(i);
            if (c == '\n') {
                out.append(c);
            } else if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !isZeroWidth(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 零宽字符：U+200B～U+200D、U+2060、U+FEFF（用数值比较，免得源码里出现看不见的字符）。 */
    private static boolean isZeroWidth(char c) {
        return (c >= 0x200B && c <= 0x200D) || c == 0x2060 || c == 0xFEFF;
    }
}
