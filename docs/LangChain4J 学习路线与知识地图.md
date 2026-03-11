# LangChain4J 学习路线与知识地图

## 1. 文档目标

这份文档用于把本项目的学习主线固定下来：**以 LangChain4j 为唯一 AI 编排核心，逐步完成 Chat、Streaming、Memory、RAG、Tools、MCP、Trace 的工程化落地。**

它不是 API 手册，而是学习顺序、能力边界和项目落点说明。

## 2. LangChain4j 核心能力地图

结合官方文档，当前项目最需要掌握的能力可以收敛为 6 个层级：

### 2.1 基础对话层

- `ChatModel`
- `StreamingChatModel`
- `ChatRequestParameters`
- OpenAI-compatible 接入方式

这是最底层入口。先跑通“单轮聊天 + 流式输出”，再谈上层能力。

### 2.2 高层封装层

- `AI Services`
- `@SystemMessage` / `@UserMessage`
- `Result<T>`
- `TokenStream`

官方当前更推荐围绕 `AI Services` 组织业务逻辑，而不是继续依赖传统 `Chain` 抽象。对本项目来说，这一层最适合承接聊天编排服务。

### 2.3 记忆层

- `ChatMemory`
- `MessageWindowChatMemory`
- `TokenWindowChatMemory`
- `ChatMemoryProvider`
- `@MemoryId`

要明确区分 `memory` 和 `history`：`memory` 是发给模型的上下文，`history` 是前端展示与审计所需的完整消息留存。

### 2.4 RAG 层

- `Document` / `TextSegment` / `Metadata`
- `EmbeddingModel`
- `EmbeddingStore`
- `ContentRetriever`
- `RetrievalAugmentor`
- `DefaultRetrievalAugmentor`

项目第一阶段不需要一开始就做复杂 RAG，先完成“文档导入 -> 分块 -> 向量化 -> 检索 -> 注入上下文”的闭环。

### 2.5 Tool / Agent 层

- `@Tool`
- `ToolSpecification`
- `ToolProvider`
- `ToolExecution`
- `beforeToolExecution` / `onToolExecuted`

这里建议先用“AI Services + Tools”完成可控的工具调用，再做更复杂的 Agent 编排。第一版不应把重点放在实验性 agentic 模块上。

### 2.6 MCP 与可观测层

- `McpClient`
- `McpToolProvider`
- `ChatModelListener`
- `TokenStream` 回调

MCP 解决的是“外部工具标准接入”，Observability 解决的是“内部执行链路可观测”。这两部分决定平台化能力是否成立。

## 3. 本项目中的能力落点

| 能力 | LangChain4j 关键对象 | 本项目落点 |
| --- | --- | --- |
| 基础问答 | `ChatModel` / `StreamingChatModel` | 聊天主链路、SSE 输出 |
| 高层封装 | `AI Services` | Chat 应用服务、模式编排入口 |
| 多轮上下文 | `ChatMemoryProvider` / `@MemoryId` | 会话维度记忆管理 |
| RAG | `RetrievalAugmentor` / `ContentRetriever` | 知识库检索与上下文注入 |
| Tool 调用 | `@Tool` / `ToolProvider` | 平台内工具与运行时工具装配 |
| MCP | `McpClient` / `McpToolProvider` | 外部 MCP Server 热插拔接入 |
| Streaming 过程事件 | `TokenStream` 回调 | token、tool call、sources 推送 |
| LLM 请求观测 | `ChatModelListener` | Trace 埋点、请求审计、调试 |

## 4. 推荐学习顺序

## Phase 0：概念打底

目标：先把 LangChain4j 的主干抽象分清楚。

需要掌握：

- `ChatModel` 和 `StreamingChatModel` 的区别
- `AI Services` 相对低层 API 的作用
- `ChatMemory`、`RAG`、`Tools`、`MCP` 分别解决什么问题

完成标志：

- 能口头讲清楚四种运行模式的依赖关系
- 能分清 `memory / history / trace`

## Phase 1：先跑通基础聊天

目标：完成最小聊天闭环。

建议顺序：

1. 接本地 Qwen 的 OpenAI-compatible Chat API
2. 实现 `POST /chat/send + GET /chat/stream/{requestId}`
3. 用 `StreamingChatModel` 或 `AI Services + TokenStream` 输出流式答案

完成标志：

- 前端能实时看到 token 输出
- 后端能生成并关联 `requestId`

## Phase 2：引入 AI Services 与 Memory

目标：把底层调用升级为可维护的业务封装。

建议学习点：

- `AI Services` 接口定义方式
- `ChatMemoryProvider`
- `@MemoryId`
- 同一 `memoryId` 不并发调用的约束

完成标志：

