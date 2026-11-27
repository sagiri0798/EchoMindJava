package com.echomind.chat;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.ChatRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.echomind.memory.ConversationStore;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryMaintenance;
import com.echomind.memory.MemoryManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServicePiiTest {
    private ChatService service(AgentOrchestrator orchestrator, AnswerVerifier verifier) {
        var intents = mock(IntentRecognizer.class);
        var memory = mock(MemoryManager.class);
        var maintenance = mock(MemoryMaintenance.class);
        var mapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        var valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        when(memory.getContext(any(), any(), any())).thenReturn(new MemoryContext(List.of(), List.of(), Map.of(), ""));
        when(intents.recognize(any(), any())).thenReturn(new IntentResult(IntentCategory.ACCOUNT, 0.95, UrgencyLevel.LOW,
                "account", Map.of(
                        "phone", List.of("13800000000"),
                        "recipient", List.of("张三"),
                        "address", List.of("北京市朝阳区望京街道1号楼101室")),
                "test", 0, Map.of(), "none"));
        return new ChatService(orchestrator, intents, memory, new ConversationStore(redis, mapper),
                maintenance, verifier, mapper, new EchoMindProperties());
    }

    @Test void chatResponseAndEntitiesAreRedacted() {
        var orchestrator = mock(AgentOrchestrator.class);
        var verifier = mock(AnswerVerifier.class);
        String answer = "收件人张三，电话13800000000，地址：北京市朝阳区望京街道1号楼101室";
        when(orchestrator.run(any())).thenReturn(new OrchestratorResult("r1", answer, AgentType.GENERAL,
                IntentCategory.ACCOUNT, false, 10, List.of(AgentType.GENERAL), AgentType.GENERAL,
                List.of(), List.of(), List.of(), "test", 0.9, "success", false, null));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new AnswerVerifier.VerificationResult(true, true, false, "ok", true));

        var reply = service(orchestrator, verifier)
                .chat(new ChatRequest("改地址", "u1", "conv", "client-pii"), "u1");

        assertFalse(reply.response().contains("13800000000"));
        assertTrue(reply.response().contains("138****0000"));
        assertFalse(reply.response().contains("望京街道"));
        assertTrue(reply.response().contains("北京市朝阳区****"));
        assertTrue(reply.response().contains("收件人张**"));
        assertEquals("138****0000", reply.entities().get("phone").get(0));
        assertEquals("张**", reply.entities().get("recipient").get(0));
        assertEquals("北京市朝阳区****", reply.entities().get("address").get(0));
    }
}
