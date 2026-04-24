# Open Agent Platform (Java)

## 后端接口设计文档 + DTO/VO 设计

------

## 1. 设计目标

本接口层需要满足以下目标：

- 支持聊天问答
- 支持 SSE 流式输出
- 支持 RAG 多知识库多选
- 支持 MCP Server 热插拔
- 支持 Agent Tool 动态装配
- 支持 Trace 查询与前端展示
- 支持知识库文件上传、索引、删除、重建
- 支持会话历史与平台配置管理

接口风格建议：

- 查询类：REST + JSON
- 流式生成类：REST + SSE
- 文件上传：`multipart/form-data`
- 后台异步任务：同步返回 taskId，后续轮询或 SSE 订阅

------

## 2. API 分组

建议按以下 6 组划分：

1. 会话与聊天接口
2. 知识库接口
3. MCP 管理接口
4. Trace 接口
5. 系统配置接口
6. 通用字典与健康检查接口

建议统一前缀：

```
/api/v1
```

------

## 3. 通用响应模型

------

## 3.1 通用响应 `ApiResponse<T>`

```
public class ApiResponse<T> {
    private Integer code;
    private String message;
    private T data;
    private Long timestamp;
    private String requestId;
}
```

### 约定

- `code = 0` 表示成功
- 非 0 表示失败
- `requestId` 用于链路追踪，与 trace 体系打通

### 示例

```
{
  "code": 0,
  "message": "success",
  "data": {},
  "timestamp": 1773210000000,
  "requestId": "req_20260311_0001"
}
```

------

## 3.2 分页响应 `PageResponse<T>`

```
public class PageResponse<T> {
    private List<T> records;
    private Long total;
    private Long pageNo;
    private Long pageSize;
    private Boolean hasMore;
}
```

------

## 3.3 错误响应 `ApiError`

```
public class ApiError {
    private Integer code;
    private String message;
    private String detail;
    private String requestId;
    private Long timestamp;
}
```

------

## 4. 会话与聊天接口

------

## 4.1 创建会话

### `POST /api/v1/conversations`

### 请求 DTO：`ConversationCreateRequest`

```
public class ConversationCreateRequest {
    private String title;
    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private List<Long> knowledgeBaseIds;
    private List<Long> mcpServerIds;
    private Long agentProfileId;
    private Long systemPromptId;
}
```

### 返回 VO：`ConversationVO`

```
public class ConversationVO {
    private Long id;
    private String title;
    private String modeCode;
    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private List<KnowledgeBaseSimpleVO> selectedKnowledgeBases;
    private List<McpServerSimpleVO> selectedMcpServers;
    private String status;
    private Long createdAt;
    private Long updatedAt;
}
```

### 说明

- 新会话可在创建时就带上默认知识库和 MCP server
- `modeCode` 可由后端根据开关自动计算：
  - `CHAT`
  - `RAG`
  - `AGENT`
  - `RAG_AGENT`

------

## 4.2 查询会话列表

### `GET /api/v1/conversations`

### 查询参数

- `pageNo`
- `pageSize`
- `keyword`
- `status`

### 返回

```
ApiResponse<PageResponse<ConversationVO>>
```

------

## 4.3 查询会话详情

### `GET /api/v1/conversations/{conversationId}`

### 返回 VO：`ConversationDetailVO`

```
public class ConversationDetailVO extends ConversationVO {
    private Long userId;
    private Long systemPromptId;
    private Long agentProfileId;
    private String extJson;
    private Long lastMessageAt;
}
```

------

## 4.4 更新会话配置

### `PUT /api/v1/conversations/{conversationId}`

### 请求 DTO：`ConversationUpdateRequest`

```
public class ConversationUpdateRequest {
    private String title;
    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private List<Long> knowledgeBaseIds;
    private List<Long> mcpServerIds;
    private Long systemPromptId;
    private Long agentProfileId;
}
```

### 返回

```
ApiResponse<ConversationVO>
```

------