- 每个会话有独立 memory
- MySQL 中 history 与 memory 存储逻辑分开

## Phase 3：完成第一版 RAG

目标：跑通“知识增强问答”。

建议学习点：

- `Document` / `TextSegment`
- `EmbeddingStoreContentRetriever`
- `DefaultRetrievalAugmentor`
- `Result<T>.sources()` 或 `TokenStream.onRetrieved()`

完成标志：

- 能返回答案
- 能展示命中的知识片段
- 能明确哪些内容进入了模型上下文

## Phase 4：完成 Tools 与 MCP 接入

目标：让模型具备“查外部能力”的执行链路。

建议学习点：

- `@Tool` 的声明方式
- `ToolProvider` 动态提供工具
- `McpClient` 与 `McpToolProvider`
- `beforeToolExecution` 与 `onToolExecuted`

完成标志：

- 会话级动态装配工具集
- MCP 工具可按 server 启停
- Trace 能展示 tool request / tool result

## Phase 5：补齐 Trace 与可观测能力

目标：把“能跑”升级为“能解释”。

建议学习点：

- `ChatModelListener`
- `TokenStream` 的 streaming 生命周期回调
- `onPartialToolCall` / `onCompleteResponse`

完成标志：

- 能记录请求参数、模型响应、错误
- 能区分聊天流与 Trace 流

## 5. 学习时应重点记住的几个边界

### 5.1 `AI Services` 是主线，不是可选项

官方当前把 `AI Services` 作为高层主抽象，而 `Chain` 更偏历史兼容与有限场景。项目的聊天应用服务建议优先围绕 `AI Services` 设计。

### 5.2 `ChatMemory` 不是完整历史

`ChatMemory` 只负责发给模型的上下文。完整消息、审计、重放、前端展示仍然要落到你自己的 MySQL 表。

### 5.3 RAG 入口是 `RetrievalAugmentor`

项目里不要把“RAG”理解成“手工拼接一段上下文文本”就结束。官方主线是 `ContentRetriever -> RetrievalAugmentor -> 注入 UserMessage`。

### 5.4 Tool 调用不等于 Agent 框架

用 `@Tool`、`ToolProvider`、MCP，就已经能构建出很强的“模型可调用动作能力”。第一版没有必要先追求复杂 agentic 图编排。

### 5.5 同一会话必须串行

官方明确提示：同一 `@MemoryId` 下并发调用 AI Service 可能破坏 `ChatMemory`。因此本项目应在 `conversationId` 维度做串行控制。

## 6. 本项目当前建议暂缓的主题

下面这些不是不能学，而是优先级应靠后：

- `langchain4j-agentic` 的复杂 Agent 编排
- 大规模 Tool Search 优化
- Re-ranking、多路 Query Router 等高级 RAG
- Guardrails、Moderation、结构化输出的深度扩展

这些能力适合在基础链路稳定后再补，不应压过主线。

## 7. 最终知识地图

可以把整个项目理解为下面这张能力图：

```text
ChatModel / StreamingChatModel
        ↓
     AI Services
        ↓
  ┌─────┼───────────┬──────────┐
  ↓     ↓           ↓          ↓
Memory  RAG        Tools      MCP
  ↓     ↓           ↓          ↓
History Sources   ToolExec   Dynamic Tool Set
  └─────┴──────┬────┴──────────┘
               ↓
        Orchestrator / Trace
               ↓
        Vue3 + SSE + Timeline
```

一句话总结：**先学“模型怎么接”，再学“上下文怎么补”，再学“工具怎么调”，最后学“链路怎么观测”。**

## 8. 推荐配套阅读顺序

1. AI Services
2. Response Streaming
3. Chat Memory
4. RAG
5. Tools
6. MCP
7. Observability

## 9. 参考资料

以下内容基于 2026-03-11 查阅到的官方资料整理：

- LangChain4j 官方首页：https://docs.langchain4j.dev/
- AI Services：https://docs.langchain4j.dev/tutorials/ai-services/
- Response Streaming：https://docs.langchain4j.dev/tutorials/response-streaming/
- Chat Memory：https://docs.langchain4j.dev/tutorials/chat-memory/
- RAG：https://docs.langchain4j.dev/tutorials/rag/
- Tools：https://docs.langchain4j.dev/tutorials/tools/
- MCP：https://docs.langchain4j.dev/tutorials/mcp/
- Observability：https://docs.langchain4j.dev/tutorials/observability/
- LangChain4j GitHub：https://github.com/langchain4j/langchain4j

本仓库关联文档：

- `docs/项目实现架构.md`
- `docs/模块拆分与目录结构设计文档.md`
- `docs/后端接口设计文档(DTO、VO设计).md`
- `docs/文档总览与重构建议.md`
