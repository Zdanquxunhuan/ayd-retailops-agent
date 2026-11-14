package com.yu.retailops.adapter;

import com.yu.retailops.domain.Diagnosis.*;
import com.yu.retailops.runtime.Planner;
import com.yu.retailops.runtime.InvestigationPlanner;
import com.yu.retailops.domain.Investigation;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * JSON-mode model adapter. JSON syntax is insufficient: Runtime validates semantics locally.
 */
public class JsonModelPlanner implements Planner, InvestigationPlanner, com.yu.retailops.runtime.DiagnosisReviewer {
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;
    private final String model, apiKey;
    private final String prompt, investigationPrompt, reviewPrompt;
    private final RemoteRequestBudget budget;

    public JsonModelPlanner(String endpoint, String model, String apiKey) throws Exception {
        this(endpoint, model, apiKey, 10);
    }

    public JsonModelPlanner(String endpoint, String model, String apiKey, int requestLimit) throws Exception {
        this.budget = new RemoteRequestBudget(requestLimit);
        this.endpoint = URI.create(endpoint);
        this.model = model;
        this.apiKey = apiKey;
        if (!"https".equals(this.endpoint.getScheme()) || model.isBlank() || apiKey.isBlank())
            throw new IllegalArgumentException("HTTPS endpoint, model name and API key are required for the real model adapter");
        prompt = loadPrompt("/prompts/order-diagnosis-v3.txt");
        investigationPrompt = loadPrompt("/prompts/order-investigation-v13.txt");
        reviewPrompt = loadPrompt("/prompts/order-semantic-review-v2.txt");
    }

    private String loadPrompt(String resource) throws Exception {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing prompt");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static JsonModelPlanner forQwen(String endpoint, String model, String dashscopeKey, int limit) throws Exception {
        URI uri = URI.create(endpoint);
        if (!isBeijingQwenHost(uri.getHost()) || uri.getUserInfo() != null
                || !"/compatible-mode/v1/chat/completions".equals(uri.getPath())
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1)
            throw new IllegalArgumentException("Qwen preset requires the official Beijing endpoint");
        return new JsonModelPlanner(endpoint, model, dashscopeKey, limit);
    }

    private static boolean isBeijingQwenHost(String host) {
        return host != null && (host.equals("dashscope.aliyuncs.com")
                || host.matches("[a-zA-Z0-9-]+\\.cn-beijing\\.maas\\.aliyuncs\\.com"));
    }

    Map<String, Object> requestBody(Context context) {
        return requestBody(context, prompt, 1200);
    }

    Map<String, Object> investigationRequestBody(Investigation.Context context) {
        return requestBody(context, investigationPrompt, 2400);
    }

    private Map<String, Object> requestBody(Object context, String systemPrompt, int maxTokens) {
        var body = new HashMap<String, Object>(Map.of("model", model, "temperature", 0, "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", json.writeValueAsString(context)))));
        if ("api.deepseek.com".equals(endpoint.getHost())) body.put("thinking", Map.of("type", "disabled"));
        if (isBeijingQwenHost(endpoint.getHost())) body.put("enable_thinking", false);
        return body;
    }

    public ModelReply next(Context context) throws Exception {
        return decodeResponse(send(requestBody(context)));
    }

    public Investigation.Reply investigate(Investigation.Context context) throws Exception {
        var decoded = decode(send(investigationRequestBody(context)), InvestigationDelta.class);
        return new Investigation.Reply(decoded.value().expand(context), decoded.promptTokens(), decoded.completionTokens());
    }

    public ReviewReply review(Investigation.Context context, Investigation.Proposal proposal) throws Exception {
        var decoded = decode(send(requestBody(Map.of("context", context, "proposal", proposal),
                reviewPrompt, 1600)), ReviewResult.class);
        return new ReviewReply(decoded.value(), decoded.promptTokens(), decoded.completionTokens());
    }

    private byte[] send(Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        budget.reserve();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new ModelCallException("MODEL_HTTP_" + response.statusCode());
            byte[] bytes = stream.readNBytes(262145);
            if (bytes.length > 262144) throw new IllegalStateException("MODEL_RESPONSE_TOO_LARGE");
            return bytes;
        }
    }

    static ModelReply decodeResponse(byte[] bytes) {
        var decoded = decode(bytes, Decision.class);
        return new ModelReply(decoded.value(), decoded.promptTokens(), decoded.completionTokens());
    }

    record Decoded<T>(T value, long promptTokens, long completionTokens) {
    }

    static <T> Decoded<T> decode(byte[] bytes, Class<T> type) {
        JsonMapper strictJson = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var root = strictJson.readTree(bytes);
        var choice = root.path("choices").path(0);
        if (!"stop".equals(choice.path("finish_reason").asText()))
            throw new ModelCallException("MODEL_OUTPUT_INCOMPLETE");
        String content = choice.path("message").path("content").asText();
        if (content == null || content.isBlank()) throw new ModelCallException("MODEL_OUTPUT_EMPTY");
        T decision;
        try {
            decision = strictJson.readValue(content, type);
        } catch (RuntimeException invalid) {
            if (Boolean.getBoolean("omniops.capture-model-output")) {
                try {
                    var dir = java.nio.file.Path.of("evaluation-output/model-format-diagnostics");
                    java.nio.file.Files.createDirectories(dir);
                    java.nio.file.Files.writeString(dir.resolve(UUID.randomUUID() + ".txt"), content);
                } catch (java.io.IOException ignored) { /* Diagnostic capture must not change rejection. */ }
            }
            throw new ModelCallException("MODEL_OUTPUT_INVALID_JSON_OR_SCHEMA");
        }
        return new Decoded<>(decision, root.path("usage").path("prompt_tokens").asLong(0),
                root.path("usage").path("completion_tokens").asLong(0));
    }

    public String provider() {
        return isBeijingQwenHost(endpoint.getHost()) ? "QWEN_JSON_MODEL" : "REMOTE_JSON_MODEL";
    }

    public String modelVersion() {
        return model;
    }

    public int requestsUsed() {
        return budget.used();
    }
}

