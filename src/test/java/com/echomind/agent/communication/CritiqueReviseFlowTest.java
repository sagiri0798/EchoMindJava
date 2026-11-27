package com.echomind.agent.communication;

import com.echomind.agent.AgentAiService;
import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.TechnicalAgent;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.tool.ToolRegistry;
import com.echomind.trace.RequestTraceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Critique-revise round: the verifier's CRITIQUE reaches the agent and the revision appends to the graph. */
class CritiqueReviseFlowTest {

    private ChatResponse text(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).finishReason(FinishReason.STOP).build();
    }

    @Test void critiqueIsInjectedAndRevisionAppendsToGraph() {
        var store = new RequestTraceStore();
        var model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                String system = request.messages().stream().filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text()).findFirst().orElse("");
                if (system.contains("[上轮校验意见")) return text("已补充操作步骤的修正答案。");
                return text("原始答案。");
            }
        };
        var ai = new AgentAiService(model, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null))), store);
        var request = new AgentRequest("登录报错500", "u", "c", "", List.of(), Map.of("error_code", List.of("E502")),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.LOW, .99, "r4", new ArrayList<>());
        var revised = orchestrator.reviseWithCritique(request, "缺少操作步骤");
        assertNotNull(revised);
        assertTrue(revised.response().contains("修正答案"), revised.response());
        assertTrue(revised.routingReason().contains("critique-revise"), revised.routingReason());

        var messages = store.findBus("r4").orElse(List.of());
        assertEquals(1, messages.size(), messages.toString());
        assertEquals("critique", messages.getFirst().type());
        assertEquals("verifier", messages.getFirst().sender());
        assertEquals("缺少操作步骤", messages.getFirst().payloadExcerpt());
    }

    @Test void blankCritiqueIsIgnored() {
        var store = new RequestTraceStore();
        var ai = new AgentAiService(new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { return text("答复"); }
        }, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null))), store);
        var request = new AgentRequest("登录报错500", "u", "c", "", List.of(), Map.of("error_code", List.of("E502")),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.LOW, .99, "r5", new ArrayList<>());
        assertNull(orchestrator.reviseWithCritique(request, ""));
        assertNull(orchestrator.reviseWithCritique(request, null));
    }
}
