<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { RequestError } from '@/api/http'
import {
  createMcpServer,
  disableMcpTool,
  enableMcpTool,
  listMcpServers,
  listMcpTools,
  syncMcpTools,
  testMcpConnection,
  updateMcpServer,
  type McpServer,
  type McpServerUpsertRequest,
  type McpToolSnapshot,
} from '@/api/mcp'

const mcpServers = ref<McpServer[]>([])
const mcpTools = ref<McpToolSnapshot[]>([])
const loadingServers = ref(false)
const loadingTools = ref(false)
const savingServer = ref(false)
const serverError = ref('')
const toolError = ref('')
const serverDialogVisible = ref(false)
const schemaDialogVisible = ref(false)
const editingServerId = ref<number | null>(null)
const schemaDialogTitle = ref('')
const schemaDialogContent = ref('')
const busyServerIds = ref<number[]>([])
const busyToolIds = ref<number[]>([])

const serverFilters = reactive({
  keyword: '',
  enabled: 'ALL',
  transportType: 'ALL',
})

const toolFilters = reactive({
  serverId: undefined as number | undefined,
  keyword: '',
  enabled: 'ALL',
})

const serverForm = reactive({
  name: '',
  description: '',
  transportType: 'STREAMABLE_HTTP' as 'STDIO' | 'STREAMABLE_HTTP',
  endpoint: '',
  command: '',
  argsText: '',
  envText: '{}',
  headersText: '{}',
  authType: 'NONE',
  authConfigText: '{}',
  enabled: true,
  riskLevel: 'MEDIUM',
})

const serverStats = computed(() => {
  return {
    total: mcpServers.value.length,
    healthy: mcpServers.value.filter((item) => item.healthStatus === 'HEALTHY').length,
    enabled: mcpServers.value.filter((item) => item.enabled).length,
    tools: mcpTools.value.length,
  }
})

const serverOptions = computed(() => {
  return mcpServers.value.map((item) => ({
    label: item.name,
    value: item.id,
  }))
})

const activeToolServerName = computed(() => {
  if (!toolFilters.serverId) {
    return '全部已同步工具'
  }

  return mcpServers.value.find((item) => item.id === toolFilters.serverId)?.name ?? `Server #${toolFilters.serverId}`
})

function resolveEnabledFlag(value: string) {
  if (value === '1') {
    return 1
  }
  if (value === '0') {
    return 0
  }
  return undefined
}

function formatTime(timestamp: number | null | undefined) {
  if (!timestamp) {
    return '未记录'
  }

  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(timestamp)
}

function resolveHealthTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'HEALTHY') {
    return 'success'
  }
  if (status === 'UNHEALTHY') {
    return 'danger'
  }
  if (status === 'UNKNOWN') {
    return 'warning'
  }
  return 'info'
}

function resolveEnabledTagType(enabled: boolean): 'success' | 'info' {
  return enabled ? 'success' : 'info'
}

function resolveRiskTagType(riskLevel: string): 'success' | 'warning' | 'danger' | 'info' {
  if (riskLevel === 'LOW') {
    return 'success'
  }
  if (riskLevel === 'HIGH') {
    return 'danger'
  }
  if (riskLevel === 'MEDIUM') {
    return 'warning'
  }
  return 'info'
}

function renderServerTarget(server: McpServer) {
  if (server.transportType === 'STDIO') {
    return [server.command, ...server.args].filter(Boolean).join(' ')
  }
  return server.endpoint || '未配置 endpoint'
}

function markBusy(listRef: typeof busyServerIds | typeof busyToolIds, targetId: number) {
  listRef.value = Array.from(new Set([...listRef.value, targetId]))
}

function releaseBusy(listRef: typeof busyServerIds | typeof busyToolIds, targetId: number) {
  listRef.value = listRef.value.filter((id) => id !== targetId)
}

function isServerBusy(serverId: number) {
  return busyServerIds.value.includes(serverId)
}

function isToolBusy(toolId: number) {
  return busyToolIds.value.includes(toolId)
}

