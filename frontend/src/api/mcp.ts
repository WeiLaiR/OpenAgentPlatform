import { request } from './http'

export interface McpServer {
  id: number
  name: string
  description: string | null
  protocolType: string
  transportType: 'STDIO' | 'STREAMABLE_HTTP'
  endpoint: string | null
  command: string | null
  args: string[]
  env: Record<string, string>
  headers: Record<string, string>
  authType: string
  authConfig: Record<string, unknown> | null
  enabled: boolean
  healthStatus: string
  riskLevel: string
  toolCount: number
  lastConnectedAt: number | null
  lastSyncAt: number | null
  createdAt: number | null
  updatedAt: number | null
}

export interface McpServerUpsertRequest {
  name: string
  description?: string
  transportType: 'STDIO' | 'STREAMABLE_HTTP'
  endpoint?: string
  command?: string
  args?: string[]
  env?: Record<string, string>
  headers?: Record<string, string>
  authType?: string
  authConfig?: Record<string, unknown>
  enabled?: boolean
  riskLevel?: string
}

export interface McpToolSnapshot {
  id: number
  mcpServerId: number
  serverName: string
  runtimeToolName: string
  originToolName: string
  toolTitle: string
  description: string | null
  inputSchemaJson: unknown
  outputSchemaJson: unknown
  enabled: boolean
  riskLevel: string
  versionNo: string | null
  syncHash: string | null
  syncedAt: number | null
  updatedAt: number | null
}

export interface McpConnectionTestResult {
  serverId: number
  serverName: string
  healthStatus: string
  toolCount: number
  toolNames: string[]
  checkedAt: number
}

export interface McpToolSyncResult {
  serverId: number
  serverName: string
  syncedCount: number
  runtimeToolNames: string[]
  syncedAt: number
}

export async function listMcpServers(params: {
  keyword?: string
  enabled?: number
  transportType?: string
  limit?: number
} = {}): Promise<McpServer[]> {
  const searchParams = new URLSearchParams()

  if (params.keyword) {
    searchParams.set('keyword', params.keyword)
  }
  if (typeof params.enabled === 'number') {
    searchParams.set('enabled', String(params.enabled))
  }
  if (params.transportType) {
    searchParams.set('transportType', params.transportType)
  }
  if (typeof params.limit === 'number') {
    searchParams.set('limit', String(params.limit))
  }

  const suffix = searchParams.toString() ? `?${searchParams.toString()}` : ''
  const response = await request<McpServer[]>(`/api/v1/mcp/servers${suffix}`)
  return response.data
}

export async function createMcpServer(requestBody: McpServerUpsertRequest): Promise<McpServer> {
  const response = await request<McpServer>('/api/v1/mcp/servers', {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
  return response.data
}

export async function updateMcpServer(
  serverId: number,
  requestBody: McpServerUpsertRequest,
): Promise<McpServer> {
  const response = await request<McpServer>(`/api/v1/mcp/servers/${serverId}`, {
    method: 'PUT',
    body: JSON.stringify(requestBody),
  })
  return response.data
}

export async function testMcpConnection(serverId: number): Promise<McpConnectionTestResult> {
  const response = await request<McpConnectionTestResult>(`/api/v1/mcp/servers/${serverId}/connect`, {
    method: 'POST',
  })
  return response.data
}

export async function syncMcpTools(serverId: number): Promise<McpToolSyncResult> {
  const response = await request<McpToolSyncResult>(`/api/v1/mcp/servers/${serverId}/sync-tools`, {
    method: 'POST',
  })
  return response.data
}

export async function listMcpTools(params: {
  serverId?: number
  keyword?: string
  enabled?: number
  limit?: number
} = {}): Promise<McpToolSnapshot[]> {
  const searchParams = new URLSearchParams()

  if (typeof params.serverId === 'number') {
    searchParams.set('serverId', String(params.serverId))
  }
  if (params.keyword) {
    searchParams.set('keyword', params.keyword)
  }
  if (typeof params.enabled === 'number') {
    searchParams.set('enabled', String(params.enabled))
  }
  if (typeof params.limit === 'number') {
    searchParams.set('limit', String(params.limit))
  }

  const suffix = searchParams.toString() ? `?${searchParams.toString()}` : ''
  const response = await request<McpToolSnapshot[]>(`/api/v1/mcp/tools${suffix}`)
  return response.data
}

export async function enableMcpTool(toolId: number): Promise<McpToolSnapshot> {
  const response = await request<McpToolSnapshot>(`/api/v1/mcp/tools/${toolId}/enable`, {
    method: 'POST',
  })
  return response.data
}

export async function disableMcpTool(toolId: number): Promise<McpToolSnapshot> {
  const response = await request<McpToolSnapshot>(`/api/v1/mcp/tools/${toolId}/disable`, {
    method: 'POST',
  })
  return response.data
}
