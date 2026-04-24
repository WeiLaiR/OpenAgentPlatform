package com.weilair.openagent.chat.service;

public enum ToolRiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static ToolRiskLevel fromValue(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        try {
            return ToolRiskLevel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return MEDIUM;
        }
    }
}