## 4.5 删除/归档会话

### `DELETE /api/v1/conversations/{conversationId}`

逻辑删除或归档均可，建议第一版做归档。

### 返回

```
public class OperationResultVO {
    private Boolean success;
    private String message;
}
```

------

## 4.6 发送消息（非流式）

### `POST /api/v1/chat/send-sync`

这个接口主要用于：

- 调试
- 单元测试
- 后台集成调用
- 不要求 streaming 的场景

### 请求 DTO：`ChatSendRequest`

```
public class ChatSendRequest {
    private Long conversationId;
    private String message;
    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private List<Long> knowledgeBaseIds;
    private List<Long> mcpServerIds;
    private Map<String, Object> options;
}
```

### `options` 建议支持

- `temperature`
- `topP`
- `maxTokens`
- `traceEnabled`
- `streamEnabled`（非流式接口通常为 false）

### 返回 VO：`ChatAnswerVO`

```
public class ChatAnswerVO {
    private String requestId;
    private Long conversationId;
    private String answer;
    private String finishReason;
    private Boolean usedRag;
    private Boolean usedTools;
    private Long elapsedMillis;
    private ToolConfirmationPendingVO pendingConfirmation;
}

public class ToolConfirmationPendingVO {
    private Long id;
    private String requestId;
    private Long conversationId;
    private Long userMessageId;
    private String toolCallId;
    private String toolName;
    private String toolTitle;
    private String serverName;
    private String riskLevel;
    private String status;
    private String statusMessage;
    private Long expiresAt;
    private Long createdAt;
}
```

### 说明

- 当命中高风险工具且当前请求需要人工确认时：
  - `finishReason = tool_confirmation_required`
  - `answer` 为空字符串
  - `pendingConfirmation` 返回待确认记录
- 同步接口不会因为待确认而退化成 500。

------

## 4.7 发送消息（流式默认方案）

默认采用“两步式”：

1. `POST /api/v1/chat/send`
2. `GET /api/v1/chat/stream/{requestId}`

这样前端可以继续使用原生 `EventSource`，同时也能和 Trace 的 `GET /api/v1/traces/stream/{requestId}` 保持一致。

### 第一步：`POST /api/v1/chat/send`

### Content-Type

```
application/json
```

### 返回

```
ApiResponse<ChatRequestAcceptedVO>
```

### 请求 DTO：`ChatSendRequest`

仍使用 `ChatSendRequest`

### 返回 VO：`ChatRequestAcceptedVO`

```
public class ChatRequestAcceptedVO {
    private String requestId;
    private Long conversationId;
    private String status; // ACCEPTED / RUNNING
    private Long acceptedAt;
}
```

### 说明

- `requestId` 是一次生成任务的唯一标识
- 接口只负责创建请求，不直接返回 token 流
- 若同一 `conversationId` 已有生成任务执行中，建议返回 `409 Conflict` 或明确的排队状态
- 请求中的 `enableRag` / `enableAgent` / `knowledgeBaseIds` / `mcpServerIds` / `memoryEnabled` 为**本次请求覆盖项**
- 覆盖优先级固定为：**请求参数 > 会话 settings > 系统默认**
- 请求级覆盖只在当次生效，不自动回写会话配置

### 第二步：`GET /api/v1/chat/stream/{requestId}`

### 返回

```
text/event-stream
```

### SSE 事件类型设计

建议统一为：

- `message_start`
- `progress`
- `token`
- `message_end`
- `error`

说明：

- 聊天流只承载回答生成相关事件
- `progress` 可用于推理模型首 token 前的“思考中”提示，但它是瞬时事件，不进入会话历史与数据库
- RAG / Tool / 内部执行过程放到独立 Trace SSE 通道
- LangChain4j 的 `StreamingChatModel` 通过 `StreamingChatResponseHandler` 处理流式输出，非常适合映射到 SSE 事件流

### SSE 示例

