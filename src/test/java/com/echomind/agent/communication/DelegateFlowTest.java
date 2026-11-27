package com.echomind.agent.communication;

import com.echomind.agent.AgentAiService;
import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.GeneralAgent;
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

import static org.junit.jupiter.api.Assertions.*;

/** DELEGATE flows: triage transfer executes for real, escalation hands over the specialist's context. */
class DelegateFlowTest {

    private ChatResponse text(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).finishReason(FinishReason.STOP).build();
    }

    private ChatResponse call(String name, String arguments) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build())))
                .finishReason(FinishReason.TOOL_EXECUTION).build();
    }

    private ChatModel model(java.util.function.Function<String, String> generalAnswer, java.util.function.Function<String, String> technicalAnswer) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                ChatMessage last = request.messages().getLast();
                String system = request.messages().stream().filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text()).findFirst().orElse("");
                if (last instanceof ToolExecutionResultMessage) return text("好的，正在处理。");
                if (system.contains("智能客服")) return call("delegate_agent", generalAnswer.apply(system));
                if (system.contains("技术支持专家")) return text(technicalAnswer.apply(system));
                throw new IllegalStateException("unexpected agent: " + system);
            }
        };
    }

    private AgentRequest generalRequest(String message) {
        return new AgentRequest(message, "u", "c", "", List.of(), Map.of(), IntentCategory.QUERY,
                "general", UrgencyLevel.LOW, .95, "r2", new ArrayList<>());
    }

    private AgentOrchestrator orchestrator(ChatModel model, RequestTraceStore store, Map<AgentType, List<com.echomind.agent.BaseAgent>> pool) {
        var ai = new AgentAiService(model, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        return new AgentOrchestrator(null, pool, store);
    }

    @Test void generalDelegatesTechnicalAndDelegateeBecomesResponder() {
        var store = new RequestTraceStore();
        var ai = new AgentAiService(model(s -> "{\"target_agent\":\"technical\",\"reason\":\"技术排查\"}", s -> "已排查：重启后即可恢复。"),
                new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(ai, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null))), store);
        var result = orchestrator.run(generalRequest("帮我处理技术问题"));
        assertEquals("success", result.status(), result.errorCode());
        assertEquals(AgentType.TECHNICAL, result.agentType());
        assertTrue(result.response().contains("重启后即可恢复"), result.response());

        List<BusMessageTrace> messages = store.findBus("r2").orElse(List.of());
        assertEquals(1, messages.size(), messages.toString());
        assertEquals("delegate", messages.getFirst().type());
        assertEquals("ok", messages.getFirst().outcome());
        assertEquals("general", messages.getFirst().sender());
        assertEquals("technical", messages.getFirst().receiver());
    }

    @Test void failedDelegationFallsBackToDelegatorsOwnAnswer() {
        var store = new RequestTraceStore();
        var ai = new AgentAiService(model(s -> "{\"target_agent\":\"billing\",\"reason\":\"账单咨询\"}", s -> "不应到达"),
                new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        // Pool without BILLING: the delegatee cannot be selected.
        var orchestrator = new AgentOrchestrator(null, Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(ai, null))), store);
        var result = orchestrator.run(generalRequest("帮我处理账单问题"));
        assertEquals(AgentType.GENERAL, result.agentType());
        assertEquals("partial", result.status(), result.errorCode());

        List<BusMessageTrace> messages = store.findBus("r2").orElse(List.of());
        assertEquals(1, messages.size(), messages.toString());
        assertEquals("failed", messages.getFirst().outcome());
    }

    @Test void keywordEscalationHandsOverSpecialistContext() {
        var store = new RequestTraceStore();
        var model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return text("该问题无法处理，建议转人工核实。");
            }
        };
        var ai = new AgentAiService(model, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(
                AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null))), store);
        var result = orchestrator.run(new AgentRequest("登录报错500", "u", "c", "", List.of(),
                Map.of("error_code", List.of("E502"), "amount", List.of("99")),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.LOW, .99, "r3", new ArrayList<>()));
        assertEquals(AgentType.ESCALATION, result.agentType());
        assertTrue(result.escalated());
        // The handoff packet carries the specialist's preliminary conclusion, not a bare flag.
        assertTrue(result.response().contains("专家初步结论"), result.response());
        assertTrue(result.response().contains("无法处理"), result.response());

        List<BusMessageTrace> messages = store.findBus("r3").orElse(List.of());
        assertEquals(1, messages.size(), messages.toString());
        assertEquals("escalate", messages.getFirst().type());
    }
}
