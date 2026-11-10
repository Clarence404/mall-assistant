package io.github.clarence404.assistant.ops;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

/**
 * 模型没报告用量时，按字数估算 Token（第 9 章引入）。
 *
 * <p>所在层：上线运维的工具类。{@link UsageAdvisor} 在响应里没有 usage（命令行模型的流式输出、假模型、某些兼容接口）时用它。
 *
 * <p>系数是拿 qwen2.5 的分词器实测的（讲义「Token 用量」一节）：一段 121 个汉字的规则原文是 87 个 Token（约 0.7 个 / 字）；132 个字母的英文是
 * 27 个 Token（约 4.9 个字母 / 个）；数字是一位一个 Token（订单号 23 位就是 23 个）。每条消息还有角色标记的开销（{@code <|im_start|>user} 之类），
 * 按每条 4 个算。不同模型的分词器不一样（GPT、Claude 的中文大约 1～1.5 个 Token / 字），所以估算只能用来看「大概多少、哪里多」，
 * 记账和预算以模型报告的为准；估出来的数在调试面板和接口里都标着「估算」。
 */
public final class TokenEstimator {

    /** 每个汉字（含全角标点）约多少个 Token。 */
    static final double PER_CJK = 0.7;

    /** 其他字符（字母、半角标点、空格）多少个算一个 Token。 */
    static final double OTHER_PER_TOKEN = 4.5;

    /** 每条消息的角色标记开销。 */
    static final int PER_MESSAGE = 4;

    private TokenEstimator() {}

    /**
     * 估算一段文字的 Token 数（ch09-4 之一）。
     *
     * <p>要求（测试按这个写）：null 或空串是 0；否则逐个字符数三类——汉字和全角标点（{@link #isCjk}）每个 {@link #PER_CJK}，数字 0～9 每个 1，
     * 其他每 {@link #OTHER_PER_TOKEN} 个算 1——三类加起来<b>向上取整</b>。用整数算（都乘以 90：汉字 63、数字 90、其他 20，再向上整除 90），
     * 免得 {@code 10 * 0.7} 这种浮点数算出 7.000000000000001、向上取整成了 8。
     *
     * @param text 文字
     * @return 估算的 Token 数
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int digits = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (c >= '0' && c <= '9') {
                digits++;
            } else {
                other++;
            }
        }
        long ninetieths = cjk * 63L + digits * 90L + other * 20L;
        return (int) ((ninetieths + 89) / 90);
    }

    /**
     * 估算一组消息（一次请求的提示词）：每条消息的文字估算加上 {@link #PER_MESSAGE}。
     *
     * @param messages 消息
     * @return 估算的 Token 数
     */
    public static int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimate(message.getText()) + PER_MESSAGE;
        }
        return total;
    }

    /** 汉字（CJK 统一表意文字）、中文标点（U+3000～U+303F）、全角字符（U+FF00～U+FFEF）。 */
    static boolean isCjk(char c) {
        // 用数值比较而不是 Unicode 转义的字符字面量：palantir 格式化会把转义改写成原字符，全角空格、零宽字符之类肉眼看不清
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
