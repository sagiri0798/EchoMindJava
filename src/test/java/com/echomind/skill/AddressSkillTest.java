package com.echomind.skill;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressSkillTest {
    @Test void addressSkillLoadsOnlyForBillingAddressMessages() {
        var properties = new EchoMindProperties();
        properties.getSkills().setRootDir("skills");
        var manager = new SkillManager(properties, new ObjectMapper());
        manager.init();

        assertTrue(manager.promptFor("帮我把收货地址改成北京市朝阳区", "billing").contains("订单收货地址变更规范"));
        assertTrue(manager.promptFor("我要修改收货地址", "billing").contains("订单收货地址变更规范"));
        assertFalse(manager.promptFor("申请退款", "billing").contains("订单收货地址变更规范"));
        assertFalse(manager.promptFor("帮我把邮箱地址改成新的", "billing").contains("订单收货地址变更规范"));
        assertFalse(manager.promptFor("账单地址改一下", "billing").contains("订单收货地址变更规范"));
        assertFalse(manager.promptFor("帮我把收货地址改成北京市朝阳区", "general").contains("订单收货地址变更规范"));
        assertTrue(manager.summary().toString().contains("order_address_change"));
    }
}
