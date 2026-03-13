# MCP 接入与信息提供约定

本文档用于统一后续 MCP 接入时的沟通格式，并明确当前项目的落地边界。

## 1. 先说结论

本项目中的 MCP Server 信息，目标落地方式是：

- 存数据库
- 运行时热插拔
- 会话级动态装配

不采用的方式：

- 不把具体 MCP Server 长期写死在 `application.yaml` / `application-local.yaml`
- 不把用户可维护的 MCP 列表直接硬编码在后端配置文件中

原因很明确：

- 项目目标本身就要求 `MCP 热插拔`
- 后续还要支持用户自行新增 MCP
- MCP tool 是动态发现的，必须和 `mcp_server`、`mcp_tool_snapshot` 表设计保持一致

因此，配置文件里最多只应该放：

- 平台级默认值
- 安全策略
- 超时
- allowlist
- 是否自动同步等全局行为

而不是放具体某一个 MCP server 的注册信息。

## 2. 当前数据库设计边界

当前仓库里已经有对应表结构：

- `mcp_server`
- `mcp_tool_snapshot`
- `conversation_mcp_binding`

可参考：

- `docs/数据库设计文档.md`
- `docs/项目实现架构.md`

职责划分建议：

- `mcp_server`：保存 MCP Server 注册信息
- `mcp_tool_snapshot`：保存远端工具快照
- `conversation_mcp_binding`：保存会话级启用关系

## 3. 后续和我沟通时的推荐 JSON 格式

你后续给我 MCP 信息时，推荐直接用下面这种格式。

这个格式是“沟通输入格式”，目的是方便我快速映射到数据库结构、接口 DTO 和运行时配置。

### 3.1 STDIO 类型

```json
{
  "name": "sequential-thinking",
  "description": "Sequential thinking MCP server",
  "transportType": "STDIO",
  "command": "npx",
  "args": [
    "-y",
    "@modelcontextprotocol/server-sequential-thinking"
  ],
  "env": {},
  "headers": {},
  "authType": "NONE",
  "authConfig": {},
  "enabled": true,
  "riskLevel": "MEDIUM"
}
```

### 3.2 STREAMABLE_HTTP 类型

```json
{
  "name": "example-remote-mcp",
  "description": "Remote MCP server over Streamable HTTP",
  "transportType": "STREAMABLE_HTTP",
  "endpoint": "https://example.com/mcp",
  "headers": {
    "Authorization": "Bearer YOUR_TOKEN"
  },
  "authType": "BEARER",
  "authConfig": {
    "tokenSource": "DIRECT"
  },
  "enabled": true,
  "riskLevel": "MEDIUM"
}
```

## 4. 各字段说明

建议你后续尽量提供这些字段：

- `name`
  - 平台内唯一标识，建议短、稳定、可读
- `description`
  - 方便前端管理页展示
- `transportType`
  - `STDIO` 或 `STREAMABLE_HTTP`
- `command`
  - 仅 `STDIO` 需要
- `args`
  - 仅 `STDIO` 需要
- `env`
  - 仅 `STDIO` 常见
- `endpoint`
  - 仅 `STREAMABLE_HTTP` 需要
- `headers`
  - 常用于远程认证
- `authType`
  - 如 `NONE` / `BEARER` / `BASIC` / `CUSTOM`
- `authConfig`
  - 认证补充信息
- `enabled`
  - 是否默认启用
- `riskLevel`
  - `LOW` / `MEDIUM` / `HIGH`

## 5. 你这次给的 MCP 信息是否足够

你刚才给的这份：

```json
{
  "mcpServers": {
    "sequential-thinking": {
      "command": "npx",
      "args": [
        "-y",
        "@modelcontextprotocol/server-sequential-thinking"
      ]
    }
  }
}
```

对于“首个 STDIO MCP 样例接入”来说，已经基本足够。

我可以从中推断出：

- `name = sequential-thinking`
- `transportType = STDIO`
- `command = npx`
- `args = ["-y", "@modelcontextprotocol/server-sequential-thinking"]`
- `authType = NONE`

但如果你后续要批量给我 MCP 信息，还是推荐统一成第 3 节那种结构，少做推断，避免歧义。

## 6. 额外还需要你补什么

对于 `STDIO` 类型，最好再补这几项：

- 是否默认启用
- 风险等级
- 是否需要额外环境变量

对于 `STREAMABLE_HTTP` 类型，最好再补这几项：

- 完整 endpoint
- 认证方式
- token 或 headers
- 是否只允许管理员配置

## 7. 当前阶段的实现建议

当前阶段建议这样推进：

1. 先完成 `mcp_server` 的增删改查
2. 再完成“连接 / 断开 / 同步工具”
3. 再完成 `mcp_tool_snapshot`
4. 最后做会话级动态装配和热插拔

这样路径最稳，也最符合当前文档主线。
