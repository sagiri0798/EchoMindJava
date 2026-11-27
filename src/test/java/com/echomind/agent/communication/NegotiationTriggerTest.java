package com.echomind.agent.communication;

import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NegotiationTriggerTest {

    private AgentRequest request(Map<String, List<String>> entities) {
        return new AgentRequest("登录报错", "u", "c", "", List.of(), entities,
                com.echomind.intent.IntentCategory.TECHNICAL_LOGIN, "technical",
                com.echomind.intent.UrgencyLevel.LOW, .9, "r", new java.util.ArrayList<>());
    }

    @Test void escalationPrimaryNeverNegotiates() {
        assertFalse(NegotiationTrigger.enabled(request(Map.of()), AgentType.ESCALATION, List.of(), List.of()));
    }

    @Test void generalPrimaryAlwaysNegotiates() {
        assertTrue(NegotiationTrigger.enabled(request(Map.of()), AgentType.GENERAL, List.of(), List.of()));
    }

    @Test void multiplePopulatedEntitiesTrigger() {
        assertTrue(NegotiationTrigger.enabled(
                request(Map.of("error_code", List.of("E502"), "amount", List.of("99"))),
                AgentType.TECHNICAL, List.of(), List.of()));
    }

    @Test void crossDomainCollaborationTargetsTrigger() {
        assertTrue(NegotiationTrigger.enabled(request(Map.of()), AgentType.TECHNICAL, List.of(),
                List.of(AgentType.TECHNICAL, AgentType.BILLING)));
    }

    @Test void simpleSingleDomainRequestStaysOnFastPath() {
        assertFalse(NegotiationTrigger.enabled(request(Map.of("error_code", List.of("E502"))),
                AgentType.TECHNICAL, List.of(), List.of()));
    }
}