```
event: message_start
data: {"requestId":"req_xxx","conversationId":1001}

event: progress
data: {"requestId":"req_xxx","status":"THINKING","message":"模型正在思考，请稍候…","elapsedMillis":1800}

event: token
data: {"content":"根据当前配置..."}

event: message_end
data: {"requestId":"req_xxx","answer":"最终回答","finishReason":"stop","pendingConfirmation":null}
```

### 后端建议

这个接口不直接返回完整 JSON，而是：

- 首 token 前可按需发送瞬时 `progress` 提示
- token 实时下发
- 最终 answer 持久化后发送 `message_end`

补充约定：

- `progress/thinking` 事件只服务当前在线用户体验
- 不写入 `conversation_message`
- 不写入 `trace_event`
- 不参与后续模型上下文

### 待确认场景的 `message_end`

当流式链路命中高风险工具时，当前 SSE 必须正常结束：

```
event: message_end
data: {
  "requestId": "req_xxx",
  "answer": "",
  "finishReason": "tool_confirmation_required",
  "pendingConfirmation": {
    "id": 3001,
    "requestId": "req_xxx",
    "conversationId": 1001,
    "userMessageId": 2001,
    "toolCallId": "call_1",
    "toolName": "weather-mcp__delete_file",
    "riskLevel": "HIGH",
    "status": "PENDING",
    "expiresAt": 1777017600000,
    "createdAt": 1777017000000
  }
}
```

- 前端据此把当前 assistant 气泡切到“待确认”状态。
- SSE 必须结束，不能让前端一直卡在流式连接里。

### 4.7.3 工具确认接口

#### `POST /api/v1/chat/tool-confirmations/{confirmationId}/approve`

- 作用：确认执行已记录的高风险工具请求。
- 返回：`ApiResponse<ChatRequestAcceptedVO>`
- 说明：后端不会重新让模型生成 tool call，而是继续执行已记录的 `tool request`，并开启新的 continuation `requestId` 供聊天 SSE / Trace SSE 订阅。

#### `POST /api/v1/chat/tool-confirmations/{confirmationId}/reject`

- 作用：拒绝执行已记录的高风险工具请求。
- 返回：`ApiResponse<ChatRequestAcceptedVO>`
- 说明：后端会构造结构化 `USER_REJECTED` tool result 回送模型，继续完成这一轮自然语言回答。

#### `GET /api/v1/chat/tool-confirmations/pending?conversationId={conversationId}`

- 作用：按会话查询当前仍可操作的待确认工具调用，用于页面刷新或重新进入会话后的状态恢复。
- 返回：`ApiResponse<List<ToolConfirmationPendingVO>>`
- 说明：
  - 只返回仍处于 `PENDING` 且未过期的记录。
  - 查询时会把已经超过 `expiresAt` 的 `PENDING` 记录推进为 `EXPIRED`，并从本次可操作结果中剔除。
  - 前端可根据 `userMessageId` 把待确认 assistant 状态恢复到对应用户消息之后。

------

## 4.8 查询会话历史

### `GET /api/v1/conversations/{conversationId}/messages`

### 查询参数

- `pageNo`
- `pageSize`
- `beforeMessageId`
- `afterMessageId`

### 返回 VO：`ConversationMessageVO`

```
public class ConversationMessageVO {
    private Long id;
    private Long conversationId;
    private String roleCode;
    private String messageType;
    private String content;
    private Object contentJson;
    private Integer tokenCount;
    private String modelName;
    private String finishReason;
    private Long parentMessageId;
    private Long createdAt;
}
```

### 返回

```
ApiResponse<PageResponse<ConversationMessageVO>>
```

------

## 4.9 重新生成回答

### `POST /api/v1/chat/regenerate`

### 请求 DTO：`ChatRegenerateRequest`

```
public class ChatRegenerateRequest {
    private Long conversationId;
    private Long userMessageId;
    private Boolean enableRag;
    private Boolean enableAgent;
    private List<Long> knowledgeBaseIds;
    private List<Long> mcpServerIds;
    private Map<String, Object> options;
}
```