async function loadServers(preserveToolFilter = true) {
  loadingServers.value = true
  serverError.value = ''

  try {
    mcpServers.value = await listMcpServers({
      keyword: serverFilters.keyword.trim() || undefined,
      enabled: resolveEnabledFlag(serverFilters.enabled),
      transportType:
        serverFilters.transportType === 'ALL' ? undefined : serverFilters.transportType,
      limit: 100,
    })

    if (
      preserveToolFilter &&
      toolFilters.serverId &&
      !mcpServers.value.some((item) => item.id === toolFilters.serverId)
    ) {
      toolFilters.serverId = undefined
    }
  } catch (error) {
    mcpServers.value = []
    serverError.value = error instanceof RequestError ? error.message : 'MCP Server 列表加载失败。'
  } finally {
    loadingServers.value = false
  }
}

async function loadTools() {
  loadingTools.value = true
  toolError.value = ''

  try {
    mcpTools.value = await listMcpTools({
      serverId: toolFilters.serverId,
      keyword: toolFilters.keyword.trim() || undefined,
      enabled: resolveEnabledFlag(toolFilters.enabled),
      limit: 300,
    })
  } catch (error) {
    mcpTools.value = []
    toolError.value = error instanceof RequestError ? error.message : '工具快照列表加载失败。'
  } finally {
    loadingTools.value = false
  }
}

function resetServerForm() {
  serverForm.name = ''
  serverForm.description = ''
  serverForm.transportType = 'STREAMABLE_HTTP'
  serverForm.endpoint = ''
  serverForm.command = ''
  serverForm.argsText = ''
  serverForm.envText = '{}'
  serverForm.headersText = '{}'
  serverForm.authType = 'NONE'
  serverForm.authConfigText = '{}'
  serverForm.enabled = true
  serverForm.riskLevel = 'MEDIUM'
}

function openCreateDialog() {
  editingServerId.value = null
  resetServerForm()
  serverDialogVisible.value = true
}

function openEditDialog(server: McpServer) {
  editingServerId.value = server.id
  serverForm.name = server.name
  serverForm.description = server.description ?? ''
  serverForm.transportType = server.transportType
  serverForm.endpoint = server.endpoint ?? ''
  serverForm.command = server.command ?? ''
  serverForm.argsText = server.args.join('\n')
  serverForm.envText = formatJson(server.env)
  serverForm.headersText = formatJson(server.headers)
  serverForm.authType = server.authType
  serverForm.authConfigText = formatJson(server.authConfig ?? {})
  serverForm.enabled = server.enabled
  serverForm.riskLevel = server.riskLevel
  serverDialogVisible.value = true
}

function buildServerPayload(): McpServerUpsertRequest {
  const args = serverForm.argsText
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean)

  return {
    name: serverForm.name.trim(),
    description: serverForm.description.trim() || undefined,
    transportType: serverForm.transportType,
    endpoint: serverForm.transportType === 'STREAMABLE_HTTP' ? serverForm.endpoint.trim() || undefined : undefined,
    command: serverForm.transportType === 'STDIO' ? serverForm.command.trim() || undefined : undefined,
    args,
    env: parseJsonObjectText('环境变量 JSON', serverForm.envText) as Record<string, string>,
    headers: parseJsonObjectText('请求头 JSON', serverForm.headersText) as Record<string, string>,
    authType: serverForm.authType,
    authConfig: parseJsonObjectText('认证配置 JSON', serverForm.authConfigText),
    enabled: serverForm.enabled,
    riskLevel: serverForm.riskLevel,
  }
}

function parseJsonObjectText(label: string, rawText: string) {
  const content = rawText.trim()
  if (!content) {
    return {}
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(content)
  } catch {
    throw new Error(`${label} 不是合法 JSON。`)
  }

  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error(`${label} 必须是 JSON 对象。`)
  }

  return parsed as Record<string, unknown>
}

