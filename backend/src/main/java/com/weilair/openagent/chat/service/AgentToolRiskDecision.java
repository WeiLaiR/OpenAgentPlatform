package com.weilair.openagent.chat.service;

public record AgentToolRiskDecision(
        Action action,
        ToolRiskLevel riskLevel,
        String reason
) {

    public enum Action {
        ALLOW,
        REQUIRE_CONFIRMATION,
        DENY
    }

    public static AgentToolRiskDecision allow(ToolRiskLevel riskLevel, String reason) {
        return new AgentToolRiskDecision(Action.ALLOW, riskLevel, reason);
    }

    public static AgentToolRiskDecision requireConfirmation(ToolRiskLevel riskLevel, String reason) {
        return new AgentToolRiskDecision(Action.REQUIRE_CONFIRMATION, riskLevel, reason);
    }

    public static AgentToolRiskDecision deny(ToolRiskLevel riskLevel, String reason) {
        return new AgentToolRiskDecision(Action.DENY, riskLevel, reason);
    }

    public boolean isAllowed() {
        return action == Action.ALLOW;
    }
}