### 返回

`ApiResponse<ChatAnswerVO>` 或 SSE 版本

------

## 5. 知识库接口

------

## 5.1 创建知识库

### `POST /api/v1/knowledge-bases`

### 请求 DTO：`KnowledgeBaseCreateRequest`

```
public class KnowledgeBaseCreateRequest {
    private String name;
    private String description;
    private String embeddingModelName;
    private Integer embeddingDimension;
    private String parserStrategy;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
```

### 返回 VO：`KnowledgeBaseVO`

```
public class KnowledgeBaseVO {
    private Long id;
    private String name;
    private String description;
    private String status;
    private String embeddingModelName;
    private Integer embeddingDimension;
    private String milvusCollectionName;
    private String milvusPartitionName;
    private String parserStrategy;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Long fileCount;
    private Long segmentCount;
    private Long createdAt;
}
```

------

## 5.2 查询知识库列表

### `GET /api/v1/knowledge-bases`

### 查询参数

- `pageNo`
- `pageSize`
- `keyword`
- `status`

### 返回

```
ApiResponse<PageResponse<KnowledgeBaseVO>>
```

------

## 5.3 查询知识库详情

### `GET /api/v1/knowledge-bases/{kbId}`

### 返回

```
ApiResponse<KnowledgeBaseDetailVO>
public class KnowledgeBaseDetailVO extends KnowledgeBaseVO {
    private Long ownerUserId;
    private String extJson;
}
```

------

## 5.4 更新知识库

### `PUT /api/v1/knowledge-bases/{kbId}`

### 请求 DTO：`KnowledgeBaseUpdateRequest`

```
public class KnowledgeBaseUpdateRequest {
    private String name;
    private String description;
    private String parserStrategy;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String status;
}
```

------

## 5.5 上传文件到知识库

### `POST /api/v1/knowledge-bases/{kbId}/files/upload`

### Content-Type

```
multipart/form-data
```

### 表单字段

- `file`
- `autoIndex`：是否自动索引

### 返回 VO：`KnowledgeFileVO`

```
public class KnowledgeFileVO {
    private Long id;
    private Long knowledgeBaseId;
    private String fileName;
    private String fileExt;
    private Long fileSize;
    private String parseStatus;
    private String indexStatus;
    private String storageUri;
    private String errorMessage;
    private Long createdAt;
}
```

------

## 5.6 查询知识库文件列表

### `GET /api/v1/knowledge-bases/{kbId}/files`

### 返回

```
ApiResponse<PageResponse<KnowledgeFileVO>>
```

------

## 5.7 开始索引文件

### `POST /api/v1/knowledge-files/{fileId}/index`

### 请求 DTO：`KnowledgeFileIndexRequest`

```
public class KnowledgeFileIndexRequest {
    private Boolean forceReindex;
}
```

### 返回 VO：`AsyncTaskVO`

```
public class AsyncTaskVO {
    private String taskId;
    private String taskType;
    private String status;
}
```

------

## 5.8 重建文件索引

### `POST /api/v1/knowledge-files/{fileId}/reindex`

### 返回

```
ApiResponse<AsyncTaskVO>
```

------

## 5.9 删除文件

### `DELETE /api/v1/knowledge-files/{fileId}`

### 行为建议

- 删除 MySQL 文件元数据
- 删除对应 segments
- 删除 Milvus 向量
- 写 trace / audit

------

## 5.10 查询文件分段

### `GET /api/v1/knowledge-files/{fileId}/segments`

### 返回 VO：`KnowledgeSegmentVO`

```
public class KnowledgeSegmentVO {
    private Long id;
    private Long knowledgeBaseId;
    private Long fileId;
    private Integer segmentNo;
    private String textPreview;
    private String fullText;
    private Integer tokenCount;
    private Integer pageNo;
    private String sourceTitle;
    private String sourcePath;
    private Object metadataJson;
    private String milvusPrimaryKey;
    private Long createdAt;
}
```

