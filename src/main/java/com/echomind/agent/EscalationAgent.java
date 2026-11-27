package com.echomind.agent;

import com.echomind.agent.communication.CommunicationContext;
import com.echomind.skill.SkillManager;
import com.echomind.trace.ToolCallTrace;
import java.util.List;

/** Deterministic handoff: no model call and no simulated ticket submission. */
public class EscalationAgent extends BaseAgent {
    public EscalationAgent(AgentAiService aiService, SkillManager skills) { super(aiService, skills); }
    @Override public AgentType type() { return AgentType.ESCALATION; }
    @Override protected String systemPrompt() { return "负责人工升级交接，不模拟后台操作。"; }
    @Override public AgentResponse handle(AgentRequest request) {
        return handle(request, CommunicationContext.none());
    }
    public AgentResponse handle(AgentRequest request, CommunicationContext ctx) {
        long start = System.nanoTime();
        if (ctx.enabled() && ctx.escalationSpecialistType() != null) {
            // Specialist context handed over: the preliminary conclusion travels with the packet.
            var summary = HandoffSummary.create(request,
                    request.urgency() == com.echomind.intent.UrgencyLevel.CRITICAL
                            ? "紧急或高风险问题，需要人工优先核验" : "用户请求人工协助或超出当前自动处理范围",
                    ctx.escalationSpecialistType(), ctx.escalationSpecialistOutput());
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (ctx.round() == 0) stats().record(request.requestId(), true, elapsed);
            return new AgentResponse(type(), summary.toCustomerText(), true, 1, elapsed, true, "", true, false, false, "",
                    List.of(new ToolCallTrace("create_handoff_summary", true, false, false, false, elapsed, "")));
        }
        var summary = HandoffSummary.create(request, request.urgency() == com.echomind.intent.UrgencyLevel.CRITICAL
                ? "紧急或高风险问题，需要人工优先核验" : "用户请求人工协助或超出当前自动处理范围");
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (ctx.round() == 0) stats().record(request.requestId(), true, elapsed);
        return new AgentResponse(type(), summary.toCustomerText(), true, 1, elapsed, true, "", true, false, false, "",
                List.of(new ToolCallTrace("create_handoff_summary", true, false, false, false, elapsed, "")));
    }
}
