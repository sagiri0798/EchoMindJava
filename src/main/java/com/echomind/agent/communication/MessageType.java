package com.echomind.agent.communication;

/** Typed protocol unit of the agent bus. Every cross-agent interaction is one of these. */
public enum MessageType {
    /** Blocking sub-question from one agent to another; the asker merges the reply and continues. */
    ASK,
    /** Terminal handoff: the sender passes the whole request to another agent. */
    DELEGATE,
    /** A supporting agent contributes its conclusion (parallel mode graph annotation). */
    PROPOSE,
    /** The verifier's negative verdict: what is wrong and what to fix. */
    CRITIQUE,
    /** The composer accepts the merged contributions (parallel mode graph annotation). */
    ACCEPT,
    /** Context handoff to the escalation agent, replacing the bare boolean flag. */
    ESCALATE
}