------

## 5.11 测试检索

### `POST /api/v1/knowledge-bases/retrieve`

这个接口非常重要，用于前端做“知识库调试”。

### 请求 DTO：`KnowledgeRetrieveRequest`

```
public class KnowledgeRetrieveRequest {
    private String query;
    private List<Long> knowledgeBaseIds;
    private Integer topK;
    private Double minScore;
}
```

### 返回 VO：`KnowledgeRetrieveVO`

```
public class KnowledgeRetrieveVO {
    private String query;
    private List<RagSnippetVO> snippets;
    private Integer total;
}
```

### `RagSnippetVO`

```
public class RagSnippetVO {
    private Long knowledgeBaseId;
    private Long fileId;
    private Long segmentId;
    private String fileName;
    private Integer segmentNo;
    private String contentPreview;
    private Double score;
    private Integer pageNo;
    private Object metadata;
}
```

------

## 6. MCP 管理接口

------

## 6.1 创建 MCP Server

### `POST /api/v1/mcp/servers`

### 请求 DTO：`McpServerCreateRequest`

```
public class McpServerCreateRequest {
    private String name;
    private String description;
    private String transportType; // STDIO / STREAMABLE_HTTP
    private String endpoint;
    private String commandLine;
    private Map<String, Object> argsJson;
    private Map<String, Object> envJson;
    private Map<String, Object> headersJson;
    private String authType; // NONE / BEARER / BASIC / CUSTOM
    private Map<String, Object> authConfigJson;
    private String riskLevel; // LOW / MEDIUM / HIGH
}
```

### 返回 VO：`McpServerVO`

```
public class McpServerVO {
    private Long id;
    private String name;
    private String description;
    private String protocolType;
    private String transportType;
    private String endpoint;
    private String healthStatus;
    private Boolean enabled;
    private String riskLevel;
    private Long lastConnectedAt;
    private Long lastSyncAt;
    private Long createdAt;
}
```

------

## 6.2 查询 MCP Server 列表

### `GET /api/v1/mcp/servers`

### 返回

```
ApiResponse<PageResponse<McpServerVO>>
```

------

## 6.3 查询 MCP Server 详情

### `GET /api/v1/mcp/servers/{serverId}`

### 返回 VO：`McpServerDetailVO`

```
public class McpServerDetailVO extends McpServerVO {
    private Object argsJson;
    private Object envJson;
    private Object headersJson;
    private String authType;
    private Object authConfigJsonMasked;
    private String extJson;
}
```

### 注意

敏感配置建议脱敏后返回。

------

## 6.4 更新 MCP Server

### `PUT /api/v1/mcp/servers/{serverId}`

### 请求 DTO：`McpServerUpdateRequest`

```
public class McpServerUpdateRequest {
    private String name;
    private String description;
    private String endpoint;
    private String commandLine;
    private Map<String, Object> argsJson;
    private Map<String, Object> envJson;
    private Map<String, Object> headersJson;
    private String authType;
    private Map<String, Object> authConfigJson;
    private String riskLevel;
    private Boolean enabled;
}
```

------

## 6.5 连接/探活 MCP Server

### `POST /api/v1/mcp/servers/{serverId}/connect`

### 返回 VO：`McpConnectResultVO`

```
public class McpConnectResultVO {
    private Long serverId;
    private Boolean success;
    private String healthStatus;
    private String message;
    private Long connectedAt;
}
```

LangChain4j 的 MCP Client 就是为了与 MCP Server 通信、检索并执行工具而设计的，这个接口本质上是在平台层触发一次连接校验。

------

## 6.6 同步工具列表

### `POST /api/v1/mcp/servers/{serverId}/sync-tools`

### 返回 VO：`McpToolSyncResultVO`

```
public class McpToolSyncResultVO {
    private Long serverId;
    private Integer totalTools;
    private Integer enabledTools;
    private Long syncedAt;
}
```

------

## 6.7 查询工具列表

