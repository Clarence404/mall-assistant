package io.github.clarence404.assistant.simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 调智能客服（和页面一样的接口）以及商城网关（只用来查自己的订单号，好问「我的订单 xxx 到哪了」）的薄封装。JDK 自带 HttpClient，不抛异常：
 * 连不上、超时都折成 {@link Answer#status()} = -1。
 */
final class AssistantClient {

    /**
     * 一轮问答的结果。
     *
     * @param status HTTP 状态码；流式接口固定 200（出错看 errorCode），-1 表示连不上或超时
     * @param errorCode 失败时的错误码（ProblemDetail 的 code 或流式 error 事件的 code），成功为 null
     * @param intent 识别出的意图
     * @param todayTokens 回答里带的今日用量（第 9 章 usage.todayTokens），没有为 -1
     * @param dailyBudget 回答里带的每日预算，没有为 -1
     * @param turnTokens 这一轮用了多少 Token（输入 + 输出），没有为 0
     * @param confirmation 待确认操作（第 7 章，Agent 提出写操作时），没有为 null
     */
    record Answer(
            int status,
            String errorCode,
            String intent,
            long todayTokens,
            long dailyBudget,
            long turnTokens,
            JsonNode confirmation) {

        boolean ok() {
            return status >= 200 && status < 300 && errorCode == null;
        }

        static Answer failed(int status, String code) {
            return new Answer(status, code, null, -1, -1, 0, null);
        }
    }

    private final String baseUrl;
    private final String mallBaseUrl;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    AssistantClient(String baseUrl, String mallBaseUrl) {
        this.baseUrl = trim(baseUrl);
        this.mallBaseUrl = trim(mallBaseUrl);
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    String baseUrl() {
        return baseUrl;
    }

    /**
     * 经智能客服登录商城（和页面右上角的登录一样）。
     *
     * @return 状态码与响应体；200 时 body 里有 accessToken、expiresIn
     */
    Response login(String phone, String password) {
        return send("POST", baseUrl + "/api/mall/login", null, Map.of("phone", phone, "password", password), 15);
    }

    /** 今日用量（第 9 章 GET /api/usage/me）。 */
    Response usage(String token) {
        return send("GET", baseUrl + "/api/usage/me", token, null, 15);
    }

    /** 商城里我的最近几单（经网关，用智能客服登录拿到的商城令牌）。 */
    Response myOrders(String token) {
        return send("GET", mallBaseUrl + "/api/orders?size=5", token, null, 10);
    }

    /** 确认或取消一个待确认操作（第 7 章）。 */
    Response decide(String token, String confirmationId, String decision, String conversationId) {
        return send(
                "POST",
                baseUrl + "/api/agent/confirmations/" + confirmationId,
                token,
                Map.of("decision", decision, "conversationId", conversationId),
                130);
    }

    /**
     * 阻塞接口 POST /api/chat：等整段回答。
     *
     * @param message 问题
     * @param conversationId 会话 ID，单轮时为 null
     * @param token 商城令牌，匿名为 null
     * @return 结果
     */
    Answer chat(String message, String conversationId, String token) {
        Response r = send("POST", baseUrl + "/api/chat", token, body(message, conversationId), 180);
        if (r.status() < 0) {
            return Answer.failed(-1, "IO_ERROR");
        }
        if (r.status() < 200 || r.status() >= 300) {
            String code = r.body() != null && r.body().has("code")
                    ? r.body().get("code").asString()
                    : "HTTP_" + r.status();
            return Answer.failed(r.status(), code);
        }
        JsonNode b = r.body();
        JsonNode usage = b == null ? null : b.get("usage");
        return new Answer(
                r.status(),
                null,
                b == null || !b.hasNonNull("intent")
                        ? null
                        : b.get("intent").path("intent").asString(null),
                longOf(usage, "todayTokens"),
                longOf(usage, "dailyBudget"),
                usage == null
                        ? 0
                        : usage.path("inputTokens").asLong(0)
                                + usage.path("outputTokens").asLong(0),
                b == null || !b.hasNonNull("confirmation") ? null : b.get("confirmation"));
    }

    /**
     * 流式接口 POST /api/chat/stream：逐行读 SSE，记下 intent、confirm、done（带 usage）或 error 事件。
     *
     * @param message 问题
     * @param conversationId 会话 ID
     * @param token 商城令牌
     * @return 结果；以 error 事件结束时 errorCode 是事件里的 code
     */
    Answer stream(String message, String conversationId, String token) {
        HttpRequest req = request("POST", baseUrl + "/api/chat/stream", token, body(message, conversationId), 180)
                .header("Accept", "text/event-stream")
                .build();
        try {
            HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() != 200) {
                resp.body().close();
                return Answer.failed(resp.statusCode(), "HTTP_" + resp.statusCode()); // 校验失败等，还没开始推流
            }
            List<String[]> events = new ArrayList<>();
            try (Stream<String> lines = resp.body()) {
                String event = null;
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:") && event != null) {
                        events.add(new String[] {event, line.substring(5).trim()});
                    } else if (line.isEmpty()) {
                        event = null;
                    }
                }
            }
            return fromEvents(events);
        } catch (IOException e) {
            return Answer.failed(-1, "IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.failed(-1, "INTERRUPTED");
        }
    }

    /**
     * 把一串 SSE 事件折成结果（单独拿出来方便测试）。
     *
     * @param events [事件名, data] 的列表
     * @return 结果；既没有 done 也没有 error 时算 STREAM_INCOMPLETE
     */
    Answer fromEvents(List<String[]> events) {
        String intent = null;
        JsonNode confirmation = null;
        for (String[] e : events) {
            JsonNode data = parse(e[1]);
            switch (e[0]) {
                case "intent" ->
                    intent = data == null ? null : data.path("intent").asString(null);
                case "confirm" -> confirmation = data;
                case "error" -> {
                    return new Answer(
                            200,
                            data == null ? "STREAM_ERROR" : data.path("code").asString("STREAM_ERROR"),
                            intent,
                            -1,
                            -1,
                            0,
                            null);
                }
                case "done" -> {
                    JsonNode usage = data == null ? null : data.get("usage");
                    return new Answer(
                            200,
                            null,
                            intent,
                            longOf(usage, "todayTokens"),
                            longOf(usage, "dailyBudget"),
                            usage == null
                                    ? 0
                                    : usage.path("inputTokens").asLong(0)
                                            + usage.path("outputTokens").asLong(0),
                            confirmation);
                }
                default -> {
                    // token / tool / source / memory / step / blocked：模拟器不关心内容
                }
            }
        }
        return Answer.failed(200, "STREAM_INCOMPLETE");
    }

    /**
     * 一个普通 JSON 响应。
     *
     * @param status 状态码，-1 表示连不上或超时
     * @param body 响应体 JSON，不是 JSON 时为 null
     */
    record Response(int status, JsonNode body) {

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private Response send(String method, String url, String token, Object body, int timeoutSeconds) {
        try {
            HttpResponse<String> resp = http.send(
                    request(method, url, token, body, timeoutSeconds).build(), HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), parse(resp.body()));
        } catch (IOException e) {
            return new Response(-1, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(-1, null);
        }
    }

    private HttpRequest.Builder request(String method, String url, String token, Object body, int timeoutSeconds) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .header("User-Agent", "assistant-simulator");
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return req;
    }

    private static Map<String, Object> body(String message, String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        return body;
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static long longOf(JsonNode node, String field) {
        return node == null || !node.hasNonNull(field) ? -1 : node.get(field).asLong();
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