async function handleSaveServer() {
  if (!serverForm.name.trim()) {
    ElMessage.error('请输入 MCP Server 名称。')
    return
  }

  if (serverForm.transportType === 'STDIO' && !serverForm.command.trim()) {
    ElMessage.error('STDIO 类型必须提供启动命令。')
    return
  }

  if (serverForm.transportType === 'STREAMABLE_HTTP' && !serverForm.endpoint.trim()) {
    ElMessage.error('STREAMABLE_HTTP 类型必须提供 endpoint。')
    return
  }

  savingServer.value = true

  try {
    const payload = buildServerPayload()
    const saved = editingServerId.value
      ? await updateMcpServer(editingServerId.value, payload)
      : await createMcpServer(payload)

    ElMessage.success(
      editingServerId.value
        ? `MCP Server“${saved.name}”已更新。`
        : `MCP Server“${saved.name}”已创建。`,
    )
    serverDialogVisible.value = false
    await loadServers(false)
  } catch (error) {
    const message =
      error instanceof Error ? error.message : 'MCP Server 保存失败，请稍后重试。'
    ElMessage.error(error instanceof RequestError ? error.message : message)
  } finally {
    savingServer.value = false
  }
}

async function handleConnectionTest(server: McpServer) {
  markBusy(busyServerIds, server.id)

  try {
    const result = await testMcpConnection(server.id)
    ElMessage.success(`连接成功，共探测到 ${result.toolCount} 个工具。`)
    await loadServers()
  } catch (error) {
    ElMessage.error(error instanceof RequestError ? error.message : 'MCP 连通性测试失败。')
    await loadServers()
  } finally {
    releaseBusy(busyServerIds, server.id)
  }
}

async function handleToolSync(server: McpServer) {
  markBusy(busyServerIds, server.id)

  try {
    const result = await syncMcpTools(server.id)
    toolFilters.serverId = server.id
    ElMessage.success(`工具同步完成，共写入 ${result.syncedCount} 个工具快照。`)
    await Promise.all([loadServers(), loadTools()])
  } catch (error) {
    ElMessage.error(error instanceof RequestError ? error.message : '工具同步失败。')
    await loadServers()
  } finally {
    releaseBusy(busyServerIds, server.id)
  }
}

async function handleToggleTool(tool: McpToolSnapshot) {
  markBusy(busyToolIds, tool.id)

  try {
    const updated = tool.enabled ? await disableMcpTool(tool.id) : await enableMcpTool(tool.id)
    mcpTools.value = mcpTools.value.map((item) => (item.id === updated.id ? updated : item))
    ElMessage.success(updated.enabled ? '工具已启用。' : '工具已禁用。')
  } catch (error) {
    ElMessage.error(error instanceof RequestError ? error.message : '工具状态更新失败。')
  } finally {
    releaseBusy(busyToolIds, tool.id)
  }
}

function focusToolsForServer(serverId: number) {
  toolFilters.serverId = serverId
  void loadTools()
}

function openSchemaDialog(tool: McpToolSnapshot) {
  schemaDialogTitle.value = `${tool.runtimeToolName} 输入 Schema`
  schemaDialogContent.value = formatJson(tool.inputSchemaJson ?? {})
  schemaDialogVisible.value = true
}

function formatJson(value: unknown) {
  return JSON.stringify(value ?? {}, null, 2)
}

onMounted(async () => {
  await Promise.all([loadServers(), loadTools()])
})
</script>

