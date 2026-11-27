package com.echomind.trace;

import java.util.Locale;

/** One bus message in the per-request communication graph; JSON-serializable for frontend rendering. */
public record BusMessageTrace(String sender, String receiver, String type, int round, long timestampMs,
                              long latencyMs, String outcome, String violation, String payloadExcerpt) {
    public static BusMessageTrace from(com.echomind.agent.communication.BusMessage message) {
        return new BusMessageTrace(
                message.sender(),
                message.receiver(),
                message.type().name().toLowerCase(Locale.ROOT),
                message.round(),
                message.timestampMs(),
                message.latencyMs(),
                message.outcome(),
                message.violation(),
                com.echomind.agent.communication.BusMessage.excerpt(message.payload(), 256)
        );
    }
}
