package com.echomind.agent.communication;

import com.echomind.agent.AgentType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConvergenceControllerTest {

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-07T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    private ConvergenceController controller() {
        return new ConvergenceController(clock, 20000, 6, 2, 8000);
    }

    @Test void cycleDetectionRefusesSenderAsReceiver() {
        var controller = controller();
        var admission = controller.admit(MessageType.ASK, AgentType.TECHNICAL, AgentType.BILLING,
                List.of(AgentType.BILLING, AgentType.TECHNICAL));
        assertFalse(admission.allowed());
        assertEquals("cycle_detected", admission.violation());
    }

    @Test void depthCapRefusesNestedNestedAsk() {
        var controller = controller();
        assertFalse(controller.admit(MessageType.ASK, AgentType.TECHNICAL, AgentType.BILLING,
                List.of(AgentType.BILLING, AgentType.GENERAL)).allowed()); // size 2 == maxDepth
    }

    @Test void messageLimitRefusesSeventhMessage() {
        var controller = controller();
        for (int i = 0; i < 6; i++) {
            assertTrue(controller.admit(MessageType.ASK, AgentType.TECHNICAL, AgentType.BILLING, List.of()).allowed());
        }
        var seventh = controller.admit(MessageType.ASK, AgentType.BILLING, AgentType.GENERAL, List.of());
        assertFalse(seventh.allowed());
        assertEquals("message_limit_exceeded", seventh.violation());
    }

    @Test void budgetExceededAtDeadline() {
        var controller = controller();
        assertTrue(controller.admit(MessageType.ASK, AgentType.TECHNICAL, AgentType.BILLING, List.of()).allowed());
        clock.now = clock.now.plusSeconds(21);
        var late = controller.admit(MessageType.ASK, AgentType.BILLING, AgentType.GENERAL, List.of());
        assertFalse(late.allowed());
        assertEquals("budget_exceeded", late.violation());
    }

    @Test void remainingBudgetIsAskTimeoutClippedByGlobalBudget() {
        var controller = controller();
        assertEquals(8000, controller.remainingBudgetMs());
        clock.now = clock.now.plusSeconds(18);
        assertEquals(2000, controller.remainingBudgetMs());
        clock.now = clock.now.plusSeconds(3);
        assertEquals(0, controller.remainingBudgetMs());
    }

    @Test void refusedMessagesDoNotConsumeBudget() {
        var controller = controller();
        assertFalse(controller.admit(MessageType.ASK, AgentType.TECHNICAL, AgentType.BILLING,
                List.of(AgentType.BILLING)).allowed()); // cycle refused, no count
        assertEquals(0, controller.messages());
        assertTrue(controller.admit(MessageType.ASK, AgentType.TECHNICAL, AgentType.BILLING, List.of()).allowed());
        assertEquals(1, controller.messages());
    }

    @Test void selfAskIsRefused() {
        var controller = controller();
        var admission = controller.admit(MessageType.ASK, AgentType.GENERAL, AgentType.GENERAL, List.of());
        assertFalse(admission.allowed());
        assertEquals("self_ask", admission.violation());
        assertEquals(0, controller.messages());
    }
}
