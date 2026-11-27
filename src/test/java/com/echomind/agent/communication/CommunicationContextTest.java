package com.echomind.agent.communication;

import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentResponse;
import com.echomind.agent.AgentType;
import com.echomind.agent.BaseAgent;
import com.echomind.config.EchoMindProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CommunicationContextTest {

    private AgentRequest request(String message) {
        return AgentRequest.of(message, "u", "c", "", List.of());
    }

    private EchoMindProperties.Bus config(long budgetMs, long askTimeoutMs) {
        EchoMindProperties.Bus bus = new EchoMindProperties.Bus();
        bus.setBudgetMs(budgetMs);
        bus.setAskTimeoutMs(askTimeoutMs);
        return bus;
    }

    @Test void askTimeoutReturnsUnsupportedMarkerAndStampsMessage() {
        var ctx = CommunicationContext.create(config(1000, 200));
        ctx.bind(type -> mock(BaseAgent.class),
                (sub, target, visited) -> {
                    try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                    return new AgentResponse(target, "late", true, 1, 0, false, "", true, false, false, "");
                });
        var outcome = ctx.ask(request("跨域问题"), AgentType.TECHNICAL, AgentType.BILLING, "退款政策？");
        assertFalse(outcome.supported());
        assertEquals("timeout", outcome.violation());
        var messages = ctx.snapshot();
        assertEquals(1, messages.size());
        assertEquals("timeout", messages.getFirst().outcome());
    }

    @Test void cycleRefusalUsesCurrentCallStack() {
        var ctx = CommunicationContext.create(config(20000, 8000));
        ctx.bind(type -> mock(BaseAgent.class), (sub, target, visited) -> null);
        var outcome = ctx.withRound(1, List.of(AgentType.GENERAL),
                () -> ctx.ask(request("子问题"), AgentType.TECHNICAL, AgentType.GENERAL, "再问一次"));
        assertFalse(outcome.supported());
        assertEquals("cycle_detected", outcome.violation());
        assertEquals("cycle_detected", ctx.snapshot().getFirst().outcome());
    }

    @Test void delegateStoresResultAndIsConsumedOnce() {
        var ctx = CommunicationContext.create(config(20000, 8000));
        var reply = new AgentResponse(AgentType.TECHNICAL, "专家答复", true, 1, 1, false, "", true, false, false, "");
        ctx.bind(type -> mock(BaseAgent.class), (sub, target, visited) -> reply);
        var outcome = ctx.delegate(request("原始问题"), AgentType.GENERAL, AgentType.TECHNICAL, "技术问题转交");
        assertTrue(outcome.supported());
        assertSame(reply, ctx.consumeDelegationResult());
        assertNull(ctx.consumeDelegationResult());
        assertEquals("delegate", ctx.snapshot().getFirst().type().name().toLowerCase());
    }

    @Test void disabledContextDegradesImmediately() {
        var outcome = CommunicationContext.none().ask(request("问题"), AgentType.GENERAL, AgentType.TECHNICAL, "子问题");
        assertFalse(outcome.supported());
        assertEquals("disabled", outcome.violation());
        assertTrue(CommunicationContext.none().snapshot().isEmpty());
    }
}
