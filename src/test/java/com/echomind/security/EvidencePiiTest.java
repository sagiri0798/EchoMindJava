package com.echomind.security;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePiiTest {
    private static final Pattern FULL_PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final List<String> STREETS = List.of(
            "望京街道", "中关村大街", "科技园南路", "张江路", "文三路", "中山路");
    private static final List<String> RECIPIENTS = List.of(
            "李雷", "韩梅梅", "王五", "赵六", "钱七", "张三");

    @Test void addressChangeEvidenceContainsNoFullPii() throws Exception {
        Path root = Path.of("docs", "evidence", "address-change");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(file);
                assertFalse(FULL_PHONE.matcher(text).find(), file + " contains a full phone");
                for (String street : STREETS) {
                    assertFalse(text.contains(street), file + " contains street " + street);
                }
                for (String recipient : RECIPIENTS) {
                    assertFalse(text.contains(recipient), file + " contains recipient " + recipient);
                }
            }
        }
    }
}
