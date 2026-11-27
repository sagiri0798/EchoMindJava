package com.echomind.agent;

import com.echomind.security.PiiRedactor;
import java.util.*;

/** A handoff packet, not a submitted ticket or a claim that a human has accepted the case. */
public record HandoffSummary(String requestId, String reason, String intent, String urgency,
                             Map<String, List<String>> entities, boolean ticketCreated,
                             String specialistType, String specialistOutput) {
    public static HandoffSummary create(AgentRequest request, String reason) {
        return create(request, reason, null, null);
    }
    public static HandoffSummary create(AgentRequest request, String reason, String specialistType, String specialistOutput) {
        Map<String, List<String>> known = new LinkedHashMap<>();
        if (request.entities() != null) request.entities().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) known.put(key, values.stream().limit(8).map(HandoffSummary::safe).toList());
        });
        return new HandoffSummary(request.requestId(), safe(reason), request.intent() == null ? "other" : request.intent().name().toLowerCase(Locale.ROOT),
                request.urgency() == null ? "LOW" : request.urgency().name(), PiiRedactor.redactEntities(known), false,
                specialistType == null || specialistType.isBlank() ? null : safe(specialistType),
                specialistOutput == null ? "" : safe(specialistOutput));
    }
    private static String safe(String value) {
        String text = PiiRedactor.redactText(Objects.toString(value, ""));
        return text.substring(0, text.offsetByCodePoints(0, Math.min(200, text.codePointCount(0, text.length()))));
    }
    public String toCustomerText() {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("order_id", "订单号"), Map.entry("amount", "金额"),
                Map.entry("date", "相关时间"), Map.entry("error_code", "错误码"),
                Map.entry("product", "产品"), Map.entry("recipient", "收件人"),
                Map.entry("phone", "联系电话"), Map.entry("address", "地址"),
                Map.entry("current_address", "地址"), Map.entry("new_address", "新地址"),
                Map.entry("previous_address", "原地址"), Map.entry("street", "街道"),
                Map.entry("detail", "门牌"));
        String information = entities.isEmpty() ? "暂无可交接的订单、金额或错误码等信息" : entities.entrySet().stream()
                .map(e -> labels.getOrDefault(e.getKey(), "补充信息") + "：" + String.join("、", e.getValue()))
                .reduce((a, b) -> a + "；" + b).orElse("");
        String priority = switch (urgency) { case "CRITICAL" -> "紧急"; case "HIGH" -> "优先"; case "MEDIUM" -> "需要跟进"; default -> "普通"; };
        String text = "这个问题需要人工客服继续核验，以下信息可用于交接：\n\n"
                + "升级原因：" + reason + "\n优先级：" + priority
                + "\n已知信息：" + information;
        if (specialistType != null && !specialistOutput.isBlank()) {
            text = text + "\n专家初步结论（" + specialistType + "）：" + safe(specialistOutput);
        }
        return PiiRedactor.redactText(text + "\n\n"
                + "系统尚未接入人工工单队列，以上仅为交接摘要，未创建或提交工单。请通过现有客服渠道联系人工。"
                + "请不要发送密码、短信验证码或完整密钥。");
    }
}
