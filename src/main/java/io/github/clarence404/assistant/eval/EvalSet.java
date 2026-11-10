package io.github.clarence404.assistant.eval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.Resource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一份版本化的回归评测集：JSONL 文件（一行一条 {@link EvalCase}）+ 由内容算出的版本号（第 9 章引入）。
 *
 * <p>所在层：评测。{@link EvalRunner} 跑它；报告和基线里都记着它的名字和版本，{@link EvalComparison} 发现版本不同就拒绝比较。
 *
 * <p><b>为什么要「版本化」</b>：评测集本身会改——加用例、改期望、删掉过时的。如果拿「改过的评测集」跑出的通过率和「旧评测集」的基线比，
 * 涨了跌了都说明不了问题。所以版本号由文件内容算（SHA-256 的前 12 位），改一个字就是新版本，必须重新生成基线；文件本身进 git，谁改了什么一目了然。
 *
 * <p>为什么用 JSONL 而不是一个大 JSON 数组：一行一条，加一条就是加一行，{@code git diff} 只显示那一行；也方便用 {@code grep}、{@code jq -c} 挑出一类来看。
 */
public record EvalSet(String name, String version, List<EvalCase> cases) {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS) // 文件里写小写的 "rag"，读成 Category.RAG
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) // 没写 "login" 就是 false（Jackson 3 默认会报错）
            .build();

    /**
     * 从文件读。
     *
     * @param resource JSONL 文件（如 {@code classpath:eval/cases-v1.jsonl}）
     * @return 评测集，名字是文件名
     * @throws IllegalArgumentException 格式不对、编号重复、某条没有期望
     */
    public static EvalSet load(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return parse(resource.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("读不到评测集 " + resource, e);
        }
    }

    /**
     * 解析 JSONL 文本：空行跳过；每行一条，编号不能重复，必须有类别、问题和至少一项期望。
     *
     * @param name 名字
     * @param content 文件内容
     * @return 评测集
     * @throws IllegalArgumentException 哪一行有问题（带行号）
     */
    public static EvalSet parse(String name, String content) {
        String normalized = content.replace("\r\n", "\n");
        List<EvalCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String[] lines = normalized.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            EvalCase c;
            try {
                c = JSON.readValue(line, EvalCase.class);
            } catch (JacksonException e) {
                throw new IllegalArgumentException(name + " 第 " + (i + 1) + " 行不是合法的用例：" + e.getOriginalMessage(), e);
            }
            if (c.id() == null
                    || c.category() == null
                    || c.message() == null
                    || c.message().isBlank()) {
                throw new IllegalArgumentException(name + " 第 " + (i + 1) + " 行缺 id / category / message");
            }
            if (c.expect() == null || c.expect().isEmpty()) {
                throw new IllegalArgumentException(name + " 第 " + (i + 1) + " 行（" + c.id() + "）没有任何期望");
            }
            if (!ids.add(c.id())) {
                throw new IllegalArgumentException(name + " 第 " + (i + 1) + " 行：编号 " + c.id() + " 重复");
            }
            cases.add(c);
        }
        return new EvalSet(name, version(normalized), List.copyOf(cases));
    }

    /** 版本号：内容的 SHA-256 前 12 个十六进制字符（换行统一成 \n，Windows 上检出也一样）。 */
    static String version(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
