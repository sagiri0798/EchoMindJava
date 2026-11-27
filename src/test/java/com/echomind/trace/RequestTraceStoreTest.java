package com.echomind.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestTraceStoreTest {
    @Test void toolErrorAndBusPayloadAreRedacted() {
        var store = new RequestTraceStore();
        var trace = new RequestToolTrace("r1", "2026-11-28T00:00:00Z", "/chat",
                "user-1", "conv-1", "account", "account", "billing", "billing",
                List.of(), List.of("lookup_order"),
                List.of(new ToolCallTrace("lookup_order", false, false, false, false, 1,
                        "收件人张三 13800000000 北京市朝阳区望京街道1号楼101室")), false, false, 1);
        store.record(trace);
        var error = store.find("r1").orElseThrow().toolCalls().get(0).error();
        assertFalse(error.contains("13800000000"));
        assertTrue(error.contains("138****0000"));
        assertFalse(error.contains("望京街道"));
        assertTrue(error.contains("北京市朝阳区****"));
        assertFalse(error.contains("收件人张三"));

        store.recordBus("r1", List.of(new BusMessageTrace("billing", "escalation", "escalate",
                0, 1, 1, "ok", null,
                "专家结论：收件人张三，电话13800000000，地址北京市朝阳区望京街道1号楼101室")));
        var payload = store.findBus("r1").orElseThrow().get(0).payloadExcerpt();
        assertFalse(payload.contains("13800000000"));
        assertFalse(payload.contains("望京街道"));
        assertTrue(payload.contains("北京市朝阳区****"));
        assertTrue(payload.contains("收件人张**"));
    }
}
