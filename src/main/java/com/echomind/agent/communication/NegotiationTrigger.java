package com.echomind.agent.communication;

import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;

import java.util.List;

/**
 * Decides whether a single-agent request enters negotiation mode (agent-bus tools enabled).
 * The fast paths stay untouched unless one of these signals says the question is multi-faceted.
 */
public final class NegotiationTrigger {
    private NegotiationTrigger() {}

    public static boolean enabled(AgentRequest req, AgentType primary, List<AgentType> supporting,
                                  List<AgentType> collaborationTargets) {
        if (primary == AgentType.ESCALATION) return false;
        // Cross-domain signals: several candidate specialists mean one may need another's conclusion.
        if (supporting != null && !supporting.isEmpty()) return true;
        if (collaborationTargets != null && collaborationTargets.size() >= 2) return true;
        // GeneralAgent triage must stay live: delegation from general is always possible.
        if (primary == AgentType.GENERAL) return true;
        // Multiple populated entity kinds suggest a multi-faceted question.
        if (req.entities() != null && req.entities().values().stream().filter(v -> v != null && !v.isEmpty()).count() >= 2) {
            return true;
        }
        return false;
    }
}
