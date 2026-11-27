package com.echomind.agent.communication;

import com.echomind.agent.AgentAiService;
import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.BillingAgent;
import com.echomind.agent.GeneralAgent;
import com.echomind.agent.TechnicalAgent;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.UrgencyLevel;
import com.echomind.tool.ToolRegistry;
import com.echomind.trace.BusMessageTrace;
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

/** Parallel fast path keeps its per-agent behavior but annotates the shared graph with PROPOSE/ACCEPT. */
class ParallelGraphTest {

    private ChatResponse text(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).finishReason(FinishReason.STOP).build();
    }

    @Test void parallelModeAnnotatesProposeAndAccept() {
        var store = new RequestTraceStore();
        var model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                String system = request.messages().stream().filter(m -> m instanceof SystemMessage)
                        .map(m -> ((SystemMessage) m).text()).findFirst().orElse("");
                if (system.contains("技术支持专家")) return text("技术答复：重启应用。");
                if (system.contains("账单服务专家")) return text("账单答复：7 天内可退。");
                return text("通用答复。");
            }
        };
        var ai = new AgentAiService(model, new ToolRegistry(new ObjectMapper()), new ObjectMapper());
        var orchestrator = new AgentOrchestrator(null, Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(ai, null)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(ai, null)),
                AgentType.BILLING, List.of(new BillingAgent(ai, null))), store);
        var request = new AgentRequest("登录报错，同时申请退款", "u", "c", "", List.of(), Map.of(),
                IntentCategory.TECHNICAL_LOGIN, "technical", UrgencyLevel.LOW, .99, "r6", new ArrayList<>());
        var result = orchestrator.run(request);
        assertEquals(AgentType.TECHNICAL, result.primaryAgent());
        assertEquals(List.of(AgentType.BILLING), result.supportingAgents());

        List<BusMessageTrace> messages = store.findBus("r6").orElse(List.of());
        assertEquals(2, messages.size(), messages.toString());
        assertEquals("propose", messages.get(0).type());
        assertEquals("billing", messages.get(0).sender());
        assertEquals("technical", messages.get(0).receiver());
        assertEquals("accept", messages.get(1).type());
        assertEquals("composer", messages.get(1).sender());
    }
}
