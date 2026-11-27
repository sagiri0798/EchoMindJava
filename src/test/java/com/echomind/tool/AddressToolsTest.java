package com.echomind.tool;

import com.echomind.agent.AgentRequest;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentResult;
import com.echomind.intent.UrgencyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AddressToolsTest {
    private ToolRegistry registry(boolean writesEnabled) {
        var tools = new ToolRegistry(new ObjectMapper(), writesEnabled);
        new RoleToolRegistration(tools).register();
        return tools;
    }

    private AgentRequest request(String user) {
        var intent = new IntentResult(IntentCategory.ACCOUNT, .95, UrgencyLevel.LOW, "account",
                Map.of(), "", 0, Map.of(), "local");
        return AgentRequest.of("改地址", user, "conv-" + user, "背景", List.of(), intent)
                .withOperations(new ToolExecutionGuard(name -> {}));
    }

    private Map<String, Object> addressArgs(String orderId, boolean confirmed) {
        return Map.of("order_id", orderId, "recipient", "张三", "phone", "13800000000",
                "province", "北京市", "city", "北京市", "district", "朝阳区",
                "street", "望京街道", "detail", "1号楼101室", "confirmed", confirmed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResult<Object> result) {
        assertTrue(result.success(), String.valueOf(result.error()));
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentAddress(ToolRegistry registry, String orderId, String user) {
        var data = data(registry.call("lookup_order", Map.of("order_id", orderId), "billing", request(user)));
        return (Map<String, Object>) data.get("current_address");
    }

    @Test void lookupOwnOrderShowsCurrentAddressAndShippingState() {
        var data = data(registry(false).call("lookup_order", Map.of("order_id", "A1001"), "billing", request("eval_user")));
        assertEquals(true, data.get("found"));
        assertEquals(false, data.get("shipped"));
        assertEquals("海淀区", currentAddress(registry(false), "A1001", "eval_user").get("district"));
        assertTrue(String.valueOf(data.get("phone")).contains("*"), "phone must be masked");
        assertEquals(true, data.get("sandbox"));
        assertEquals(false, data.get("real_order_modified"));
        assertEquals(true, data(registry(false).call("lookup_order", Map.of("order_id", "#A1001"), "billing", request("eval_user"))).get("found"));
    }

    @Test void lookupHidesOrdersOwnedBySomeoneElseAndUnknownOrders() {
        for (String orderId : List.of("B2001", "999999")) {
            var data = data(registry(false).call("lookup_order", Map.of("order_id", orderId), "billing", request("eval_user")));
            assertEquals(false, data.get("found"), orderId);
            assertEquals("订单不存在或不属于当前用户", data.get("reason"), orderId);
            assertFalse(data.containsKey("current_address"), orderId);
            assertFalse(data.containsKey("recipient"), orderId);
        }
    }

    @Test void updateRequiresExplicitConfirmationAndDoesNotMutate() {
        var tools = registry(true);
        var before = currentAddress(tools, "A1001", "eval_user");
        var data = data(tools.call("update_shipping_address", addressArgs("A1001", false), "billing", request("eval_user")));
        assertEquals(false, data.get("updated"));
        assertEquals("confirmation_required", data.get("status"));
        assertEquals(before, currentAddress(tools, "A1001", "eval_user"));
    }

    @Test void updateRejectsShippedOrderAndSuggestsLogisticsInterception() {
        var tools = registry(true);
        var before = currentAddress(tools, "A1002", "eval_user");
        var data = data(tools.call("update_shipping_address", addressArgs("A1002", true), "billing", request("eval_user")));
        assertEquals(false, data.get("updated"));
        assertEquals("shipped", data.get("status"));
        assertTrue(String.valueOf(data.get("reason")).contains("已发货"));
        assertEquals(true, data.get("escalation_recommended"));
        assertEquals(before, currentAddress(tools, "A1002", "eval_user"));
    }

    @Test void updateRejectsAnotherUsersOrderAndUnknownOrder() {
        for (String orderId : List.of("B2001", "999999")) {
            var data = data(registry(true).call("update_shipping_address", addressArgs(orderId, true), "billing", request("eval_user")));
            assertEquals(false, data.get("updated"), orderId);
            assertEquals("order_not_found", data.get("status"), orderId);
            assertEquals("订单不存在或不属于当前用户", data.get("reason"), orderId);
        }
    }

    @Test void updateValidatesPhoneAndRequiredFieldsBeforeExecution() {
        var tools = registry(true);
        var badPhone = new java.util.HashMap<>(addressArgs("A1001", true));
        badPhone.put("phone", "12345");
        assertFalse(tools.call("update_shipping_address", badPhone, "billing", request("eval_user")).success());
        var missingRecipient = new java.util.HashMap<>(addressArgs("A1001", true));
        missingRecipient.remove("recipient");
        assertFalse(tools.call("update_shipping_address", missingRecipient, "billing", request("eval_user")).success());
        var blankRecipient = new java.util.HashMap<>(addressArgs("A1001", true));
        blankRecipient.put("recipient", "   ");
        assertFalse(tools.call("update_shipping_address", blankRecipient, "billing", request("eval_user")).success());
    }

    @Test void updateSucceedsInSandboxAndLookupReflectsChange() {
        var tools = registry(true);
        var data = data(tools.call("update_shipping_address", addressArgs("A1001", true), "billing", request("eval_user")));
        assertEquals(true, data.get("updated"));
        assertEquals("sandbox_updated", data.get("status"));
        assertEquals("high", data.get("risk_level"));
        assertEquals(true, data.get("sandbox"));
        assertEquals(false, data.get("real_order_modified"));
        assertTrue(String.valueOf(data.get("summary")).contains("朝阳区"));
        assertFalse(String.valueOf(data.get("summary")).contains("13800000000"), "summary must not leak the full phone");
        assertTrue(String.valueOf(data.get("notice")).contains("真实订单未"));
        var changed = currentAddress(tools, "A1001", "eval_user");
        assertEquals("朝阳区", changed.get("district"));
        assertEquals("望京街道", changed.get("street"));
    }

    @Test void updateIsNotVisibleWithoutWriteOptInOrGuardedRequest() {
        assertTrue(registry(false).available("billing").stream().noneMatch(t -> t.name().equals("update_shipping_address")));
        assertTrue(registry(true).available("billing").stream().anyMatch(t -> t.name().equals("update_shipping_address")));
        assertTrue(registry(true).available("technical").stream().noneMatch(t -> t.name().equals("update_shipping_address")));
        var plain = AgentRequest.of("改地址", "eval_user", "c", "", List.of());
        assertFalse(registry(true).call("update_shipping_address", addressArgs("A1001", true), "billing", plain).success());
    }

    @Test void lookupIsVisibleToBillingOnlyAndNeedsTheAuthenticatedRequest() {
        assertTrue(registry(false).available("billing").stream().anyMatch(t -> t.name().equals("lookup_order")));
        assertTrue(registry(false).available("general").stream().noneMatch(t -> t.name().equals("lookup_order")));
        assertFalse(registry(false).call("lookup_order", Map.of("order_id", "A1001"), "general", request("eval_user")).success());
        assertFalse(registry(false).call("lookup_order", Map.of("order_id", "A1001"), "billing").success());
    }
}
