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

/** ChatService critique-revise loop: one revision round, then the original canned fallback semantics. */
class ChatServiceCritiqueTest {

    private ChatService service(AgentOrchestrator orchestrator, AnswerVerifier verifier) {
        var intents = mock(IntentRecognizer.class);
        var memory = mock(MemoryManager.class);
        var maintenance = mock(MemoryMaintenance.class);
        // Real ConversationStore over a mocked Redis: the final Lease class cannot be mocked itself.
        // The mapper needs JavaTimeModule because commit() serializes Instant-bearing messages.
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
        when(intents.recognize(any(), any())).thenReturn(new IntentResult(IntentCategory.QUERY, 0.95, UrgencyLevel.LOW,
                "general", Map.of(), "test", 0, Map.of(), "none"));
        return new ChatService(orchestrator, intents, memory, new ConversationStore(redis, mapper),
                maintenance, verifier, mapper, new EchoMindProperties());
    }

    private OrchestratorResult result(String id, String answer, String status) {
        return new OrchestratorResult(id, answer, AgentType.GENERAL, IntentCategory.QUERY, false, 10,
                List.of(AgentType.GENERAL), AgentType.GENERAL, List.of(), List.of(), List.of(), "test", 0.9, status, false, null);
    }

    @Test void rejectedAnswerIsRevisedOnceAndAccepted() {
        var orchestrator = mock(AgentOrchestrator.class);
        var verifier = mock(AnswerVerifier.class);
        when(orchestrator.run(any())).thenReturn(result("r1", "原始答案", "success"));
        when(orchestrator.reviseWithCritique(any(), any())).thenReturn(result("r1", "修订后的答案", "success"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new AnswerVerifier.VerificationResult(false, true, false, "缺少操作步骤", true))
                .thenReturn(new AnswerVerifier.VerificationResult(true, true, false, "ok", true));
        var reply = service(orchestrator, verifier)
                .chat(new ChatRequest("查询问题", "u", "conv", "client-1"), "u");
        assertEquals("修订后的答案", reply.response());
        assertEquals("success", reply.status());
        verify(orchestrator).reviseWithCritique(any(), eq("缺少操作步骤"));
        verify(verifier, times(2)).verify(any(), any(), any());
    }

    @Test void doubleRejectionFallsBackToCannedMessageAfterOneRevision() {
        var orchestrator = mock(AgentOrchestrator.class);
        var verifier = mock(AnswerVerifier.class);
        when(orchestrator.run(any())).thenReturn(result("r1", "原始答案", "success"));
        when(orchestrator.reviseWithCritique(any(), any())).thenReturn(result("r1", "仍不理想", "success"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new AnswerVerifier.VerificationResult(false, true, false, "缺少操作步骤", true));
        var reply = service(orchestrator, verifier)
                .chat(new ChatRequest("查询问题", "u", "conv", "client-2"), "u");
        assertEquals("暂时无法可靠确认这个问题的处理结果，请联系人工客服核实。", reply.response());
        assertEquals("partial", reply.status());
        assertEquals("answer_verification_rejected", reply.errorCode());
        verify(orchestrator).reviseWithCritique(any(), any());
    }

    @Test void unavailableVerifierSkipsRevisionAndKeepsRetryableFailure() {
        var orchestrator = mock(AgentOrchestrator.class);
        var verifier = mock(AnswerVerifier.class);
        when(orchestrator.run(any())).thenReturn(result("r1", "原始答案", "success"));
        when(verifier.verify(any(), any(), any()))
                .thenReturn(new AnswerVerifier.VerificationResult(false, false, true, "verification_unavailable", false));
        var reply = service(orchestrator, verifier)
                .chat(new ChatRequest("查询问题", "u", "conv", "client-3"), "u");
        assertEquals("当前服务暂时无法核实回答，请稍后重试或联系人工客服。", reply.response());
        assertEquals("failed", reply.status());
        assertTrue(reply.retryable());
        assertEquals("verification_unavailable", reply.errorCode());
        verify(orchestrator, never()).reviseWithCritique(any(), any());
    }
}
