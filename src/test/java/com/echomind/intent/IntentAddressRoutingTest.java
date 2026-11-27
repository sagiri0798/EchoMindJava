package com.echomind.intent;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntentAddressRoutingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private IntentRecognizer recognizer(String llmIntent, double confidence) {
        LlmGateway llm = (system, user, temperature, maxTokens) ->
                "{\"intent\":\"" + llmIntent + "\",\"confidence\":" + confidence + ",\"reasoning\":\"fixture\"}";
        return new IntentRecognizer(llm, mapper, new IntentEmbedding("local", "", mapper));
    }

    private IntentResult recognize(IntentRecognizer recognizer, String message) {
        return recognizer.recognize(message, List.of());
    }

    private IntentResult recognize(IntentRecognizer recognizer, String message, List<Map<String, String>> history) {
        return recognizer.recognize(message, history);
    }

    @Test void shippingAddressChangeOverridesGenericRequestIntent() {
        var result = recognize(recognizer("request", 0.9), "帮我把收货地址改成北京市朝阳区");
        assertEquals(IntentCategory.ACCOUNT, result.intent());
        assertTrue(result.confidence() >= 0.9, "address change must carry high confidence: " + result.confidence());
        assertTrue(result.sourceScores().containsKey("address_pattern"));
    }

    @Test void allShippingAddressChangePhrasingsAreAccount() {
        for (String message : List.of(
                "我要修改收货地址",
                "换地址到上海",
                "地址变更怎么操作",
                "改地址",
                "收货地址改成北京市朝阳区",
                "帮我改一下收货地址",
                "收货地址需要变更",
                "收货地址怎么改",
                "地址填错了，帮我改一下",
                "地址能改吗",
                "收货地址可以修改吗")) {
            assertEquals(IntentCategory.ACCOUNT, recognize(recognizer("request", 0.9), message).intent(), message);
        }
    }

    @Test void addressChangeWinsEvenWhenLlmDisagreesOrFails() {
        assertEquals(IntentCategory.ACCOUNT, recognize(recognizer("other", 0.3), "帮我把收货地址改到杭州").intent());
        LlmGateway broken = (system, user, temperature, maxTokens) -> "模型暂时不可用";
        var recognizer = new IntentRecognizer(broken, mapper, new IntentEmbedding("local", "", mapper));
        assertEquals(IntentCategory.ACCOUNT, recognize(recognizer, "换地址到上海").intent());
    }

    @Test void confirmationAfterAddressPromptKeepsAddressTask() {
        var history = List.of(
                Map.of("role", "user", "content", "订单号 #B2001，收件人张三，电话13800000000，收货地址改成北京市朝阳区望京街道1号楼101室"),
                Map.of("role", "assistant", "content", "请确认以下新地址，确认后我将提交变更预演。请回复“确认”即可。"));
        var result = recognize(recognizer("request", 0.95), "确认修改", history);
        assertEquals(IntentCategory.ACCOUNT, result.intent());
        assertTrue(result.sourceScores().containsKey("address_confirmation"));
        assertTrue(recognize(recognizer("request", 0.95), "嗯", history).sourceScores().containsKey("address_confirmation"));
    }

    @Test void confirmationWithoutAddressContextDoesNotStartAddressTask() {
        var history = List.of(Map.of("role", "assistant", "content", "退款将在 3 个工作日内到账。"));
        var result = recognize(recognizer("request", 0.7), "确认", history);
        assertFalse(result.sourceScores().containsKey("address_confirmation"));
        assertNotEquals(IntentCategory.ACCOUNT, result.intent());
    }

    @Test void emailAndBillingAddressChangesDoNotTriggerShippingOverride() {
        for (String message : List.of("修改我的邮箱地址", "帮我把邮箱地址改成新的", "账单地址改一下", "开票地址改一下")) {
            var result = recognize(recognizer("request", 0.9), message);
            assertFalse(result.sourceScores().containsKey("address_pattern"), message);
            assertFalse(result.sourceScores().containsKey("address_confirmation"), message);
            assertNotEquals(IntentCategory.ACCOUNT, result.intent(), message);
        }
    }

    @Test void statusQuestionsAndNegationsDoNotTriggerShippingOverride() {
        for (String message : List.of("收货地址改了吗？", "不要修改收货地址", "不用改地址", "地址不用改")) {
            var result = recognize(recognizer("logistics", 0.95), message);
            assertFalse(result.sourceScores().containsKey("address_pattern"), message);
        }
    }

    @Test void refundCaveatThatMentionsShippingAddressIsNotAConfirmationPrompt() {
        var history = List.of(Map.of("role", "assistant", "content", "退款将退回原收货地址，预计 3 个工作日到账。"));
        var result = recognize(recognizer("request", 0.7), "好的", history);
        assertFalse(result.sourceScores().containsKey("address_confirmation"));
    }

    @Test void handoffPromptIsNotAnAddressConfirmation() {
        var history = List.of(Map.of("role", "assistant", "content", "收货地址变更需要人工客服处理，请回复确认转接。"));
        var result = recognize(recognizer("request", 0.7), "确认", history);
        assertFalse(result.sourceScores().containsKey("address_confirmation"));
    }

    @Test void shippingAddressQuestionsWithoutAChangeVerbAreNotHijacked() {
        var result = recognize(recognizer("query", 0.9), "收货地址的运费怎么算");
        assertFalse(result.sourceScores().containsKey("address_pattern"));
        assertNotEquals(IntentCategory.ACCOUNT, result.intent());
    }

    @Test void ordinaryOrderQuestionIsUnaffected() {
        var result = recognize(recognizer("logistics", 0.95), "我的订单什么时候到？");
        assertEquals(IntentCategory.LOGISTICS, result.intent());
        assertFalse(result.sourceScores().containsKey("address_pattern"));
    }
}
