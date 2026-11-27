package com.echomind.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified PII redaction for user-visible answers, handoff packets, traces and evidence.
 *
 * <p>Phone numbers are always masked (138****8000). Addresses keep province/city/district and
 * mask street/door details. Recipient names keep the first character. Structured entity maps are
 * redacted by key so tool-internal data can still be used for the confirmation flow.</p>
 */
public final class PiiRedactor {
    private static final Pattern BEARER = Pattern.compile("(?i)(?:bearer\\s+|sk-)[A-Za-z0-9._-]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    /** Keep province/city/district, mask the street/door part of a Chinese address (spaces allowed). */
    private static final Pattern ADDRESS = Pattern.compile(
            "((?:[\\u4e00-\\u9fa5]{2,}\\s*(?:省|自治区))?"
                    + "\\s*[\\u4e00-\\u9fa5]{2,}\\s*(?:市|自治州)?"
                    + "\\s*[\\u4e00-\\u9fa5]{2,}\\s*(?:区|县|旗))"
                    + "([\\u4e00-\\u9fa5A-Za-z0-9\\-\\s]{2,})");
    private static final Pattern RECIPIENT = Pattern.compile(
            "((?:收件人|收货人)\\s*[:：]?\\s*)"
                    + "((?!(?:和|与|及|的|电话|手机|地址|信息|姓名))[\\u4e00-\\u9fa5]{2,4})"
                    + "(?=[\\s，。；、:：]|$)");
    /** Street/door-only fragments without a preceding district ("望京街道 1 号楼 101 室"). */
    private static final Pattern STREET_DETAIL = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,}(?:街道|大街|路|街|巷|道)\\s*\\d+\\s*号[\\u4e00-\\u9fa5A-Za-z0-9]*");
    /** Field-style output such as "街道=望京街道、门牌=1号楼101室". */
    private static final Pattern STREET_FIELD = Pattern.compile(
            "((?:街道|门牌)\\s*[=:：]\\s*)([\\u4e00-\\u9fa5A-Za-z0-9\\-\\s]{2,})");
    private static final Pattern DUPLICATE_REGION = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,}(?:省|市|自治区))\\1");

    private PiiRedactor() {
    }

    public static String redactText(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String text = BEARER.matcher(value).replaceAll("[REDACTED]");
        text = EMAIL.matcher(text).replaceAll("[EMAIL]");
        text = PHONE.matcher(text).replaceAll("$1****$2");
        text = maskAddressMatches(text);
        text = DUPLICATE_REGION.matcher(text).replaceAll("$1");
        text = STREET_DETAIL.matcher(text).replaceAll("****");
        text = STREET_FIELD.matcher(text).replaceAll("$1****");
        Matcher recipient = RECIPIENT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (recipient.find()) {
            recipient.appendReplacement(out, Matcher.quoteReplacement(
                    recipient.group(1) + redactRecipient(recipient.group(2))));
        }
        recipient.appendTail(out);
        return out.toString();
    }

    public static String redactPhone(String value) {
        if (value == null) {
            return "";
        }
        return PHONE.matcher(value).replaceAll("$1****$2");
    }

    public static String redactRecipient(String value) {
        String name = Objects.toString(value, "").strip();
        if (name.isEmpty()) {
            return "";
        }
        int first = name.offsetByCodePoints(0, 1);
        return name.substring(0, first) + "**";
    }

    public static String redactAddress(String value) {
        String address = Objects.toString(value, "").strip();
        if (address.isEmpty() || address.contains("****")) {
            return address;
        }
        if (ADDRESS.matcher(address).find()) {
            return DUPLICATE_REGION.matcher(maskAddressMatches(address)).replaceAll("$1");
        }
        return "****";
    }

    private static String maskAddressMatches(String text) {
        Matcher matcher = ADDRESS.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String prefix = matcher.group(1).replaceAll("\\s+", "");
            for (int len = 2; len <= prefix.length() / 2; len++) {
                if (prefix.startsWith(prefix.substring(0, len), len)) {
                    prefix = prefix.substring(len);
                    break;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(prefix + "****"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static Map<String, List<String>> redactEntities(Map<String, List<String>> entities) {
        if (entities == null || entities.isEmpty()) {
            return entities == null ? Map.of() : entities;
        }
        Map<String, List<String>> redacted = new LinkedHashMap<>();
        entities.forEach((key, values) -> redacted.put(key, values == null ? List.of()
                : values.stream().map(value -> redactEntityValue(key, value)).toList()));
        return redacted;
    }

    private static String redactEntityValue(String key, String value) {
        String text = Objects.toString(value, "");
        String name = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return switch (name) {
            case "phone", "mobile", "tel", "telephone" -> redactPhone(text);
            case "recipient", "receiver", "name" -> redactRecipient(text);
            case "address", "full_address", "current_address", "new_address", "previous_address" ->
                    redactAddress(text);
            case "street", "detail", "address_detail" -> "****";
            case "email" -> "[EMAIL]";
            default -> redactText(text);
        };
    }
}
