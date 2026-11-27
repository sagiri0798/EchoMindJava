package com.echomind.agent.communication;

import com.echomind.agent.AgentAiService;
import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.BillingAgent;
import com.echomind.agent.TechnicalAgent;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.tool.ToolRegistry;
import com.echomind.trace.BusMessageTrace;
import com.echomind.trace.RequestTraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end ASK flow with a scripted model: TECHNICAL asks BILLING, merges the reply into its answer. */
class AskFlowTest {

    private ChatResponse text(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).finishReason(FinishReason.STOP).build();
    }

    private ChatResponse call(String name, String arguments) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build())))
                .finishReason(FinishReason.TOOL_EXECUTION).build();
    }

    private ChatModel scriptedModel(AtomicInteger technicalCalls, AtomicInteger billingCalls) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                ChatMessage last = request.messages().getLast();
                String system = request.messages().stream().filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text()).findFirst().orElse("");
                if (last instanceof ToolExecutionResultMessage result) {
                    if (result.text().startsWith("对方结论")) {
                        assertTrue(result.text().contains("7 天"), result.text());
                        return text("错误码 E502 的排查步骤：重启后重试。退款部分依据对方结论处理。");
                    }
                    return text("该部分暂缺，建议人工核验。");
                }
                if (system.contains("技术支持专家")) {
                    technicalCalls.incrementAndGet();
                    return call("ask_agent", "{\"target_agent\":\"billing\",\"question\":\"该产品退款政策是什么？\"}");
                }
                if (system.contains("账单服务专家")) {
                    billingCalls.incrementAndGet();
                    return text("7 天内可申请退款。");
                }
                throw new IllegalStateException("unexpected agent: " + system);
            }
        };
    }

    private AgentRequest request(String message, Map<String, List<String>> entities) {
        return new AgentRequest(message, "u", "c", "", List.of(), entities, IntentCategory.TECHNICAL_LOGIN,
                "technical", UrgencyLevel.LOW, .99, "r1", new ArrayList<>());
    }

    private AgentOrchestrator orchestrator(ChatModel model, RequestTraceStore store) {
        var ai = new AgentAiService(model, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        return new AgentOrchestrator(null, Map.of(
                AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null)),
                AgentType.BILLING, List.of(new BillingAgent(ai, null))), store);
    }

    @Test void technicalAsksBillingAndMergesTheReply() {
        AtomicInteger technicalCalls = new AtomicInteger();
        AtomicInteger billingCalls = new AtomicInteger();
        var store = new RequestTraceStore();
        var result = orchestrator(scriptedModel(technicalCalls, billingCalls), store)
                .run(request("登录报错500", Map.of("error_code", List.of("E502"), "amount", List.of("99"))));
        assertEquals("success", result.status(), result.errorCode());
        assertEquals(AgentType.TECHNICAL, result.agentType());
        assertTrue(result.response().contains("依据对方结论"), result.response());
        assertEquals(1, technicalCalls.get());
        assertEquals(1, billingCalls.get());

        // The ask_agent tool call is traced with the agent_bus source.
        var askTrace = result.toolCalls().stream().filter(t -> t.toolName().equals("ask_agent")).findFirst();
        assertTrue(askTrace.isPresent());
        assertTrue(askTrace.get().success());
        assertEquals("agent_bus", askTrace.get().source());

        // The communication graph holds exactly one admitted ASK with a clean outcome.
        List<BusMessageTrace> messages = store.findBus("r1").orElse(List.of());
        assertEquals(1, messages.size(), messages.toString());
        BusMessageTrace message = messages.getFirst();
        assertEquals("ask", message.type());
        assertEquals("technical", message.sender());
        assertEquals("billing", message.receiver());
        assertEquals("ok", message.outcome());
    }

    @Test void unavailableTargetDegradesToUnsupportedMarkerInsteadOfFailing() {
        AtomicInteger technicalCalls = new AtomicInteger();
        AtomicInteger billingCalls = new AtomicInteger();
        var store = new RequestTraceStore();
        // Pool without BILLING: the ask target cannot be selected.
        var ai = new AgentAiService(scriptedModel(technicalCalls, billingCalls), new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null))), store);
        var result = orchestrator.run(request("登录报错500", Map.of("error_code", List.of("E502"), "amount", List.of("99"))));
        assertEquals("partial", result.status(), result.errorCode());
        assertTrue(result.response().contains("暂缺"), result.response());

        List<BusMessageTrace> messages = store.findBus("r1").orElse(List.of());
        assertEquals(1, messages.size(), messages.toString());
        assertEquals("unavailable", messages.getFirst().outcome());
    }

    @Test void cycleBackToAskerIsRefusedAndStamped() {
        var store = new RequestTraceStore();
        var model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                ChatMessage last = request.messages().getLast();
                String system = request.messages().stream().filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text()).findFirst().orElse("");
                if (last instanceof ToolExecutionResultMessage result) {
                    if (result.text().startsWith("对方结论")) return text("退款部分按对方结论处理。");
                    if (result.text().startsWith("未能获得对方结论")) return text("退款部分暂缺，建议人工核验。");
                    return text("结束。");
                }
                if (system.contains("技术支持专家")) return call("ask_agent", "{\"target_agent\":\"billing\",\"question\":\"退款政策？\"}");
                if (system.contains("账单服务专家")) return call("ask_agent", "{\"target_agent\":\"technical\",\"question\":\"技术排查？\"}");
                throw new IllegalStateException("unexpected agent: " + system);
            }
        };
        var ai = new AgentAiService(model, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(
                AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null)),
                AgentType.BILLING, List.of(new BillingAgent(ai, null))), store);
        var result = orchestrator.run(request("登录报错500", Map.of("error_code", List.of("E502"), "amount", List.of("99"))));
        assertTrue(result.status().equals("success") || result.status().equals("partial"), result.status());

        // TECHNICAL→BILLING admitted; BILLING→TECHNICAL refused as a cycle.
        List<BusMessageTrace> messages = store.findBus("r1").orElse(List.of());
        assertEquals(2, messages.size(), messages.toString());
        assertEquals("ok", messages.get(0).outcome(), messages.toString());
        assertEquals("cycle_detected", messages.get(1).outcome());
        assertEquals(1, messages.get(1).round());
    }
}
