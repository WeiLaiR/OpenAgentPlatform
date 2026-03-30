package com.weilair.openagent.chat.prompt;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.stereotype.Component;

/**
 * 第一版先把 Prompt 模板收口到代码目录里，避免继续散落在 assembler / resolver / AI Service 注解中。
 * 当前仍然使用 LangChain4j 官方 `PromptTemplate` 作为模板渲染能力，
 * 项目自定义层只负责模板目录和 key 管理，不重复发明模板引擎。
 */
@Component
public class PromptTemplateCatalog {

    private final Map<PromptTemplateKey, PromptTemplate> templates;

    public PromptTemplateCatalog() {
        Map<PromptTemplateKey, PromptTemplate> templateMap = new EnumMap<>(PromptTemplateKey.class);
        templateMap.put(
                PromptTemplateKey.SYSTEM_BASE,
                PromptTemplate.from("""
                        你是 OpenAgentPlatform 中的智能助手。
                        请优先给出准确、可解释、便于学习 LangChain4j 主链路的回答。
                        如果信息不足，请明确指出依据不足，不要把猜测表述成已确认事实。
                        """)
        );
        templateMap.put(
                PromptTemplateKey.RAG_CONTEXT,
                PromptTemplate.from("""
                        {{userMessage}}

                        请优先基于以下知识库检索参考片段回答。
                        如果参考片段不足以支持结论，请明确说明。

                        {{contents}}
                        """)
        );
        templateMap.put(
                PromptTemplateKey.AGENT_TOOL_POLICY,
                PromptTemplate.from("""
                        当工具可以提供更可靠的信息时，应优先使用工具。
                        不要编造工具调用结果；如果工具失败或被禁用，要明确说明限制。
                        """)
        );
        templateMap.put(
                PromptTemplateKey.MCP_TOOL_SELECTION,
                PromptTemplate.from("""
                        只允许使用当前会话显式暴露的 MCP 工具。
                        可见工具不代表必须调用，应根据任务目标选择最合适的工具。
                        """)
        );
        templateMap.put(
                PromptTemplateKey.ANSWER_FORMAT,
                PromptTemplate.from("""
                        回答时先给结论，再补充必要依据。
                        涉及知识库内容时，请区分“来自检索片段的结论”和“模型自身补充说明”。
                        """)
        );
        this.templates = Map.copyOf(templateMap);
    }

    public String render(PromptTemplateKey key) {
        return render(key, Map.of());
    }

    public String render(PromptTemplateKey key, Map<String, Object> variables) {
        Map<String, Object> safeVariables = variables == null ? Map.of() : new LinkedHashMap<>(variables);
        return template(key).apply(safeVariables).text();
    }

    public PromptTemplate template(PromptTemplateKey key) {
        PromptTemplate template = templates.get(key);
        if (template == null) {
            throw new IllegalArgumentException("未知 Prompt 模板: " + key);
        }
        return template;
    }
}
