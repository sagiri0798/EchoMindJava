package com.echomind.agent.communication;

import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentResponse;
import com.echomind.agent.AgentType;
import com.echomind.agent.BaseAgent;
import com.echomind.config.EchoMindProperties;
import com.echomind.tool.ToolExecutionGuard;
import com.echomind.util.Tasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Request-scoped carrier of the agent bus: message log, convergence budgets and the
 * hooks an orchestrator binds to execute nested runs. One instance per top-level request;
 * created in {@code AgentOrchestrator.run} and passed explicitly (never ThreadLocal, so
 * parallel virtual threads share it safely). All state is synchronized — a request carries
 * at most {@code maxMessages} messages, so contention is negligible.
 */
public final class CommunicationContext {

    /** Selects a pool instance for a nested run; null when the target is unhealthy. */
    public interface AgentSelector {
        BaseAgent select(AgentType target);
    }

    /** Executes one nested agent run inside the current request (ASK/DELEGATE). Depth = visited.size(). */
    public interface NestedExecutor {
        AgentResponse run(AgentRequest subRequest, AgentType target, List<AgentType> visited);
    }

    /** Result of a blocking ASK/DELEGATE: either the target's conclusion or a degraded marker. */
    public record AskOutcome(boolean supported, String content, String violation) {
        public static AskOutcome supported(String content) { return new AskOutcome(true, content, null); }
        public static AskOutcome unsupported(String violation) { return new AskOutcome(false, "", violation); }
    }

    private static final CommunicationContext NONE = new CommunicationContext(false, null, null);

    private final boolean enabled;
    private final ConvergenceController controller;
    private final List<BusMessage> messages = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final long startNanos = System.nanoTime();
    private volatile AgentSelector selector = target -> null;
    private volatile NestedExecutor executor = (sub, target, visited) -> null;
    private volatile int round;
    private volatile List<AgentType> stack = List.of();
    private volatile String critique;
    private volatile String escalationSpecialistType;
    private volatile String escalationSpecialistOutput;
    private AgentResponse delegationResult;

    private CommunicationContext(boolean enabled, ConvergenceController controller, String marker) {
        this.enabled = enabled;
        this.controller = controller;
    }

    /** No-op context: communication tools are not injected, ASK/DELEGATE degrade immediately. */
    public static CommunicationContext none() {
        return NONE;
    }

    public static CommunicationContext create(EchoMindProperties.Bus config) {
        if (config == null || !config.isEnabled()) return none();
        return new CommunicationContext(true,
                new ConvergenceController(config.getBudgetMs(), config.getMaxMessages(), config.getMaxDepth(), config.getAskTimeoutMs()), null);
    }

    public boolean enabled() { return enabled; }

    /** Nesting depth of the current execution: 0 at root, 1 for a first-level ASK/DELEGATE. */
    public int round() { return round; }

    /** Runs {@code action} with the given call stack; nested executions inherit budget and cycle context. */
    public <T> T withRound(int depth, List<AgentType> visited, Supplier<T> action) {
        int previous = round;
        List<AgentType> previousStack = stack;
        round = depth;
        stack = visited == null ? List.of() : List.copyOf(visited);
        try {
            return action.get();
        } finally {
            round = previous;
            stack = previousStack;
        }
    }

    /** Binds the orchestrator's pool access and nested execution path for this request. */
    public void bind(AgentSelector selector, NestedExecutor executor) {
        if (!enabled) return;
        this.selector = selector == null ? target -> null : selector;
        this.executor = executor == null ? (sub, target, visited) -> null : executor;
    }