<template>
  <section class="mcp-layout">
    <el-card class="panel hero-card" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">MCP 管理</p>
            <h2>Model Context Protocol</h2>
          </div>
          <el-button type="primary" @click="openCreateDialog">新增 MCP Server</el-button>
        </div>
      </template>

      <p class="section-description">
        这一页优先落地 MCP 的“管理闭环”：录入 server、用 LangChain4j 官方 MCP client 做连通性测试、
        同步工具快照并控制工具启停。当前还没有把这些工具正式接进聊天主链路，所以这里看到的是
        “平台管理层”，不是“Agent 已经可调用工具”。
      </p>

      <div class="summary-grid">
        <div class="summary-card">
          <span>Server 总数</span>
          <strong>{{ serverStats.total }}</strong>
          <small>当前数据库里已登记的 MCP Server。</small>
        </div>
        <div class="summary-card">
          <span>健康 Server</span>
          <strong>{{ serverStats.healthy }}</strong>
          <small>最近一次连通性测试结果为 HEALTHY。</small>
        </div>
        <div class="summary-card">
          <span>已启用 Server</span>
          <strong>{{ serverStats.enabled }}</strong>
          <small>后续接入 tool provider 时会优先考虑这些 server。</small>
        </div>
        <div class="summary-card">
          <span>已同步工具</span>
          <strong>{{ serverStats.tools }}</strong>
          <small>来自 `mcp_tool_snapshot` 的当前快照数量。</small>
        </div>
      </div>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">Server 列表</p>
            <h2>MCP Servers</h2>
          </div>
          <el-button :loading="loadingServers" @click="loadServers()">刷新</el-button>
        </div>
      </template>

      <div class="filter-bar">
        <el-input v-model="serverFilters.keyword" clearable placeholder="按名称、描述或 endpoint 搜索" />
        <el-select v-model="serverFilters.transportType">
          <el-option label="全部传输类型" value="ALL" />
          <el-option label="STDIO" value="STDIO" />
          <el-option label="STREAMABLE_HTTP" value="STREAMABLE_HTTP" />
        </el-select>
        <el-select v-model="serverFilters.enabled">
          <el-option label="全部状态" value="ALL" />
          <el-option label="仅启用" value="1" />
          <el-option label="仅停用" value="0" />
        </el-select>
        <el-button type="primary" @click="loadServers(false)">筛选</el-button>
      </div>

      <el-alert v-if="serverError" :closable="false" show-icon title="MCP Server 列表加载失败" type="error">
        <template #default>{{ serverError }}</template>
      </el-alert>

      <el-table v-else :data="mcpServers" class="server-table" empty-text="暂无 MCP Server" v-loading="loadingServers">
        <el-table-column label="Server" min-width="220">
          <template #default="{ row }: { row: McpServer }">
            <div class="table-cell-stack">
              <strong>{{ row.name }}</strong>
              <span>{{ row.description || '未填写说明' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="传输 / 目标" min-width="280">
          <template #default="{ row }: { row: McpServer }">
            <div class="table-cell-stack">
              <el-tag effect="plain" round>{{ row.transportType }}</el-tag>
              <span>{{ renderServerTarget(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="180">
          <template #default="{ row }: { row: McpServer }">
            <div class="status-tag-row">
              <el-tag :type="resolveHealthTagType(row.healthStatus)">{{ row.healthStatus }}</el-tag>
              <el-tag :type="resolveEnabledTagType(row.enabled)">{{ row.enabled ? 'ENABLED' : 'DISABLED' }}</el-tag>
              <el-tag :type="resolveRiskTagType(row.riskLevel)">{{ row.riskLevel }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="工具 / 时间" min-width="220">
          <template #default="{ row }: { row: McpServer }">
            <div class="table-cell-stack">
              <span>工具快照：{{ row.toolCount }}</span>
              <span>最近连接：{{ formatTime(row.lastConnectedAt) }}</span>
              <span>最近同步：{{ formatTime(row.lastSyncAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="220">
          <template #default="{ row }: { row: McpServer }">
            <div class="action-stack">
              <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
              <el-button :loading="isServerBusy(row.id)" link type="primary" @click="handleConnectionTest(row)">
                测试连接
              </el-button>
              <el-button :loading="isServerBusy(row.id)" link type="primary" @click="handleToolSync(row)">
                同步工具
              </el-button>
              <el-button link type="primary" @click="focusToolsForServer(row.id)">查看工具</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">工具快照</p>
            <h2>{{ activeToolServerName }}</h2>
          </div>
          <el-button :loading="loadingTools" @click="loadTools">刷新</el-button>
        </div>
      </template>

      <div class="filter-bar tool-filter-bar">
        <el-select v-model="toolFilters.serverId" clearable placeholder="按 Server 查看工具">
          <el-option label="全部 Server" :value="undefined" />
          <el-option
            v-for="item in serverOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-input v-model="toolFilters.keyword" clearable placeholder="按 tool 名称或说明搜索" />
        <el-select v-model="toolFilters.enabled">
          <el-option label="全部状态" value="ALL" />
          <el-option label="仅启用" value="1" />
          <el-option label="仅停用" value="0" />
        </el-select>
        <el-button type="primary" @click="loadTools">筛选</el-button>
      </div>

      <el-alert v-if="toolError" :closable="false" show-icon title="工具快照加载失败" type="error">
        <template #default>{{ toolError }}</template>
      </el-alert>

      <el-table v-else :data="mcpTools" class="tool-table" empty-text="暂无工具快照" v-loading="loadingTools">
        <el-table-column label="工具" min-width="260">
          <template #default="{ row }: { row: McpToolSnapshot }">
            <div class="table-cell-stack">
              <strong>{{ row.runtimeToolName }}</strong>
              <span>origin：{{ row.originToolName }}</span>
              <span>{{ row.description || '无描述' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="180">
          <template #default="{ row }: { row: McpToolSnapshot }">
            <div class="table-cell-stack">
              <span>{{ row.serverName }}</span>
              <span>同步时间：{{ formatTime(row.syncedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="风险 / 状态" min-width="160">
          <template #default="{ row }: { row: McpToolSnapshot }">
            <div class="status-tag-row">
              <el-tag :type="resolveRiskTagType(row.riskLevel)">{{ row.riskLevel }}</el-tag>
              <el-tag :type="resolveEnabledTagType(row.enabled)">{{ row.enabled ? 'ENABLED' : 'DISABLED' }}</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column align="right" fixed="right" label="操作" width="220">
          <template #default="{ row }: { row: McpToolSnapshot }">
            <div class="action-stack">
              <el-button link type="primary" @click="openSchemaDialog(row)">查看 Schema</el-button>
              <el-button
                :loading="isToolBusy(row.id)"
                link
                type="primary"
                @click="handleToggleTool(row)"
              >
                {{ row.enabled ? '禁用' : '启用' }}
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>

  <el-dialog v-model="serverDialogVisible" :title="editingServerId ? '编辑 MCP Server' : '新增 MCP Server'" width="760">
    <el-form label-position="top">
      <div class="dialog-intro">
        <p>
          当前页面会直接使用 LangChain4j 官方 MCP client 做连接和列工具，所以这里填写的 transport、endpoint、
          command、headers 会直接影响后端真实连通性，不是展示字段。
        </p>
      </div>

      <div class="form-grid">
        <el-form-item label="Server 名称">
          <el-input v-model="serverForm.name" maxlength="128" placeholder="例如：weather-mcp" />
          <div class="form-item-hint">用于平台内唯一识别，也会参与 runtime tool name 生成。</div>
        </el-form-item>
        <el-form-item label="传输类型">
          <el-select v-model="serverForm.transportType">
            <el-option label="STREAMABLE_HTTP" value="STREAMABLE_HTTP" />
            <el-option label="STDIO" value="STDIO" />
          </el-select>
          <div class="form-item-hint">优先按 LangChain4j 官方支持的传输类型接入。</div>
        </el-form-item>
      </div>

      <el-form-item label="说明">
        <el-input
          v-model="serverForm.description"
          :rows="3"
          maxlength="512"
          placeholder="说明这个 MCP Server 主要提供什么能力。"
          resize="none"
          show-word-limit
          type="textarea"
        />
      </el-form-item>

      <template v-if="serverForm.transportType === 'STREAMABLE_HTTP'">
        <el-form-item label="Endpoint">
          <el-input v-model="serverForm.endpoint" placeholder="例如：https://example.com/mcp" />
          <div class="form-item-hint">
            对应 LangChain4j `StreamableHttpMcpTransport.builder().url(...)` 的 URL。
          </div>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="认证类型">
            <el-select v-model="serverForm.authType">
              <el-option label="NONE" value="NONE" />
              <el-option label="BEARER" value="BEARER" />
              <el-option label="BASIC" value="BASIC" />
              <el-option label="CUSTOM" value="CUSTOM" />
            </el-select>
            <div class="form-item-hint">BEARER/BASIC/CUSTOM 会由后端按配置生成请求头。</div>
          </el-form-item>
          <el-form-item label="风险等级">
            <el-select v-model="serverForm.riskLevel">
              <el-option label="LOW" value="LOW" />
              <el-option label="MEDIUM" value="MEDIUM" />
              <el-option label="HIGH" value="HIGH" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="请求头 JSON">
          <el-input
            v-model="serverForm.headersText"
            :rows="4"
            placeholder='例如：{"x-api-key":"demo"}'
            resize="none"
            type="textarea"
          />
          <div class="form-item-hint">必须是 JSON 对象。适合放固定 header。</div>
        </el-form-item>
        <el-form-item label="认证配置 JSON">
          <el-input
            v-model="serverForm.authConfigText"
            :rows="4"
            placeholder='例如：{"token":"demo-token"}'
            resize="none"
            type="textarea"
          />
          <div class="form-item-hint">
            `BEARER` 期望 `{"token":"..."}`；`BASIC` 期望 `{"username":"...","password":"..."}`；`CUSTOM`
            可提供 `headerName/headerValue`。
          </div>
        </el-form-item>
      </template>

      <template v-else>
        <div class="form-grid">
          <el-form-item label="启动命令">
            <el-input v-model="serverForm.command" placeholder="例如：npx" />
            <div class="form-item-hint">对应 LangChain4j `StdioMcpTransport.builder().command(...)` 的首个命令。</div>
          </el-form-item>
          <el-form-item label="风险等级">
            <el-select v-model="serverForm.riskLevel">
              <el-option label="LOW" value="LOW" />
              <el-option label="MEDIUM" value="MEDIUM" />
              <el-option label="HIGH" value="HIGH" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="参数列表">
          <el-input
            v-model="serverForm.argsText"
            :rows="5"
            placeholder="-y&#10;@modelcontextprotocol/server-sequential-thinking"
            resize="none"
            type="textarea"
          />
          <div class="form-item-hint">每行一个参数，后端会按顺序拼成命令数组。</div>
        </el-form-item>
        <el-form-item label="环境变量 JSON">
          <el-input
            v-model="serverForm.envText"
            :rows="4"
            placeholder='例如：{"NODE_ENV":"production"}'
            resize="none"
            type="textarea"
          />
          <div class="form-item-hint">必须是 JSON 对象，对应 STDIO 子进程环境变量。</div>
        </el-form-item>
      </template>

      <el-form-item label="是否启用">
        <el-switch v-model="serverForm.enabled" active-text="启用" inactive-text="停用" />
        <div class="form-item-hint">停用后仍保留配置和工具快照，但后续 runtime 接入时默认不参与装配。</div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="serverDialogVisible = false">取消</el-button>
      <el-button :loading="savingServer" type="primary" @click="handleSaveServer">
        {{ editingServerId ? '保存修改' : '创建 Server' }}
      </el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="schemaDialogVisible" :title="schemaDialogTitle" width="760">
    <pre class="schema-block">{{ schemaDialogContent }}</pre>
  </el-dialog>
</template>

<style scoped>
.mcp-layout {
  display: grid;
  gap: 20px;
}

.panel {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
}

.hero-card {
  background:
    linear-gradient(135deg, rgba(236, 248, 255, 0.96), rgba(255, 247, 235, 0.9)),
    rgba(255, 255, 255, 0.88);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.section-head h2 {
  margin: 0;
  font-size: 24px;
}

.section-kicker {
  margin: 0 0 6px;
  font-size: 12px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #8b5e21;
}

.section-description {
  margin: 0;
  color: #425466;
  line-height: 1.8;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 18px;
}

.summary-card {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.78);
}

.summary-card span,
.summary-card small {
  color: #64748b;
}

.summary-card strong {
  color: #0f172a;
  font-size: 24px;
}

.filter-bar {
  display: grid;
  grid-template-columns: minmax(200px, 1.4fr) repeat(2, minmax(180px, 1fr)) auto;
  gap: 12px;
  margin-bottom: 16px;
}

.tool-filter-bar {
  grid-template-columns: minmax(220px, 1fr) minmax(200px, 1.3fr) minmax(180px, 1fr) auto;
}

.table-cell-stack {
  display: grid;
  gap: 4px;
}

.table-cell-stack strong,
.table-cell-stack span {
  overflow-wrap: anywhere;
}

.table-cell-stack span {
  color: #64748b;
  font-size: 13px;
}

.status-tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.action-stack {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.dialog-intro {
  padding: 14px 16px;
  margin-bottom: 16px;
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.92);
  color: #425466;
  line-height: 1.75;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.form-item-hint {
  margin-top: 8px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.schema-block {
  margin: 0;
  padding: 16px;
  overflow: auto;
  border-radius: 16px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 1200px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .section-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .summary-grid,
  .filter-bar,
  .tool-filter-bar,
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
