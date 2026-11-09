package io.github.clarence404.assistant.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 关键词检索的「分词」：把一段文字切成检索用的词项（term），建索引和查询用同一套切法（第 5 章引入）。
 *
 * <p>所在层：RAG 的工具类。{@link KeywordIndex} 建索引、查询时都调用它。
 *
 * <p>中文没有空格分词，常见的轻量做法是<b>字符 n-gram</b>：不认词典，把相邻的 1 个字（单字）和 2 个字（双字）都当成词项。
 * 「质量问题」切成 质、量、问、题、质量、量问、问题；用户问「质量有问题」时，质量、问题 两个双字都能对上。单字召回多、区分度低，
 * 双字区分度高；两个都要，BM25 会按「在多少块里出现过」自动给常见的单字降权。真实项目可以换成 IK、HanLP、jieba 这类分词器，
 * 或者直接用数据库 / 搜索引擎的全文检索。
 *
 * <p>规则：
 *
 * <ul>
 *   <li>英文字母转小写，连续的字母、数字算一个词项（「618」「iphone」）
 *   <li>汉字：每个字一个单字词项，相邻两个字一个双字词项；{@link #STOP_CHARS} 里的字（的、了、吗……）不当单字，含有它们的双字也不要
 *   <li>其余字符（空格、标点、{@code >}）是分隔符：双字不跨过它们
 * </ul>
 */
public final class Terms {

    /**
     * 停用字：几乎每句话都有、对「讲的是哪件事」没帮助的字。去掉它们，「东西坏了能退吗」剩下的词项才都是有用的。
     * 只放虚词、语气词和人称；单字停用会连带去掉含它的双字，所以「问（问题）、过（过期）、地（地址）、不、没、能」这类会组成实词的字都不放。
     */
    static final Set<Character> STOP_CHARS = Set.of(
            '的', '了', '吗', '呢', '吧', '啊', '呀', '么', '嘛', '我', '你', '他', '她', '它', '们', '是', '和', '或', '请', '怎', '什',
            '哪', '这', '那', '些');

    private Terms() {}

    /**
     * 切词项，保留重复（BM25 要统计词频）。
     *
     * @param text 任意文字，可以为 null
     * @return 词项列表，按出现顺序；没有可用的词项时为空列表
     */
    public static List<String> of(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null) {
            return terms;
        }
        String t = text.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (isHan(c)) {
                if (!STOP_CHARS.contains(c)) {
                    terms.add(String.valueOf(c));
                    if (i + 1 < t.length() && isHan(t.charAt(i + 1)) && !STOP_CHARS.contains(t.charAt(i + 1))) {
                        terms.add(t.substring(i, i + 2));
                    }
                }
                i++;
            } else if (Character.isLetterOrDigit(c)) {
                int end = i;
                while (end < t.length() && Character.isLetterOrDigit(t.charAt(end)) && !isHan(t.charAt(end))) {
                    end++;
                }
                terms.add(t.substring(i, end));
                i = end;
            } else {
                i++;
            }
        }
        return terms;
    }

    /** 是不是汉字（CJK 统一表意文字）。 */
    static boolean isHan(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }
}