### `GET /api/v1/mcp/tools`

### 查询参数

- `serverId`
- `enabled`
- `keyword`

### 返回 VO：`McpToolVO`

```
public class McpToolVO {
    private Long id;
    private Long mcpServerId;
    private String serverName;
    private String toolName; // 运行时全局唯一名
    private String originToolName; // 远端 MCP 原始工具名
    private String toolTitle;
    private String description;
    private Object inputSchemaJson;
    private Object outputSchemaJson;
    private Boolean enabled;
    private String riskLevel;
    private String versionNo;
    private Long syncedAt;
}
```

------

## 6.8 启用/禁用工具

### `POST /api/v1/mcp/tools/{toolId}/enable`

### `POST /api/v1/mcp/tools/{toolId}/disable`

### 返回

```
ApiResponse<OperationResultVO>
```

------

## 6.9 会话绑定 MCP Server

### `POST /api/v1/conversations/{conversationId}/mcp-servers`

### 请求 DTO：`ConversationMcpBindingRequest`

```
public class ConversationMcpBindingRequest {
    private List<Long> serverIds;
}
```

------

## 7. Trace 接口

------

## 7.1 查询请求级 Trace

### `GET /api/v1/traces/{requestId}`

### 返回 VO：`TraceDetailVO`

```
public class TraceDetailVO {
    private String requestId;
    private Long conversationId;
    private List<TraceEventVO> events;
}
```

### `TraceEventVO`

```
public class TraceEventVO {
    private Long id;
    private String requestId;
    private Long conversationId;
    private Long messageId;
    private String eventType;
    private String eventStage;
    private String eventSource; // LANGCHAIN4J_NATIVE / APP_CUSTOM
    private Object eventPayloadJson;
    private Boolean successFlag;
    private Integer costMillis;
    private Long createdAt;
}
```

------

## 7.2 查询会话 Trace 列表

### `GET /api/v1/conversations/{conversationId}/traces`

### 查询参数

- `pageNo`
- `pageSize`
- `requestId`
- `eventType`

### 返回

```
ApiResponse<PageResponse<TraceEventVO>>
```

------

## 7.3 Trace 流式订阅

### `GET /api/v1/traces/stream/{requestId}`

### 返回

```
text/event-stream
```

### 用途

- 前端单独订阅 trace 时间线
- 聊天区和 trace 区解耦
- 内部事件与用户可见消息分离

### 建议事件来源

- LangChain4j 原生 observability / listener 事件优先直接映射
- 平台特有事件再补 `APP_CUSTOM` 类型

------

## 8. Memory 与配置接口

------

## 8.1 查询会话配置

### `GET /api/v1/conversations/{conversationId}/settings`

### 返回 VO：`ConversationSettingsVO`

```
public class ConversationSettingsVO {
    private Long conversationId;
    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private String memoryType;
    private Integer maxMessages;
    private Integer maxTokens;
    private List<Long> selectedKnowledgeBaseIds;
    private List<Long> selectedMcpServerIds;
}
```

### 说明

LangChain4j 的 ChatMemory 常用实现是 `MessageWindowChatMemory` 与 `TokenWindowChatMemory`，所以这里保留 `memoryType`、`maxMessages`、`maxTokens` 是合理的。

这里返回的是**会话持久配置**，不是某一次请求的实际生效配置。实际执行时仍按：

- 请求参数覆盖项
- 会话持久配置
- 系统默认配置

三层顺序解析。

------

## 8.2 更新会话配置

### `PUT /api/v1/conversations/{conversationId}/settings`

### 请求 DTO：`ConversationSettingsUpdateRequest`

```
public class ConversationSettingsUpdateRequest {
    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private String memoryType;
    private Integer maxMessages;
    private Integer maxTokens;
    private List<Long> selectedKnowledgeBaseIds;
    private List<Long> selectedMcpServerIds;
}
```

### 说明

这个接口负责修改会话的持久配置。聊天发送接口里的同名字段只做单次覆盖，不回写这里。

