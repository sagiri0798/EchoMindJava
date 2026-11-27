package com.echomind.agent.communication;

import com.echomind.agent.AgentType;

import java.util.List;

/**
 * One typed message on the request-scoped agent bus. Immutable protocol unit;
 * the outcome and violation fields are stamped by the {@link ConvergenceController}.
 */
public record BusMessage(
        MessageType type,
        String sender,            // lowercase agent type, or "verifier" / "composer"
        String receiver,
        int round,                // nesting depth of the sender (root = 0)
        long timestampMs,         // offset from context start
        String payload,           // question / reason / critique / handoff (truncated)
        List<AgentType> visited,  // call stack so far (cycle detection)
        String outcome,           // ok | timeout | cycle_detected | budget_exceeded | depth_exceeded | message_limit_exceeded | unavailable | failed
        String violation,         // null unless the controller refused or the ask degraded
        long latencyMs            // blocking time of ASK/DELEGATE runs; -1 for annotations
) {
    public BusMessage(MessageType type, String sender, String receiver, int round, long timestampMs,
                      String payload, List<AgentType> visited) {
        this(type, sender, receiver, round, timestampMs, payload, visited, "ok", null, -1);
    }

    public BusMessage stamped(String outcome, String violation, long latencyMs) {
        return new BusMessage(type, sender, receiver, round, timestampMs, payload, visited, outcome, violation, latencyMs);
    }

    /** Stable truncation so payloads stay trace-safe and bounded. */
    public static String excerpt(String payload, int maxChars) {
        if (payload == null) return "";
        return payload.length() <= maxChars ? payload : payload.substring(0, maxChars) + "…";
    }
}
