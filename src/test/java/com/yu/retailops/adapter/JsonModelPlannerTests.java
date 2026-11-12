package com.yu.retailops.adapter;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonModelPlannerTests {
    @Test void qwenUsesOwnNonThinkingProtocolAndNoSecretInPayload() throws Exception {
        var planner = JsonModelPlanner.forQwen("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-flash", "test-only", 1);
        var body = planner.requestBody(new com.yu.retailops.runtime.Planner.Context("query", "A001", "O100", List.of(), 12));
        assertEquals(false, body.get("enable_thinking"));
        assertFalse(body.containsKey("thinking"));
        assertEquals("qwen-flash", body.get("model"));
        assertFalse(body.toString().contains("test-only"));
        assertEquals("QWEN_JSON_MODEL", planner.provider());
        assertEquals(0, planner.requestsUsed());
    }
    @Test void qwenRejectsMissingKeyAndUntrustedDestination() {
        assertThrows(IllegalArgumentException.class, () -> JsonModelPlanner.forQwen(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-flash", "", 1));
        assertThrows(IllegalArgumentException.class, () -> JsonModelPlanner.forQwen(
                "https://api.deepseek.com/compatible-mode/v1/chat/completions", "qwen-flash", "test-only", 1));
        assertThrows(IllegalArgumentException.class, () -> JsonModelPlanner.forQwen(
                "https://dashscope.aliyuncs.com.example.org/compatible-mode/v1/chat/completions", "qwen-flash", "test-only", 1));
    }
    byte[] response(String content,String finish) {
        return JsonMapper.builder().build().writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",finish,
                "message",Map.of("content",content))),"usage",Map.of("prompt_tokens",32,"completion_tokens",12)));
    }
    @Test void validStructuredResponseAndUsageAreParsed() {
        var reply=JsonModelPlanner.decodeResponse(response("""
                {"type":"CALL_TOOL","tool":"pos.order.query","arguments":{"storeId":"A001","orderId":"O100"},"outcome":null,"evidenceIds":[],"reasonSummary":"query"}
                ""","stop"));
        assertEquals("pos.order.query",reply.decision().tool()); assertEquals(32,reply.promptTokens());
    }
    @Test void extraFieldsAndTruncatedOutputAreRejected() {
        assertThrows(RuntimeException.class,()->JsonModelPlanner.decodeResponse(response("{\"type\":\"FINISH\",\"overridePermission\":true}","stop")));
        assertThrows(RuntimeException.class,()->JsonModelPlanner.decodeResponse(response("{}","length")));
        assertThrows(RuntimeException.class,()->JsonModelPlanner.decodeResponse(response("not JSON","stop")));
    }
    @Test void realProviderNeverFallsBackWhenMisconfigured() {
        assertThrows(IllegalArgumentException.class,()->new JsonModelPlanner("https://example.invalid","",""));
        assertThrows(IllegalArgumentException.class,()->new JsonModelPlanner("http://example.invalid","model","test"));
    }
    @Test void concatenatedDecisionsAreRejectedRatherThanExecutingFirstObject() {
        String content = """
                {"type":"CALL_TOOL","tool":"pos.order.query","arguments":{"storeId":"A001","orderId":"O100"},"outcome":null,"evidenceIds":[],"reasonSummary":"query"}
                """;
        var failure = assertThrows(ModelCallException.class, () -> JsonModelPlanner.decodeResponse(response(content + content,"stop")));
        assertEquals("MODEL_OUTPUT_INVALID_JSON_OR_SCHEMA", failure.code());
        assertEquals("MODEL_OUTPUT_EMPTY", assertThrows(ModelCallException.class,
                () -> JsonModelPlanner.decodeResponse(response("   ", "stop"))).code());
    }
    @Test void investigationSchemaHasNoModelControlledBusinessOutcomeOrConfirmedRootCause() {
        String valid = """
                {"action":"NEEDS_HUMAN","reasonSummary":"Missing history","hypothesisUpdates":[],"query":null,
                 "claims":[{"text":"unknown","evidenceIds":[],"qualification":"UNKNOWN"}],"unresolvedQuestions":["history"]}
                """;
        var reply = JsonModelPlanner.decode(response(valid, "stop"), com.yu.retailops.domain.Investigation.Proposal.class);
        assertEquals(com.yu.retailops.domain.Investigation.Action.NEEDS_HUMAN, reply.value().action());
        assertEquals(32, reply.promptTokens());
        assertThrows(ModelCallException.class, () -> JsonModelPlanner.decode(response(valid.replace("UNKNOWN", "CONFIRMED"), "stop"),
                com.yu.retailops.domain.Investigation.Proposal.class));
        assertThrows(ModelCallException.class, () -> JsonModelPlanner.decode(response(valid.replace("\"action\":", "\"outcome\":\"ALREADY_SYNCED\",\"action\":"), "stop"),
                com.yu.retailops.domain.Investigation.Proposal.class));
    }
}
