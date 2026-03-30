package com.weilair.openagent.chat.prompt;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.message.SystemMessage;

/**
 * 这个对象表达“当前这一轮最终由哪些 Prompt 块组成”。
 * 第一版先承担三件事：
 * 1. 为 `ChatContextAssembler` 提供稳定的 `SystemMessage` 输出；
 * 2. 让主链路能拿到 Prompt 组成摘要，而不是只看到一坨最终字符串；
 * 3. 为后续 trace / 调试视图保留结构化落点。
 */
public record PromptAssembly(
        List<PromptBlock> blocks
) {

    public PromptAssembly {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public List<SystemMessage> toSystemMessages() {
        return blocks.stream()
                .map(block -> SystemMessage.from(block.content()))
                .toList();
    }

    public PromptAssemblySummary summary() {
        return new PromptAssemblySummary(
                blocks.stream().map(PromptBlock::key).map(PromptTemplateKey::code).toList(),
                blocks.size(),
                summarizeVariablesByBlock()
        );
    }

    private Map<String, Map<String, Object>> summarizeVariablesByBlock() {
        Map<String, Map<String, Object>> summarized = new LinkedHashMap<>();
        for (PromptBlock block : blocks) {
            if (block.variables().isEmpty()) {
                continue;
            }
            Map<String, Object> variableSummary = new LinkedHashMap<>();
            block.variables().forEach((name, value) -> variableSummary.put(name, summarizeVariableValue(value)));
            summarized.put(block.key().code(), Map.copyOf(variableSummary));
        }
        return Map.copyOf(summarized);
    }

    private Object summarizeVariableValue(Object value) {
        if (value == null) {
            return Map.of("kind", "null");
        }
        if (value instanceof CharSequence text) {
            return Map.of(
                    "kind", "text",
                    "length", text.length(),
                    "lineCount", countLines(text)
            );
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Collection<?> collection) {
            return Map.of(
                    "kind", "collection",
                    "size", collection.size()
            );
        }
        if (value instanceof Map<?, ?> map) {
            return Map.of(
                    "kind", "map",
                    "size", map.size(),
                    "keys", map.keySet().stream().limit(8).map(String::valueOf).toList()
            );
        }
        return Map.of(
                "kind", "value",
                "type", value.getClass().getSimpleName()
        );
    }

    private int countLines(CharSequence text) {
        if (text.length() == 0) {
            return 0;
        }
        int lineCount = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lineCount++;
            }
        }
        return lineCount;
    }

    public record PromptBlock(
            PromptTemplateKey key,
            String content,
            Map<String, Object> variables
    ) {
        public PromptBlock {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    public record PromptAssemblySummary(
            List<String> promptKeys,
            int blockCount,
            Map<String, Map<String, Object>> variableSummary
    ) {
        public PromptAssemblySummary {
            promptKeys = promptKeys == null ? List.of() : List.copyOf(promptKeys);
            variableSummary = variableSummary == null ? Map.of() : Map.copyOf(variableSummary);
        }
    }
}
