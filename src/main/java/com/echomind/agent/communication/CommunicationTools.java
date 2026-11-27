package com.echomind.agent.communication;

import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.llm.ToolSpecifications;
import com.echomind.tool.ToolDefinition;
import com.echomind.trace.ToolCallTrace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The agent bus exposed to LLMs as two tools: {@code ask_agent} (blocking sub-question,
 * reply merged back as tool result) and {@code delegate_agent} (terminal handoff). Injected
 * only when negotiation mode is active, so the fast paths keep their original tool surface.
 */
public final class CommunicationTools {
    private CommunicationTools() {}

    public static final String ASK_TOOL = "ask_agent";
    public static final String DELEGATE_TOOL = "delegate_agent";
    private static final String SOURCE = "agent_bus";

    public static Map<ToolSpecification, ToolExecutor> tools(AgentType sender, CommunicationContext ctx,
                                                             List<ToolCallTrace> traces, AgentRequest request,
                                                             ObjectMapper mapper) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(ToolSpecifications.from(new ToolDefinition(ASK_TOOL,
                        "向指定角色 Agent 提出一个具体子问题，阻塞等待其结论后融合进最终回复（如账单政策、技术排查细节）。对方超时或不可用时会返回未获支持标记。",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "target_agent", Map.of("type", "string", "enum", List.of("technical", "billing", "general"), "description", "目标角色"),
                                        "question", Map.of("type", "string", "description", "需要对方回答的具体子问题")),
                                "required", List.of("target_agent", "question"),
                                "additionalProperties", false),
                        Set.of("technical", "billing", "general"), true, Duration.ofSeconds(8), Duration.ZERO,
                        "local", "", "", (ToolDefinition.Handler) null), mapper),
                askExecutor(sender, ctx, traces, request, mapper));
        tools.put(ToolSpecifications.from(new ToolDefinition(DELEGATE_TOOL,
                        "确认本角色无法完成当前请求时，把整个请求移交指定角色 Agent 处理（可移交 escalation）。移交后本 Agent 不再作答。",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "target_agent", Map.of("type", "string", "enum", List.of("technical", "billing", "general", "escalation"), "description", "移交目标"),
                                        "reason", Map.of("type", "string", "description", "移交原因")),
                                "required", List.of("target_agent", "reason"),
                                "additionalProperties", false),
                        Set.of("technical", "billing", "general"), true, Duration.ofSeconds(8), Duration.ZERO,
                        "local", "", "", (ToolDefinition.Handler) null), mapper),
                delegateExecutor(sender, ctx, traces, request, mapper));
        return tools;
    }

    private static ToolExecutor askExecutor(AgentType sender, CommunicationContext ctx, List<ToolCallTrace> traces,
                                            AgentRequest request, ObjectMapper mapper) {
        return (call, memoryId) -> {
            long started = System.nanoTime();
            try {
                Map<String, Object> args = mapper.readValue(call.arguments(), new TypeReference<>() {});
                if (args == null) throw new IllegalArgumentException("null_arguments");
                AgentType target = resolveTarget(args.get("target_agent"), false);
                String question = String.valueOf(args.getOrDefault("question", ""));
                if (target == null || target == sender || question.isBlank() || question.length() > 400) {
                    traces.add(trace(ASK_TOOL, false, 0, "invalid_arguments"));
                    return "[ask_agent] 参数无效：target_agent 需为 technical/billing/general（不能是自己），question 不能为空且不超过 400 字。";
                }
                CommunicationContext.AskOutcome outcome = ctx.ask(request, sender, target, question);
                long latency = elapsed(started);
                traces.add(trace(ASK_TOOL, outcome.supported(), latency, outcome.supported() ? "" : outcome.violation()));
                return outcome.supported()
                        ? "对方结论（" + name(target) + "）：\n" + BusMessage.excerpt(outcome.content(), 2000)
                        : "未能获得对方结论（" + outcome.violation() + "）。请基于已有信息回答，并明确告知用户该部分暂缺，不要编造。";
            } catch (Exception ex) {
                traces.add(trace(ASK_TOOL, false, elapsed(started), ex.getClass().getSimpleName()));
                return "[ask_agent] 协作失败：" + ex.getClass().getSimpleName() + "。请基于已有信息回答，并明确告知用户该部分暂缺。";
            }
        };
    }

    private static ToolExecutor delegateExecutor(AgentType sender, CommunicationContext ctx, List<ToolCallTrace> traces,
                                                 AgentRequest request, ObjectMapper mapper) {
        return (call, memoryId) -> {
            long started = System.nanoTime();
            try {
                Map<String, Object> args = mapper.readValue(call.arguments(), new TypeReference<>() {});
                if (args == null) throw new IllegalArgumentException("null_arguments");
                AgentType target = resolveTarget(args.get("target_agent"), true);
                String reason = String.valueOf(args.getOrDefault("reason", ""));
                if (target == null || target == sender || reason.isBlank()) {
                    traces.add(trace(DELEGATE_TOOL, false, 0, "invalid_arguments"));
                    return "[delegate_agent] 参数无效：target_agent 需为 technical/billing/general/escalation（不能是自己），reason 不能为空。";
                }
                CommunicationContext.AskOutcome outcome = ctx.delegate(request, sender, target, reason);
                long latency = elapsed(started);
                traces.add(trace(DELEGATE_TOOL, outcome.supported(), latency, outcome.supported() ? "" : outcome.violation()));
                return outcome.supported()
                        ? "已移交 " + name(target) + " 处理。请以一句话告知用户正在转交，然后结束回答。"
                        : "移交失败（" + outcome.violation() + "）。请自行回答用户问题。";
            } catch (Exception ex) {
                traces.add(trace(DELEGATE_TOOL, false, elapsed(started), ex.getClass().getSimpleName()));
                return "[delegate_agent] 协作失败：" + ex.getClass().getSimpleName() + "。请自行回答用户问题。";
            }
        };
    }

    private static AgentType resolveTarget(Object raw, boolean includeEscalation) {
        if (!(raw instanceof String value)) return null;
        return switch (value) {
            case "technical" -> AgentType.TECHNICAL;
            case "billing" -> AgentType.BILLING;
            case "general" -> AgentType.GENERAL;
            case "escalation" -> includeEscalation ? AgentType.ESCALATION : null;
            default -> null;
        };
    }

    private static ToolCallTrace trace(String tool, boolean success, long latency, String error) {
        return new ToolCallTrace(tool, success, false, false, false, latency, error, SOURCE, "", "");
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String name(AgentType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
