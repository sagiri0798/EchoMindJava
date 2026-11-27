package com.echomind.security;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PiiRedactorTest {
    @Test void masksPhoneEmailAddressAndRecipientInFreeText() {
        String text = PiiRedactor.redactText(
                "收件人张三，电话13800000000，邮箱a.b@example.com，地址：北京市朝阳区望京街道1号楼101室");
        assertFalse(text.contains("13800000000"));
        assertTrue(text.contains("138****0000"));
        assertFalse(text.contains("a.b@example.com"));
        assertTrue(text.contains("[EMAIL]"));
        assertFalse(text.contains("望京街道"));
        assertTrue(text.contains("北京市朝阳区****"));
        assertTrue(text.contains("收件人张**"));
    }

    @Test void redactsStructuredEntityKeys() {
        Map<String, List<String>> redacted = PiiRedactor.redactEntities(Map.of(
                "phone", List.of("13800000000"),
                "recipient", List.of("张三"),
                "address", List.of("上海市浦东新区张江路88号"),
                "street", List.of("望京街道"),
                "detail", List.of("1号楼101室"),
                "note", List.of("联系电话13800000000")));
        assertEquals("138****0000", redacted.get("phone").get(0));
        assertEquals("张**", redacted.get("recipient").get(0));
        assertEquals("上海市浦东新区****", redacted.get("address").get(0));
        assertEquals("****", redacted.get("street").get(0));
        assertEquals("****", redacted.get("detail").get(0));
        assertFalse(redacted.get("note").get(0).contains("13800000000"));
    }

    @Test void masksStreetOnlyAddress() {
        assertEquals("****", PiiRedactor.redactAddress("望京街道1号楼101室"));
        assertEquals("北京市朝阳区****", PiiRedactor.redactAddress("北京市朝阳区望京街道1号楼101室"));
        String spaced = PiiRedactor.redactText("地址：北京市 北京市 朝阳区 望京街道 1号楼101室");
        assertFalse(spaced.contains("望京街道"));
        assertTrue(spaced.contains("北京市朝阳区****"));
        assertFalse(spaced.contains("北京市北京市"), spaced);
        assertEquals("收件人和电话同时变更", PiiRedactor.redactText("收件人和电话同时变更"));
        String streetOnly = PiiRedactor.redactText("如需更新为望京街道 1 号楼 101 室");
        assertFalse(streetOnly.contains("望京街道"));
        assertTrue(streetOnly.contains("****"));
        String fieldStyle = PiiRedactor.redactText("省=北京市、市=北京市、区=朝阳区、街道=望京街道、门牌=1号楼101室");
        assertFalse(fieldStyle.contains("望京街道"));
        assertFalse(fieldStyle.contains("1号楼101室"));
    }
}