------

## 8.3 清空会话记忆

### `POST /api/v1/conversations/{conversationId}/memory/clear`

### 返回

```
ApiResponse<OperationResultVO>
```

### 说明

这个接口只清理 memory，不一定删除完整 history。
 因为 memory 与 history 应明确分离。

------

## 9. 系统配置与字典接口

------

## 9.1 查询系统可用模型

### `GET /api/v1/system/models`

### 返回 VO：`ModelOptionVO`

```
public class ModelOptionVO {
    private String modelName;
    private String modelType; // CHAT / EMBEDDING
    private Boolean enabled;
    private String baseUrl;
}
```

------

## 9.2 查询系统字典

### `GET /api/v1/system/dicts`

### 返回 VO：`SystemDictVO`

```
public class SystemDictVO {
    private List<DictItemVO> conversationModes;
    private List<DictItemVO> mcpTransportTypes;
    private List<DictItemVO> mcpRiskLevels;
    private List<DictItemVO> knowledgeParseStrategies;
    private List<DictItemVO> traceEventTypes;
}
public class DictItemVO {
    private String code;
    private String label;
}
```

------

## 9.3 健康检查

### `GET /api/v1/system/health`

### 返回 VO：`SystemHealthVO`

```
public class SystemHealthVO {
    private String appStatus;
    private String mysqlStatus;
    private String milvusStatus;
    private String chatModelStatus;
    private String embeddingModelStatus;
    private Integer healthyMcpServers;
    private Long timestamp;
}
```

------

## 10. DTO 设计规范

------

## 10.1 Request DTO 规范

建议所有请求 DTO：

- 只承载接口输入
- 不直接复用实体
- 带 Bean Validation 注解

示例：

```
public class ChatSendRequest {

    @NotNull
    private Long conversationId;

    @NotBlank
    @Size(max = 20000)
    private String message;

    private Boolean enableRag;
    private Boolean enableAgent;
    private Boolean memoryEnabled;
    private List<Long> knowledgeBaseIds;
    private List<Long> mcpServerIds;
    private Map<String, Object> options;
}
```

------

## 10.2 VO 规范

VO 要面向前端：

- 时间统一转毫秒时间戳或 ISO 字符串
- 枚举统一返回 code + label，或者前端自己做字典映射
- JSON 字段允许返回对象而不是字符串
- 不暴露内部密钥、完整认证配置、服务端敏感环境变量

------

## 11. Controller 拆分建议

建议拆成以下 controller：

### `ConversationController`

- 创建会话
- 查询会话
- 更新会话
- 删除会话
- 查询会话配置

### `ChatController`

- 发送消息
- 流式发送
- 重新生成
- 查询历史

### `KnowledgeBaseController`

- 创建知识库
- 更新知识库
- 查询知识库
- 检索测试

### `KnowledgeFileController`

- 上传文件
- 索引文件
- 重建索引
- 查询文件与分段

### `McpServerController`

- 创建 MCP
- 更新 MCP
- 连接测试
- 同步工具
- 查询 server

### `McpToolController`

- 查询工具
- 启停工具

### `TraceController`

- 查询 trace
- 订阅 trace stream

### `SystemController`

- 字典
- 健康检查
- 模型列表

------

## 12. Service 接口建议

------

### `ConversationApplicationService`

```
ConversationVO createConversation(ConversationCreateRequest request);
ConversationDetailVO getConversation(Long conversationId);
PageResponse<ConversationVO> pageConversations(ConversationQuery query);
ConversationVO updateConversation(Long conversationId, ConversationUpdateRequest request);
void archiveConversation(Long conversationId);
```

### `ChatApplicationService`

```
ChatAnswerVO sendSync(ChatSendRequest request);
ChatRequestAcceptedVO submit(ChatSendRequest request);
ChatRequestAcceptedVO approveToolConfirmation(Long confirmationId);
ChatRequestAcceptedVO rejectToolConfirmation(Long confirmationId);
void stream(String requestId, SseEmitter emitter);
ChatAnswerVO regenerate(ChatRegenerateRequest request);
PageResponse<ConversationMessageVO> pageMessages(Long conversationId, MessageQuery query);
```

