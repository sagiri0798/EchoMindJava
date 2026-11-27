package com.echomind.agent.communication;

import com.echomind.agent.AgentType;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Convergence control for one request's agent dialogue: message count, tree depth,
 * wall-clock budget and cycle detection. Every prospective bus message passes through
 * {@link #admit}. All state is synchronized; contention is negligible because a request
 * carries at most {@code maxMessages} messages.
 */
public final class ConvergenceController {

    private final Clock clock;
    private final long budgetMs;
    private final int maxMessages;
    private final int maxDepth;
    private final long askTimeoutMs;
    private final Instant deadline;
    private int messages;

    public ConvergenceController(Clock clock, long budgetMs, int maxMessages, int maxDepth, long askTimeoutMs) {
        this.clock = clock;
        this.budgetMs = budgetMs;
        this.maxMessages = maxMessages;
        this.maxDepth = maxDepth;
        this.askTimeoutMs = askTimeoutMs;
        this.deadline = clock.instant().plusMillis(budgetMs);
    }

    public ConvergenceController(long budgetMs, int maxMessages, int maxDepth, long askTimeoutMs) {
        this(Clock.systemUTC(), budgetMs, maxMessages, maxDepth, askTimeoutMs);
    }

    /** Decision for one prospective message: allowed, or refused with a stamped violation. */
    public record Admission(boolean allowed, String violation) {
        public static Admission ok() { return new Admission(true, null); }
        public static Admission refused(String violation) { return new Admission(false, violation); }
    }

    public synchronized Admission admit(MessageType type, AgentType sender, AgentType receiver, List<AgentType> visited) {
        if (sender == receiver) return Admission.refused("self_ask");
        if (visited != null && visited.contains(receiver)) return Admission.refused("cycle_detected");
        if (visited != null && visited.size() >= maxDepth) return Admission.refused("depth_exceeded");
        if (!clock.instant().isBefore(deadline)) return Admission.refused("budget_exceeded");
        if (messages >= maxMessages) return Admission.refused("message_limit_exceeded");
        messages++;
        return Admission.ok();
    }

    /** Timeout for a blocking ASK: the per-ASK cap, clipped by the remaining global budget. */
    public synchronized long remainingBudgetMs() {
        long remaining = deadline.toEpochMilli() - clock.instant().toEpochMilli();
        return Math.max(0, Math.min(askTimeoutMs, remaining));
    }

    public synchronized int messages() { return messages; }
    public long budgetMs() { return budgetMs; }
    public int maxMessages() { return maxMessages; }
    public int maxDepth() { return maxDepth; }
    public long askTimeoutMs() { return askTimeoutMs; }
}