    /**
     * Blocking sub-question: admits the ASK through the controller, selects a healthy target,
     * runs it inside the remaining budget and returns its conclusion. On refusal/timeout/failure
     * the asker degrades (continues answering without the target's support) — never fails the request.
     * The blocking run happens OUTSIDE this monitor so nested asks on the same context don't deadlock.
     */
    public AskOutcome ask(AgentRequest parent, AgentType sender, AgentType target, String question) {
        if (!enabled) return AskOutcome.unsupported("disabled");
        BusMessage message;
        AgentRequest sub;
        List<AgentType> childStack;
        long timeout;
        synchronized (this) {
            List<AgentType> current = stack;
            ConvergenceController.Admission admission = controller.admit(MessageType.ASK, sender, target, current);
            message = new BusMessage(MessageType.ASK, name(sender), name(target), current.size(), nowMs(),
                    BusMessage.excerpt(question, 400), current);
            if (!admission.allowed()) {
                messages.add(message.stamped(admission.violation(), admission.violation(), 0));
                return AskOutcome.unsupported(admission.violation());
            }
            if (selector.select(target) == null) {
                messages.add(message.stamped("unavailable", "unavailable", 0));
                return AskOutcome.unsupported("target_unavailable");
            }
            childStack = append(current, sender);
            sub = subRequest(parent, question);
            timeout = Math.max(1, controller.remainingBudgetMs());
        }
        long started = System.nanoTime();
        try {
            AgentResponse response = Tasks.within(Duration.ofMillis(timeout), () -> executor.run(sub, target, childStack));
            long latency = (System.nanoTime() - started) / 1_000_000;
            if (response != null && response.success()) {
                synchronized (this) { messages.add(message.stamped("ok", null, latency)); }
                return AskOutcome.supported(response.content());
            }
            synchronized (this) { messages.add(message.stamped("failed", "agent_failed", latency)); }
            return AskOutcome.unsupported("agent_failed");
        } catch (RuntimeException ex) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            String violation = "timeout".equals(ex.getMessage()) ? "timeout" : ex.getClass().getSimpleName();
            synchronized (this) { messages.add(message.stamped("timeout", violation, latency)); }
            return AskOutcome.unsupported(violation);
        }
    }

    /**
     * Terminal handoff: the delegatee runs on the ORIGINAL request (original write guard — it
     * becomes the primary responder) and its response is consumed by the orchestrator afterwards.
     */
    public AskOutcome delegate(AgentRequest parent, AgentType sender, AgentType target, String reason) {
        if (!enabled) return AskOutcome.unsupported("disabled");
        BusMessage message;
        List<AgentType> childStack;
        long timeout;
        synchronized (this) {
            List<AgentType> current = stack;
            ConvergenceController.Admission admission = controller.admit(MessageType.DELEGATE, sender, target, current);
            message = new BusMessage(MessageType.DELEGATE, name(sender), name(target), current.size(), nowMs(),
                    BusMessage.excerpt(reason, 200), current);
            if (!admission.allowed()) {
                messages.add(message.stamped(admission.violation(), admission.violation(), 0));
                return AskOutcome.unsupported(admission.violation());
            }
            childStack = append(current, sender);
            timeout = Math.max(1, controller.remainingBudgetMs());
        }
        long started = System.nanoTime();
        try {
            AgentResponse response = Tasks.within(Duration.ofMillis(timeout), () -> executor.run(parent, target, childStack));
            long latency = (System.nanoTime() - started) / 1_000_000;
            if (response == null || !response.success()) {
                synchronized (this) { messages.add(message.stamped("failed", "agent_failed", latency)); }
                return AskOutcome.unsupported("agent_failed");
            }
            synchronized (this) {
                messages.add(message.stamped("ok", null, latency));
                delegationResult = response;
            }
            return AskOutcome.supported("");
        } catch (RuntimeException ex) {
            long latency = (System.nanoTime() - started) / 1_000_000;
            String violation = "timeout".equals(ex.getMessage()) ? "timeout" : ex.getClass().getSimpleName();
            synchronized (this) { messages.add(message.stamped("timeout", violation, latency)); }
            return AskOutcome.unsupported(violation);
        }
    }

    /** The delegatee's response after a successful DELEGATE; consumed exactly once by the orchestrator. */
    public synchronized AgentResponse consumeDelegationResult() {
        AgentResponse result = delegationResult;
        delegationResult = null;
        return result;
    }

    /** Records an annotation (PROPOSE/ACCEPT/CRITIQUE/ESCALATE) on the message graph. */
    public synchronized void post(MessageType type, String sender, String receiver, String payload, List<AgentType> visited) {
        if (!enabled) return;
        List<AgentType> current = visited == null ? List.of() : List.copyOf(visited);
        messages.add(new BusMessage(type, sender, receiver, current.size(), nowMs(), BusMessage.excerpt(payload, 400), current));
    }

    /** Verifier feedback for the revision round; injected into the agent's system prompt. */
    public void setCritique(String reason) { critique = reason; }
    public String critique() { return critique; }

    /** Specialist context handed to the escalation agent (replaces the bare boolean flag). */
    public void setEscalationPacket(String specialistType, String specialistOutput) {
        escalationSpecialistType = specialistType;
        escalationSpecialistOutput = specialistOutput;
    }
    public String escalationSpecialistType() { return escalationSpecialistType; }
    public String escalationSpecialistOutput() { return escalationSpecialistOutput; }

    /** Chronological message log: nested messages can complete before their parent is stamped, so sort by time. */
    public synchronized List<BusMessage> snapshot() {
        return messages.stream()
                .sorted(java.util.Comparator.comparingLong(BusMessage::timestampMs).thenComparingInt(BusMessage::round))
                .toList();
    }

    private AgentRequest subRequest(AgentRequest parent, String question) {
        String subId = parent.requestId() + "#m" + seq.incrementAndGet();
        return new AgentRequest(question, parent.userId(), parent.conversationId(), parent.context(), parent.history(),
                parent.entities(), parent.intent(), parent.intentGroup(), parent.urgency(), parent.intentConfidence(),
                subId, parent.evidence(), ToolExecutionGuard.readOnly());
    }

    private List<AgentType> append(List<AgentType> base, AgentType sender) {
        List<AgentType> result = new ArrayList<>(base);
        result.add(sender);
        return result;
    }

    private long nowMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String name(AgentType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
