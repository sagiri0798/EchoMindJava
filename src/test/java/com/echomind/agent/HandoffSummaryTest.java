package com.echomind.agent;

import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandoffSummaryTest {
    private AgentRequest request() {
        var intent = new IntentResult(IntentCategory.ACCOUNT, 0.95, UrgencyLevel.HIGH, "account",
                Map.of("phone", List.of("13800000000"),
                        "recipient", List.of("张三"),
                        "address", List.of("北京市朝阳区望京街道1号楼101室"),
                        "note", List.of("备用电话 13900000000")),
                "", 0, Map.of(), "local");
        return AgentRequest.of("改地址", "u1", "c1", "背景", List.of(), intent);
    }

    @Test void handoffPacketAndCustomerTextDoNotCarryFullPii() {
        var summary = HandoffSummary.create(request(), "用户要求人工核验",
                "billing", "专家结论：收件人张三，电话13800000000，地址北京市朝阳区望京街道1号楼101室");
        String serialized = summary.toString();
        String customer = summary.toCustomerText();
        for (String text : List.of(serialized, customer)) {
            assertFalse(text.contains("13800000000"));
            assertFalse(text.contains("13900000000"));
            assertFalse(text.contains("望京街道"));
            assertFalse(text.contains("北京市朝阳区望京街道"));
        }
        assertEquals("138****0000", summary.entities().get("phone").get(0));
        assertEquals("张**", summary.entities().get("recipient").get(0));
        assertEquals("北京市朝阳区****", summary.entities().get("address").get(0));
        assertFalse(summary.entities().get("note").get(0).contains("13900000000"));
        assertTrue(customer.contains("138****0000"));
    }

    @Test void shippedAndUnknownOrderHandoffsStayRedacted() {
        for (String reason : List.of("订单已发货，建议物流拦截或转人工", "订单不存在，无法核验归属")) {
            var summary = HandoffSummary.create(request(), reason, "billing",
                    "专家结论：收件人张三，电话13800000000，地址北京市朝阳区望京街道1号楼101室");
            String customer = summary.toCustomerText();
            assertFalse(customer.contains("13800000000"), reason);
            assertFalse(customer.contains("望京街道"), reason);
            assertTrue(customer.contains("138****0000"), reason);
        }
    }
}