### `KnowledgeApplicationService`

```
KnowledgeBaseVO createKnowledgeBase(KnowledgeBaseCreateRequest request);
KnowledgeFileVO uploadFile(Long kbId, MultipartFile file, Boolean autoIndex);
AsyncTaskVO indexFile(Long fileId, Boolean forceReindex);
KnowledgeRetrieveVO retrieve(KnowledgeRetrieveRequest request);
```

### `McpApplicationService`

```
McpServerVO createServer(McpServerCreateRequest request);
McpConnectResultVO connect(Long serverId);
McpToolSyncResultVO syncTools(Long serverId);
PageResponse<McpToolVO> pageTools(McpToolQuery query);
void enableTool(Long toolId);
void disableTool(Long toolId);
```

### `TraceApplicationService`

```
TraceDetailVO getTraceDetail(String requestId);
PageResponse<TraceEventVO> pageConversationTraces(Long conversationId, TraceQuery query);
void streamTrace(String requestId, SseEmitter emitter);
```

------

## 13. SSE 设计建议

本项目固定采用 **两步式 + 双 SSE 通道**，不再保留两套并行方案：

1. `POST /api/v1/chat/send`
2. `GET /api/v1/chat/stream/{requestId}`
3. `GET /api/v1/traces/stream/{requestId}`

原因：

- 浏览器原生 `EventSource` 更适合 `GET + URL` 的订阅方式
- `requestId` 更适合表示“一次生成任务”，比 `conversationId` 更利于重试、重新生成、并发控制
- 聊天区与 Trace 区天然解耦
- Trace 可单独重放与补订阅

补充说明：

- 如果未来需要 `POST + text/event-stream`，前端应改为 `fetch` 流式读取或自定义 SSE 客户端
- 第一版不建议同时维护两种流式协议，避免前后端分叉

------

## 14. 接口状态码建议

建议保留 HTTP 标准语义：

- `200`：成功
- `400`：参数错误
- `401`：未认证
- `403`：无权限
- `404`：资源不存在
- `409`：状态冲突
- `500`：系统异常

业务错误码另行定义，例如：

- `10001`：会话不存在
- `10002`：知识库不存在
- `10003`：MCP 服务不可用
- `10004`：索引任务执行失败
- `10005`：流式生成中断
- `10006`：工具调用失败

------

## 15. 第一版最小落地接口集合

如果你想尽快开工，我建议第一版先只实现这些：

### 聊天

- `POST /api/v1/conversations`
- `GET /api/v1/conversations`
- `POST /api/v1/chat/send`
- `GET /api/v1/chat/stream/{requestId}`
- `POST /api/v1/chat/send-sync`
- `GET /api/v1/conversations/{conversationId}/messages`

### 知识库

- `POST /api/v1/knowledge-bases`
- `POST /api/v1/knowledge-bases/{kbId}/files/upload`
- `POST /api/v1/knowledge-files/{fileId}/index`
- `POST /api/v1/knowledge-bases/retrieve`

### MCP

- `POST /api/v1/mcp/servers`
- `POST /api/v1/mcp/servers/{serverId}/connect`
- `POST /api/v1/mcp/servers/{serverId}/sync-tools`
- `GET /api/v1/mcp/tools`

### Trace

- `GET /api/v1/traces/{requestId}`

这套接口就足够支撑：

- 聊天
- RAG
- MCP 热插拔
- 基础 trace 可视化

------

## 16. 最终建议

这份接口设计的核心思想可以概括成一句话：

**会话是中心、聊天是入口、RAG 和 MCP 是能力源、Trace 是观测层。**

也就是说，前端所有页面最终都围绕一个 `conversationId` 运转，后端所有能力也最终汇聚到一次 `chat request` 上。
