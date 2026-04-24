<script setup lang="ts">
import { Delete, MoreFilled, Plus, Setting } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import {
  clearConversationMemory,
  deleteConversation,
  getConversationSettings,
  listConversationMessages,
  listConversations,
  type Conversation,
  type ConversationMessage,
  updateConversationSettings,
} from '@/api/conversation'
import {
  approveToolConfirmation,
  listPendingToolConfirmations,
  rejectToolConfirmation,
  submitChat,
  sendChatSync,
  type ChatSendRequest,
  type ChatRequestAccepted,
  type StreamCompletedEvent,
  type StreamErrorEvent,
  type StreamProgressEvent,
  type StreamTokenEvent,
  type ToolConfirmationPending,
} from '@/api/chat'
import { RequestError } from '@/api/http'
import { listKnowledgeBases, type KnowledgeBase } from '@/api/knowledge'
import { listMcpServers, type McpServer } from '@/api/mcp'
import { getTraceDetail, type TraceEvent } from '@/api/trace'
import { renderMarkdownToHtml } from '@/utils/markdown'

const DEFAULT_MEMORY_ENABLED = true

function createIdOverrideState() {
  return {
    enabled: false,
    ids: [] as number[],
  }
}

function createDraftModeState() {
  return {
    enableRag: false,
    enableAgent: false,
    memoryEnabled: DEFAULT_MEMORY_ENABLED,
  }
}

const chatInput = ref('')
const chatLoading = ref(false)
const chatError = ref('')
const activeChatRequestId = ref('')
const modeState = ref(createDraftModeState())
const conversations = ref<Conversation[]>([])
const messages = ref<ConversationMessage[]>([])
const traces = ref<TraceEvent[]>([])
const activeConversationId = ref<number | null>(null)
const traceError = ref('')
const thinkingRenderNow = ref(Date.now())
const availableKnowledgeBases = ref<KnowledgeBase[]>([])
const knowledgeLoading = ref(false)
const knowledgeError = ref('')
const selectedKnowledgeBaseIds = ref<number[]>([])
const availableMcpServers = ref<McpServer[]>([])
const mcpLoading = ref(false)
const mcpError = ref('')
const selectedMcpServerIds = ref<number[]>([])
const requestKnowledgeBaseOverride = ref(createIdOverrideState())
const requestMcpServerOverride = ref(createIdOverrideState())
const memoryClearing = ref(false)
const traceFilter = ref<'ALL' | 'PROMPT' | 'RAG' | 'MODEL' | 'TOOL' | 'FAILED'>('ALL')
const traceExpandedIds = ref<number[]>([])
const settingsDrawerVisible = ref(false)
const traceDialogVisible = ref(false)
const traceDialogLoading = ref(false)
const chatHistoryRef = ref<HTMLElement | null>(null)
const chatInputRef = ref<{ focus: () => void } | null>(null)
const chatAutoScrollPinned = ref(true)
const showScrollToLatest = ref(false)

let activeChatSource: EventSource | null = null
let activeTraceSource: EventSource | null = null
let thinkingTicker: number | null = null
let chatScrollFrame: number | null = null
let conversationTitleRefreshTimer: number | null = null
const assistantMarkdownRenderTimers = new Map<number, number>()
const assistantMarkdownLastRenderAt = new Map<number, number>()

const selectedKnowledgeBases = computed(() => {
  return availableKnowledgeBases.value.filter((item) =>
    selectedKnowledgeBaseIds.value.includes(item.id),
  )
})

const selectedMcpServers = computed(() => {
  return availableMcpServers.value.filter((item) =>
    selectedMcpServerIds.value.includes(item.id),
  )
})

const knowledgeBaseNameMap = computed(() => {
  return new Map(availableKnowledgeBases.value.map((item) => [item.id, item.name]))
})

const mcpServerNameMap = computed(() => {
  return new Map(availableMcpServers.value.map((item) => [item.id, item.name]))
})

const effectiveKnowledgeBaseIds = computed(() => {
  if (!modeState.value.enableRag) {
    return []
  }
  return requestKnowledgeBaseOverride.value.enabled
    ? requestKnowledgeBaseOverride.value.ids
    : selectedKnowledgeBaseIds.value
})

const effectiveMcpServerIds = computed(() => {
  if (!modeState.value.enableAgent) {
    return []
  }
  return requestMcpServerOverride.value.enabled
    ? requestMcpServerOverride.value.ids
    : selectedMcpServerIds.value
})

const filteredTraces = computed(() => {
  return traces.value.filter((trace) => matchesTraceFilter(trace))
})

const currentRoundSummary = computed(() => {
  const ragStarted = findLastTrace('RAG_RETRIEVAL_STARTED')
  const ragSelected = findLastTrace('RAG_SEGMENTS_SELECTED')
  const ragFinished = findLastTrace('RAG_RETRIEVAL_FINISHED')
  const modelStarted = findLastTrace('MODEL_REQUEST_STARTED')
  const finalCompleted = findLastTrace('FINAL_RESPONSE_COMPLETED')
  const promptResolved = findLastTrace('PROMPT_ASSEMBLY_RESOLVED')
  const promptPayload = promptResolved ? parseTracePayload(promptResolved.eventPayloadJson) : null

  const knowledgeBaseIds =
    readTraceNumericArray(ragStarted, 'knowledgeBaseIds') ??
    readTraceNumericArray(ragFinished, 'knowledgeBaseIds') ??
    (modeState.value.enableRag ? [...effectiveKnowledgeBaseIds.value] : [])
  const retrievedCount =
    readTraceNumber(ragSelected, 'count') ?? readTraceNumber(ragFinished, 'count') ?? 0
  const ragSnippetCount = readTraceNumber(modelStarted, 'ragSnippetCount') ?? retrievedCount
  const promptKeys =
    promptPayload && Array.isArray(promptPayload.promptKeys)
      ? promptPayload.promptKeys.map((item) => String(item))
      : []
  const promptBlocks = readTraceNumber(promptResolved, 'blockCount') ?? promptKeys.length
  const promptSource =
    promptPayload && typeof promptPayload.promptSource === 'string'
      ? promptPayload.promptSource
      : '未装配'
  const promptSummaryText = promptKeys.length > 0 ? promptKeys.join(' + ') : '未装配'

  let ragStatus = '未开启'
  if (modeState.value.enableRag || ragStarted || ragSelected || ragFinished || modelStarted) {
    if (ragSnippetCount > 0) {
      ragStatus = '已注入模型'
    } else if (ragStarted || ragSelected || ragFinished) {
      ragStatus = '已检索未命中'
    } else {
      ragStatus = '等待发送'
    }
  }

  let requestStatus = '等待发送'
  if (finalCompleted) {
    requestStatus = '已完成'
  } else if (traces.value.length > 0) {
    requestStatus = '处理中'
  }

  return {
    requestId:
      (traces.value.length > 0 ? traces.value[traces.value.length - 1]?.requestId : null) ??
      activeChatRequestId.value ??
      '',
    knowledgeBaseText:
      knowledgeBaseIds.length > 0 ? formatKnowledgeBaseList(knowledgeBaseIds) : '未选择知识库',
    retrievedCount,
    ragStatus,
    requestStatus,
    promptSummaryText,
    promptBlocks,
    promptSource,
  }
})

const activeModeLabel = computed(() => {
  return formatModeLabel(modeState.value.enableRag, modeState.value.enableAgent)
})

const chatStarted = computed(() => messages.value.length > 0)

const messageRenderSignature = computed(() => {
  return messages.value
    .map((item) => `${item.id}:${item.roleCode}:${item.content?.length ?? 0}:${item.uiState ?? 'done'}`)
    .join('|')
})

const stageFactItems = computed(() => [
  { label: activeModeLabel.value, value: modeState.value.memoryEnabled ? 'Memory 开启' : 'Memory 关闭' },
  {
    label: `${effectiveKnowledgeBaseIds.value.length} 个知识库`,
    value: requestKnowledgeBaseOverride.value.enabled ? '本轮覆盖' : '会话默认',
  },
  {
    label: `${effectiveMcpServerIds.value.length} 个 MCP`,
    value: requestMcpServerOverride.value.enabled ? '本轮覆盖' : '会话默认',
  },
])

async function loadConversations(preserveActive = true) {
  const list = sortConversations(await listConversations())
  conversations.value = list

  if (preserveActive && activeConversationId.value) {
    const exists = list.some((item) => item.id === activeConversationId.value)
    if (exists) {
      return
    }
  }

  if (activeConversationId.value) {
    resetDraftConversation(false)
  }
}

async function loadKnowledgeOptions() {
  knowledgeLoading.value = true
  knowledgeError.value = ''

  try {
    const list = await listKnowledgeBases({ limit: 100 })
    availableKnowledgeBases.value = list
    selectedKnowledgeBaseIds.value = selectedKnowledgeBaseIds.value.filter((id) =>
      list.some((item) => item.id === id),
    )
    requestKnowledgeBaseOverride.value.ids = requestKnowledgeBaseOverride.value.ids.filter((id) =>
      list.some((item) => item.id === id),
    )
  } catch (error) {
    availableKnowledgeBases.value = []
    selectedKnowledgeBaseIds.value = []
    requestKnowledgeBaseOverride.value.ids = []
    knowledgeError.value =
      error instanceof RequestError ? error.message : '知识库列表加载失败，请稍后重试。'
  } finally {
    knowledgeLoading.value = false
  }
}

async function loadMcpServerOptions() {
  mcpLoading.value = true
  mcpError.value = ''

  try {
    const list = await listMcpServers({ limit: 100 })
    availableMcpServers.value = list
    selectedMcpServerIds.value = selectedMcpServerIds.value.filter((id) =>
      list.some((item) => item.id === id),
    )
    requestMcpServerOverride.value.ids = requestMcpServerOverride.value.ids.filter((id) =>
      list.some((item) => item.id === id),
    )
  } catch (error) {
    availableMcpServers.value = []
    selectedMcpServerIds.value = []
    requestMcpServerOverride.value.ids = []
    mcpError.value =
      error instanceof RequestError ? error.message : 'MCP Server 列表加载失败，请稍后重试。'
  } finally {
    mcpLoading.value = false
  }
}

async function createNewConversation() {
  resetDraftConversation(true)
  await focusChatInput()
}

async function focusChatInput() {
  await nextTick()
  chatInputRef.value?.focus()
}

function isChatHistoryNearBottom() {
  const element = chatHistoryRef.value
  if (!element) {
    return true
  }

  return element.scrollHeight - element.scrollTop - element.clientHeight < 80
}

function syncChatHistoryScrollState() {
  const pinned = isChatHistoryNearBottom()
  chatAutoScrollPinned.value = pinned
  showScrollToLatest.value = !pinned && messages.value.length > 0
}

function scrollChatHistoryToBottom(smooth = false) {
  const element = chatHistoryRef.value
  if (!element) {
    return
  }

  if (chatScrollFrame !== null) {
    window.cancelAnimationFrame(chatScrollFrame)
  }

  chatScrollFrame = window.requestAnimationFrame(() => {
    element.scrollTo({
      top: element.scrollHeight,
      behavior: smooth ? 'smooth' : 'auto',
    })
    chatScrollFrame = null
    window.requestAnimationFrame(() => syncChatHistoryScrollState())
  })
}

function keepChatHistoryPinned(force = false) {
  if (force || chatAutoScrollPinned.value) {
    showScrollToLatest.value = false
    scrollChatHistoryToBottom(force)
    return
  }

  showScrollToLatest.value = true
}

function handleChatHistoryScroll() {
  syncChatHistoryScrollState()
}

async function handleConversationCommand(command: string, conversation: Conversation) {
  if (command === 'delete') {
    await handleDeleteConversation(conversation)
    return
  }

  if (command === 'copy-id') {
    try {
      await navigator.clipboard.writeText(String(conversation.id))
      ElMessage.success(`会话 #${conversation.id} 已复制。`)
    } catch {
      ElMessage.error('复制会话 ID 失败。')
    }
    return
  }

  await selectConversation(conversation.id)

  if (command === 'settings') {
    settingsDrawerVisible.value = true
  }
}

function handleConversationDropdownCommand(
  conversation: Conversation,
  command: string | number | Record<string, unknown>,
) {
  void handleConversationCommand(String(command), conversation)
}

async function handleDeleteConversation(conversation: Conversation) {
  if (chatLoading.value && activeConversationId.value === conversation.id) {
    ElMessage.warning('当前会话仍在生成中，请等待本轮完成后再删除。')
    return
  }

  try {
    await ElMessageBox.confirm(
      `你将删除“${conversation.title}”。删除后会同步清理该会话的聊天记录、Trace 与 Memory 数据，当前实现为物理删除，操作后无法恢复。`,
      '删除会话',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        customClass: 'conversation-delete-dialog',
        confirmButtonClass: 'conversation-delete-dialog__confirm',
        cancelButtonClass: 'conversation-delete-dialog__cancel',
      },
    )
  } catch {
    return
  }

  try {
    await deleteConversation(conversation.id)

    if (activeConversationId.value === conversation.id) {
      resetDraftConversation(true)
    }

    await loadConversations(false)
    await focusChatInput()
    ElMessage.success('会话已删除。')
  } catch (error) {
    chatError.value = error instanceof RequestError ? error.message : '删除会话失败。'
  }
}

async function selectConversation(conversationId: number) {
  activeConversationId.value = conversationId
  activeChatRequestId.value = ''
  traces.value = []
  traceError.value = ''
  closeStreams()
  resetAssistantMarkdownRenderState()
  resetRequestOverrides()

  const [records, conversation, pendingConfirmations] = await Promise.all([
    listConversationMessages(conversationId),
    getConversationSettings(conversationId),
    listPendingToolConfirmations(conversationId),
  ])
  applyConversationSettings(conversation)
  updateConversationCache(conversation)
  messages.value = normalizeMessages(records, pendingConfirmations)
  await focusChatInput()
  keepChatHistoryPinned(true)
}

function resetDraftConversation(clearComposer: boolean) {
  activeConversationId.value = null
  activeChatRequestId.value = ''
  traces.value = []
  traceError.value = ''
  closeStreams()
  resetAssistantMarkdownRenderState()
  messages.value = []
  modeState.value = createDraftModeState()
  selectedKnowledgeBaseIds.value = []
  selectedMcpServerIds.value = []
  resetRequestOverrides()
  showScrollToLatest.value = false
  chatAutoScrollPinned.value = true

  if (clearComposer) {
    chatInput.value = ''
    chatError.value = ''
  }
}

function handleComposerEnter() {
  if (chatLoading.value) {
    return
  }

  void handleSend()
}

function canOpenTraceForMessage(message: ConversationMessage) {
  return message.roleCode === 'ASSISTANT' && !!message.requestId && message.uiState !== 'thinking'
}

async function openTraceDialogForMessage(message: ConversationMessage) {
  const requestId = message.requestId
  if (!requestId) {
    return
  }

  if (requestId !== activeChatRequestId.value || traces.value.length === 0) {
    traceDialogLoading.value = true
    traceError.value = ''
    try {
      const detail = await getTraceDetail(requestId)
      traces.value = detail.events
      activeChatRequestId.value = requestId
      applyDefaultTraceExpansion()
    } catch (error) {
      traceError.value = error instanceof RequestError ? error.message : 'Trace 详情加载失败。'
    } finally {
      traceDialogLoading.value = false
    }
  }

  traceDialogVisible.value = true
}

async function handleSendSync() {
  const message = chatInput.value.trim()
  if (!message || chatLoading.value || !validateBeforeSend()) {
    return
  }

  chatLoading.value = true
  chatError.value = ''
  traceError.value = ''
  const shouldRefreshGeneratedTitle = activeConversationId.value === null

  try {
    await persistActiveConversationSettings()
    const answer = await sendChatSync(buildChatRequest(message))
    activeChatRequestId.value = answer.requestId ?? ''
    chatInput.value = ''
    await refreshAfterRound(answer.conversationId, answer.requestId ?? null)
    if (shouldRefreshGeneratedTitle && answer.conversationId) {
      scheduleGeneratedTitleRefresh(answer.conversationId)
    }
  } catch (error) {
    chatError.value = error instanceof RequestError ? error.message : '同步聊天调用失败。'
  } finally {
    chatLoading.value = false
    await focusChatInput()
  }
}

async function handleSend() {
  const message = chatInput.value.trim()
  if (!message || chatLoading.value || !validateBeforeSend()) {
    return
  }

  chatLoading.value = true
  chatError.value = ''
  traceError.value = ''
  const shouldRefreshGeneratedTitle = activeConversationId.value === null
  let optimisticUserMessageId: number | null = null
  let optimisticAssistantMessageId: number | null = null

  try {
    await persistActiveConversationSettings()
    traces.value = []
    closeStreams()

    optimisticUserMessageId = -Date.now()
    optimisticAssistantMessageId = -(Date.now() + 1)
    const currentConversationId = activeConversationId.value ?? 0

    messages.value = [
      ...messages.value,
      {
        id: optimisticUserMessageId,
        conversationId: currentConversationId,
        roleCode: 'USER',
        messageType: 'TEXT',
        content: message,
        requestId: null,
        finishReason: null,
        createdAt: Date.now(),
      },
      {
        id: optimisticAssistantMessageId,
        conversationId: currentConversationId,
        roleCode: 'ASSISTANT',
        messageType: 'TEXT',
        content: '',
        requestId: null,
        finishReason: null,
        createdAt: Date.now(),
        uiState: 'thinking',
        thinkingStartedAt: Date.now(),
        progressMessage: '模型正在思考，请稍候…',
      },
    ]
    keepChatHistoryPinned(true)

    const accepted = await submitChat(buildChatRequest(message))
    activeChatRequestId.value = accepted.requestId
    if (accepted.conversationId) {
      activeConversationId.value = accepted.conversationId
    }
    chatInput.value = ''
    bindOptimisticMessages(optimisticUserMessageId, optimisticAssistantMessageId, accepted)
    const chatStreamPromise = openChatStream(accepted.requestId, optimisticAssistantMessageId)
    window.setTimeout(() => {
      openTraceStream(accepted.requestId)
    }, 0)
    const streamResult = await chatStreamPromise
    if (streamResult.finishReason !== 'tool_confirmation_required') {
      await refreshAfterRound(accepted.conversationId, accepted.requestId)
    }
    if (
      streamResult.finishReason !== 'tool_confirmation_required' &&
      shouldRefreshGeneratedTitle &&
      accepted.conversationId
    ) {
      scheduleGeneratedTitleRefresh(accepted.conversationId)
    }
  } catch (error) {
    removeOptimisticMessages(
      [optimisticUserMessageId, optimisticAssistantMessageId].filter(
        (messageId): messageId is number => messageId !== null,
      ),
    )
    chatError.value = error instanceof RequestError ? error.message : '聊天请求提交失败。'
  } finally {
    chatLoading.value = false
    await focusChatInput()
  }
}

function validateBeforeSend() {
  if (
    modeState.value.enableRag &&
    effectiveKnowledgeBaseIds.value.length === 0 &&
    !requestKnowledgeBaseOverride.value.enabled
  ) {
    chatError.value = '已开启 RAG，请至少选择一个知识库后再发送。'
    return false
  }

  return true
}

function buildChatRequest(message: string): ChatSendRequest {
  const isDraftConversation = !activeConversationId.value
  const request: ChatSendRequest = {
    conversationId: activeConversationId.value ?? undefined,
    message,
    enableRag: modeState.value.enableRag,
    enableAgent: modeState.value.enableAgent,
  }

  if (isDraftConversation) {
    request.memoryEnabled = modeState.value.memoryEnabled
  }

  if (requestKnowledgeBaseOverride.value.enabled) {
    request.knowledgeBaseIds = [...requestKnowledgeBaseOverride.value.ids]
  } else if (isDraftConversation) {
    request.knowledgeBaseIds = [...selectedKnowledgeBaseIds.value]
  }

  if (requestMcpServerOverride.value.enabled) {
    request.mcpServerIds = [...requestMcpServerOverride.value.ids]
  } else if (isDraftConversation) {
    request.mcpServerIds = [...selectedMcpServerIds.value]
  }

  return request
}

function openChatStream(
  streamRequestId: string,
  assistantMessageId: number,
): Promise<StreamCompletedEvent> {
  return new Promise((resolve, reject) => {
    activeChatSource = new EventSource(`/api/v1/chat/stream/${streamRequestId}`)

    activeChatSource.addEventListener('progress', (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as StreamProgressEvent
      updateAssistantProgress(assistantMessageId, streamRequestId, payload)
    })

    activeChatSource.addEventListener('token', (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as StreamTokenEvent
      appendAssistantChunk(assistantMessageId, streamRequestId, payload.content)
    })

    activeChatSource.addEventListener('message_end', (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as StreamCompletedEvent
      completeAssistantMessage(assistantMessageId, streamRequestId, payload)
      activeChatSource?.close()
      activeChatSource = null
      resolve(payload)
    })

    activeChatSource.addEventListener('error', (event) => {
      if ((event as MessageEvent).data) {
        const payload = JSON.parse((event as MessageEvent).data) as StreamErrorEvent
        chatError.value = payload.message
      } else {
        chatError.value = '流式连接已中断。'
      }
      markAssistantMessageError(assistantMessageId, streamRequestId)
      activeChatSource?.close()
      activeChatSource = null
      reject(new Error(chatError.value))
    })
  })
}

function openTraceStream(streamRequestId: string) {
  activeTraceSource = new EventSource(`/api/v1/traces/stream/${streamRequestId}`)

  activeTraceSource.addEventListener('trace', (event) => {
    const payload = JSON.parse((event as MessageEvent).data) as TraceEvent
    traces.value = [...traces.value, payload]
  })

  activeTraceSource.addEventListener('error', () => {
    activeTraceSource?.close()
    activeTraceSource = null
  })
}

function bindOptimisticMessages(
  userMessageId: number,
  assistantMessageId: number,
  accepted: { requestId: string; conversationId: number | null },
) {
  const conversationId = accepted.conversationId ?? activeConversationId.value ?? 0
  const userMessage = messages.value.find((item) => item.id === userMessageId)
  const assistantMessage = messages.value.find((item) => item.id === assistantMessageId)

  if (userMessage) {
    userMessage.requestId = accepted.requestId
    userMessage.conversationId = conversationId
  }

  if (assistantMessage) {
    assistantMessage.requestId = accepted.requestId
    assistantMessage.conversationId = conversationId
  }
}

function appendAssistantChunk(assistantMessageId: number, streamRequestId: string, content: string) {
  const existing = messages.value.find((item) => item.id === assistantMessageId)
  if (existing) {
    existing.requestId = streamRequestId
    existing.content = `${existing.content ?? ''}${content}`
    existing.uiState = 'streaming'
    existing.progressMessage = undefined
    existing.pendingConfirmation = undefined
    scheduleAssistantMarkdownRender(existing)
    return
  }

  const assistantMessage: ConversationMessage = {
    id: -Date.now(),
    conversationId: activeConversationId.value ?? 0,
    roleCode: 'ASSISTANT',
    messageType: 'TEXT',
    content,
    requestId: streamRequestId,
    finishReason: null,
    createdAt: Date.now(),
    uiState: 'streaming',
    pendingConfirmation: undefined,
  }

  messages.value = [
    ...messages.value,
    assistantMessage,
  ]
  scheduleAssistantMarkdownRender(assistantMessage)
}

function completeAssistantMessage(
  assistantMessageId: number,
  streamRequestId: string,
  payload: StreamCompletedEvent,
) {
  const target = messages.value.find((item) => item.id === assistantMessageId)
  if (target) {
    target.requestId = streamRequestId
    target.finishReason = payload.finishReason
    target.progressMessage = undefined

    if (payload.finishReason === 'tool_confirmation_required' && payload.pendingConfirmation) {
      target.content = ''
      target.uiState = 'pending_confirmation'
      target.pendingConfirmation = payload.pendingConfirmation
      target.renderedContentHtml = ''
      target.renderCacheKey = ''
      return
    }

    target.content = payload.answer
    target.uiState = 'done'
    target.pendingConfirmation = undefined
    renderAssistantMarkdownImmediately(target)
  }
}

function markAssistantMessageError(assistantMessageId: number, streamRequestId: string) {
  const target = messages.value.find((item) => item.id === assistantMessageId)
  if (target && !target.content) {
    target.requestId = streamRequestId
    target.content = '请求失败。'
    target.uiState = 'error'
    target.pendingConfirmation = undefined
    renderAssistantMarkdownImmediately(target)
  }
}

function updateAssistantProgress(
  assistantMessageId: number,
  streamRequestId: string,
  payload: StreamProgressEvent,
) {
  const target = messages.value.find((item) => item.id === assistantMessageId)
  if (!target) {
    return
  }
  if (target.content || target.uiState === 'streaming' || target.uiState === 'done') {
    return
  }
  target.requestId = streamRequestId
  target.uiState = 'thinking'
  target.progressMessage = payload.message
  target.pendingConfirmation = undefined
  if (!target.thinkingStartedAt) {
    target.thinkingStartedAt = Date.now() - payload.elapsedMillis
  }
}

function prepareAssistantMessageForContinuation(
  message: ConversationMessage,
  accepted: ChatRequestAccepted,
  progressMessage: string,
) {
  message.requestId = accepted.requestId
  message.finishReason = null
  message.content = ''
  message.uiState = 'thinking'
  message.progressMessage = progressMessage
  message.pendingConfirmation = undefined
  message.thinkingStartedAt = Date.now()
  message.renderedContentHtml = ''
  message.renderCacheKey = ''
}

async function handleToolConfirmation(
  message: ConversationMessage,
  decision: 'approve' | 'reject',
) {
  const pendingConfirmation = message.pendingConfirmation
  if (!pendingConfirmation || chatLoading.value) {
    return
  }

  chatLoading.value = true
  chatError.value = ''

  try {
    closeStreams()
    const accepted =
      decision === 'approve'
        ? await approveToolConfirmation(pendingConfirmation.id)
        : await rejectToolConfirmation(pendingConfirmation.id)
    activeChatRequestId.value = accepted.requestId
    prepareAssistantMessageForContinuation(
      message,
      accepted,
      decision === 'approve'
        ? '已确认继续执行工具，正在生成本轮回复…'
        : '已拒绝工具调用，正在生成本轮回复…',
    )
    const chatStreamPromise = openChatStream(accepted.requestId, message.id)
    window.setTimeout(() => {
      openTraceStream(accepted.requestId)
    }, 0)
    const streamResult = await chatStreamPromise
    if (streamResult.finishReason !== 'tool_confirmation_required') {
      await refreshAfterRound(accepted.conversationId, accepted.requestId)
    }
  } catch (error) {
    chatError.value = error instanceof RequestError ? error.message : '工具确认续跑失败。'
    if (message.uiState !== 'error') {
      message.uiState = 'pending_confirmation'
      message.pendingConfirmation = pendingConfirmation
      message.progressMessage = undefined
    }
  } finally {
    chatLoading.value = false
    await focusChatInput()
  }
}

async function handleApproveToolConfirmation(message: ConversationMessage) {
  await handleToolConfirmation(message, 'approve')
}

async function handleRejectToolConfirmation(message: ConversationMessage) {
  await handleToolConfirmation(message, 'reject')
}

function formatPendingConfirmationLabel(pendingConfirmation?: ToolConfirmationPending) {
  if (!pendingConfirmation) {
    return '高风险工具'
  }
  return pendingConfirmation.toolTitle || pendingConfirmation.toolName
}

function clearAssistantMarkdownRenderTimer(messageId: number) {
  const timer = assistantMarkdownRenderTimers.get(messageId)
  if (timer !== undefined) {
    window.clearTimeout(timer)
    assistantMarkdownRenderTimers.delete(messageId)
  }
}

function resetAssistantMarkdownRenderState() {
  assistantMarkdownRenderTimers.forEach((timer) => window.clearTimeout(timer))
  assistantMarkdownRenderTimers.clear()
  assistantMarkdownLastRenderAt.clear()
}

function renderAssistantMarkdown(message: ConversationMessage) {
  if (message.roleCode !== 'ASSISTANT') {
    return
  }

  const latestMessage = messages.value.find((item) => item.id === message.id) ?? message
  if (latestMessage.roleCode !== 'ASSISTANT') {
    return
  }

  const markdown = latestMessage.content ?? ''
  if (latestMessage.renderCacheKey === markdown) {
    return
  }

  latestMessage.renderedContentHtml = markdown ? renderMarkdownToHtml(markdown) : ''
  latestMessage.renderCacheKey = markdown
  assistantMarkdownLastRenderAt.set(latestMessage.id, Date.now())
}

function renderAssistantMarkdownImmediately(message: ConversationMessage) {
  if (message.roleCode !== 'ASSISTANT') {
    return
  }

  clearAssistantMarkdownRenderTimer(message.id)
  renderAssistantMarkdown(message)
}

function scheduleAssistantMarkdownRender(message: ConversationMessage) {
  if (message.roleCode !== 'ASSISTANT') {
    return
  }

  const renderInterval = 80
  const lastRenderedAt = assistantMarkdownLastRenderAt.get(message.id) ?? 0
  const waitMillis = Math.max(0, renderInterval - (Date.now() - lastRenderedAt))

  if (waitMillis === 0) {
    renderAssistantMarkdownImmediately(message)
    return
  }

  if (assistantMarkdownRenderTimers.has(message.id)) {
    return
  }

  // 流式阶段要保持增量可见，因此这里用节流而不是防抖；
  // 否则 token 持续到来时会一直重置定时器，最终变成“结束后一次性打印”。
  const timer = window.setTimeout(() => {
    assistantMarkdownRenderTimers.delete(message.id)
    renderAssistantMarkdown(message)
  }, waitMillis)
  assistantMarkdownRenderTimers.set(message.id, timer)
}

function updateConversationCache(conversation: Conversation) {
  const exists = conversations.value.some((item) => item.id === conversation.id)
  conversations.value = sortConversations(
    exists
    ? conversations.value.map((item) => (item.id === conversation.id ? conversation : item))
    : [conversation, ...conversations.value],
  )
}

function sortConversations(list: Conversation[]) {
  return [...list].sort((left, right) => {
    const rightTimestamp = right.lastMessageAt ?? right.updatedAt ?? 0
    const leftTimestamp = left.lastMessageAt ?? left.updatedAt ?? 0
    return rightTimestamp - leftTimestamp
  })
}

function applyConversationSettings(conversation: Conversation) {
  modeState.value.enableRag = conversation.enableRag
  modeState.value.enableAgent = conversation.enableAgent
  modeState.value.memoryEnabled = conversation.memoryEnabled
  selectedKnowledgeBaseIds.value = [...conversation.knowledgeBaseIds]
  selectedMcpServerIds.value = [...conversation.mcpServerIds]
}

function resetRequestOverrides() {
  requestKnowledgeBaseOverride.value = createIdOverrideState()
  requestMcpServerOverride.value = createIdOverrideState()
}

function arrayEquals(left: number[], right: number[]) {
  return left.length === right.length && left.every((value, index) => value === right[index])
}

function formatModeLabel(enableRag: boolean, enableAgent: boolean) {
  if (enableRag && enableAgent) {
    return 'RAG + Agent'
  }
  if (enableRag) {
    return 'RAG'
  }
  if (enableAgent) {
    return 'Agent'
  }
  return 'LLM'
}

async function persistActiveConversationSettings() {
  const conversationId = activeConversationId.value
  if (!conversationId) {
    return null
  }

  const currentConversation = conversations.value.find((item) => item.id === conversationId)
  if (
    currentConversation &&
    currentConversation.enableRag === modeState.value.enableRag &&
    currentConversation.enableAgent === modeState.value.enableAgent &&
    currentConversation.memoryEnabled === modeState.value.memoryEnabled &&
    arrayEquals(currentConversation.knowledgeBaseIds, selectedKnowledgeBaseIds.value) &&
    arrayEquals(currentConversation.mcpServerIds, selectedMcpServerIds.value)
  ) {
    return currentConversation
  }

  const updatedConversation = await updateConversationSettings(conversationId, {
    enableRag: modeState.value.enableRag,
    enableAgent: modeState.value.enableAgent,
    memoryEnabled: modeState.value.memoryEnabled,
    knowledgeBaseIds: [...selectedKnowledgeBaseIds.value],
    mcpServerIds: [...selectedMcpServerIds.value],
  })
  applyConversationSettings(updatedConversation)
  updateConversationCache(updatedConversation)
  return updatedConversation
}

async function handleConversationSettingsChange() {
  try {
    await persistActiveConversationSettings()
  } catch (error) {
    chatError.value = error instanceof RequestError ? error.message : '会话配置保存失败。'
  }
}

async function handleClearConversationMemory() {
  const conversationId = activeConversationId.value
  if (!conversationId || memoryClearing.value) {
    return
  }

  memoryClearing.value = true
  try {
    const result = await clearConversationMemory(conversationId)
    if (result.cleared) {
      ElMessage.success('当前会话 Memory 已清空。')
    }
  } catch (error) {
    chatError.value = error instanceof RequestError ? error.message : '会话 Memory 清理失败。'
  } finally {
    memoryClearing.value = false
  }
}

function removeOptimisticMessages(messageIds: number[]) {
  messages.value = messages.value.filter((item) => !messageIds.includes(item.id))
}

function closeStreams() {
  activeChatSource?.close()
  activeTraceSource?.close()
  activeChatSource = null
  activeTraceSource = null
}

function scheduleGeneratedTitleRefresh(conversationId: number) {
  if (conversationTitleRefreshTimer !== null) {
    window.clearTimeout(conversationTitleRefreshTimer)
  }

  conversationTitleRefreshTimer = window.setTimeout(async () => {
    conversationTitleRefreshTimer = null
    await loadConversations()

    if (activeConversationId.value === conversationId) {
      await selectConversation(conversationId)
    }
  }, 1800)
}

async function refreshAfterRound(conversationId: number | null, chatRequestId: string | null) {
  await loadConversations()

  if (conversationId) {
    await selectConversation(conversationId)
  }

  if (chatRequestId) {
    const detail = await getTraceDetail(chatRequestId)
    traces.value = detail.events
    activeChatRequestId.value = chatRequestId
  }
}

function formatTime(timestamp: number | null | undefined) {
  if (!timestamp) {
    return '未开始'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(timestamp)
}

function formatThinkingElapsed(startedAt: number | undefined) {
  if (!startedAt) {
    return '0s'
  }
  const elapsedSeconds = Math.max(0, Math.floor((thinkingRenderNow.value - startedAt) / 1000))
  return `${elapsedSeconds}s`
}

function normalizeMessages(
  records: ConversationMessage[],
  pendingConfirmations: ToolConfirmationPending[] = [],
) {
  const normalized: ConversationMessage[] = records.map((item) => ({
    ...item,
    uiState: item.roleCode === 'ASSISTANT' ? ('done' as const) : undefined,
    renderedContentHtml:
      item.roleCode === 'ASSISTANT' && item.content ? renderMarkdownToHtml(item.content) : undefined,
    renderCacheKey: item.roleCode === 'ASSISTANT' ? item.content : undefined,
  }))

  for (const pendingConfirmation of pendingConfirmations) {
    if (normalized.some((item) => item.requestId === pendingConfirmation.requestId && item.roleCode === 'ASSISTANT')) {
      continue
    }

    const pendingMessage: ConversationMessage = {
      id: -pendingConfirmation.id,
      conversationId: pendingConfirmation.conversationId,
      roleCode: 'ASSISTANT',
      messageType: 'TEXT',
      content: '',
      requestId: pendingConfirmation.requestId,
      finishReason: 'tool_confirmation_required',
      createdAt: pendingConfirmation.createdAt ?? Date.now(),
      uiState: 'pending_confirmation',
      pendingConfirmation,
      renderedContentHtml: '',
      renderCacheKey: '',
    }

    const userMessageIndex = normalized.findIndex((item) => item.id === pendingConfirmation.userMessageId)
    if (userMessageIndex >= 0) {
      normalized.splice(userMessageIndex + 1, 0, pendingMessage)
    } else {
      normalized.push(pendingMessage)
    }
  }

  return normalized
}

function formatTracePayload(payload: string) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}

function parseTracePayload(payload: string) {
  try {
    const parsed = JSON.parse(payload) as Record<string, unknown>
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

function findLastTrace(eventType: string) {
  for (let index = traces.value.length - 1; index >= 0; index -= 1) {
    const trace = traces.value[index]
    if (trace?.eventType === eventType) {
      return trace
    }
  }
  return null
}

function readTraceNumber(trace: TraceEvent | null, field: string) {
  const payload = trace ? parseTracePayload(trace.eventPayloadJson) : null
  if (!payload) {
    return null
  }
  const value = payload[field]
  return typeof value === 'number' ? value : null
}

function readTraceNumericArray(trace: TraceEvent | null, field: string) {
  const payload = trace ? parseTracePayload(trace.eventPayloadJson) : null
  if (!payload || !Array.isArray(payload[field])) {
    return null
  }
  return payload[field]
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item))
}

function traceTitle(trace: TraceEvent) {
  if (trace.eventType === 'PROMPT_ASSEMBLY_RESOLVED') {
    return 'Prompt 组成已固定'
  }
  if (trace.eventType === 'RAG_RETRIEVAL_STARTED') {
    return '开始检索知识库'
  }
  if (trace.eventType === 'RAG_SEGMENTS_SELECTED') {
    return '已选中召回片段'
  }
  if (trace.eventType === 'RAG_RETRIEVAL_FINISHED') {
    return '知识库检索完成'
  }
  if (trace.eventType === 'MODEL_REQUEST_STARTED') {
    return '模型请求开始'
  }
  if (trace.eventType === 'FINAL_RESPONSE_COMPLETED') {
    return '最终响应完成'
  }
  if (trace.eventType === 'AGENT_TOOLS_ATTACHED') {
    return '已装配 Agent 工具'
  }
  if (trace.eventType === 'AGENT_TOOLS_UNAVAILABLE') {
    return 'Agent 工具不可用'
  }
  if (trace.eventType === 'TOOL_EXECUTION_REQUESTED') {
    return '开始调用工具'
  }
  if (trace.eventType === 'TOOL_RISK_EVALUATED') {
    return '工具风控判定完成'
  }
  if (trace.eventType === 'TOOL_CONFIRMATION_REQUIRED') {
    return '工具需要人工确认'
  }
  if (trace.eventType === 'TOOL_EXECUTION_BLOCKED') {
    return '工具执行已阻断'
  }
  if (trace.eventType === 'TOOL_EXECUTION_COMPLETED') {
    return '工具调用完成'
  }
  return trace.eventType
}

function traceDescription(trace: TraceEvent) {
  if (trace.eventType === 'PROMPT_ASSEMBLY_RESOLVED') {
    return '当前轮真正进入主链路的 Prompt 组成已经固定，可直接核对块来源、块数量和变量摘要。'
  }
  if (trace.eventType === 'RAG_RETRIEVAL_STARTED') {
    return '当前请求已进入 RAG 检索阶段，准备按知识库分区做向量召回。'
  }
  if (trace.eventType === 'RAG_SEGMENTS_SELECTED') {
    return '向量检索已经选出候选片段，后续会把这些原文注入到模型上下文中。'
  }
  if (trace.eventType === 'RAG_RETRIEVAL_FINISHED') {
    return 'RAG 检索链路已结束，聊天主链路可以继续进入模型生成。'
  }
  if (trace.eventType === 'MODEL_REQUEST_STARTED') {
    return '模型侧请求已经发出，当前事件会反映本轮模式和注入的 RAG 片段数量。'
  }
  if (trace.eventType === 'FINAL_RESPONSE_COMPLETED') {
    return '本轮回答已完成生成并落库。'
  }
  if (trace.eventType === 'AGENT_TOOLS_ATTACHED') {
    return '当前会话已为模型挂载可用的 MCP Tool，接下来模型可以按需发起工具调用。'
  }
  if (trace.eventType === 'AGENT_TOOLS_UNAVAILABLE') {
    return '当前开启了 Agent，但没有成功装配可用工具，本轮会退化为普通模型回答。'
  }
  if (trace.eventType === 'TOOL_EXECUTION_REQUESTED') {
    return '模型已输出 Tool Call，后端正在通过 LangChain4j 官方 ToolExecutor 执行对应 MCP 工具。'
  }
  if (trace.eventType === 'TOOL_RISK_EVALUATED') {
    return '平台已经根据工具快照里的风险等级和运行时状态，完成本次工具调用的统一风控判定。'
  }
  if (trace.eventType === 'TOOL_CONFIRMATION_REQUIRED') {
    return '当前工具被识别为高风险工具，第一版运行时不会直接执行，而是先按“需要确认”处理。'
  }
  if (trace.eventType === 'TOOL_EXECUTION_BLOCKED') {
    return '当前工具调用已被平台策略直接阻断，不会真正落到 MCP 工具执行。'
  }
  if (trace.eventType === 'TOOL_EXECUTION_COMPLETED') {
    return '工具执行结果已经回填给模型，后续会继续进入下一轮生成。'
  }
  return ''
}

function traceFacts(trace: TraceEvent) {
  const payload = parseTracePayload(trace.eventPayloadJson)
  if (!payload) {
    return []
  }

  if (trace.eventType === 'RAG_RETRIEVAL_STARTED') {
    const ids = Array.isArray(payload.knowledgeBaseIds) ? payload.knowledgeBaseIds : []
    return [
      { label: '知识库', value: formatKnowledgeBaseList(ids) },
      { label: 'topK', value: formatScalar(payload.topK) },
      { label: 'minScore', value: formatScalar(payload.minScore) },
    ]
  }

  if (trace.eventType === 'RAG_SEGMENTS_SELECTED') {
    return [
      { label: '命中片段数', value: formatScalar(payload.count) },
      { label: '片段引用数', value: formatSegmentRefCount(payload.segmentRefs) },
    ]
  }

  if (trace.eventType === 'RAG_RETRIEVAL_FINISHED') {
    const ids = Array.isArray(payload.knowledgeBaseIds) ? payload.knowledgeBaseIds : []
    return [
      { label: '知识库', value: formatKnowledgeBaseList(ids) },
      { label: '最终命中数', value: formatScalar(payload.count) },
      { label: '耗时', value: trace.costMillis ? `${trace.costMillis} ms` : '未记录' },
    ]
  }

  if (trace.eventType === 'MODEL_REQUEST_STARTED') {
    return [
      { label: '模式', value: formatScalar(payload.mode) },
      { label: 'RAG 片段数', value: formatScalar(payload.ragSnippetCount) },
      { label: 'Agent 已开启', value: formatScalar(payload.agentEnabled) },
      { label: '工具数', value: formatScalar(payload.toolCount) },
      { label: '模型轮次', value: formatScalar(payload.modelRound) },
    ]
  }

  if (trace.eventType === 'PROMPT_ASSEMBLY_RESOLVED') {
    const promptKeys = Array.isArray(payload.promptKeys) ? payload.promptKeys : []
    const variableSummary =
      payload.variableSummary && typeof payload.variableSummary === 'object'
        ? (payload.variableSummary as Record<string, unknown>)
        : {}
    return [
      { label: 'Prompt 来源', value: formatScalar(payload.promptSource) },
      { label: '模式', value: formatScalar(payload.modeCode) },
      { label: 'Prompt 块', value: promptKeys.length > 0 ? promptKeys.join(' + ') : '未记录' },
      {
        label: '变量摘要',
        value:
          Object.keys(variableSummary).length > 0 ? Object.keys(variableSummary).join('、') : '无变量块',
      },
      { label: 'System 消息数', value: formatScalar(payload.systemMessageCount) },
      { label: '历史消息数', value: formatScalar(payload.historyMessageCount) },
      { label: '请求消息数', value: formatScalar(payload.requestMessageCount) },
      { label: '工具数', value: formatScalar(payload.toolCount) },
      { label: '请求结构', value: formatScalar(payload.requestStructure) },
    ]
  }

  if (trace.eventType === 'CHAT_EXECUTION_SPEC_RESOLVED') {
    const knowledgeBaseIds = Array.isArray(payload.knowledgeBaseIds) ? payload.knowledgeBaseIds : []
    const mcpServerIds = Array.isArray(payload.mcpServerIds) ? payload.mcpServerIds : []
    return [
      { label: '模式', value: formatScalar(payload.mode) },
      { label: 'Memory', value: formatBooleanLabel(payload.memoryEnabled) },
      { label: '知识库', value: formatKnowledgeBaseList(knowledgeBaseIds) },
      { label: 'MCP Server', value: formatMcpServerList(mcpServerIds) },
    ]
  }

  if (trace.eventType === 'FINAL_RESPONSE_COMPLETED') {
    return [
      { label: '回答长度', value: formatScalar(payload.answerLength) },
      { label: '耗时', value: trace.costMillis ? `${trace.costMillis} ms` : '未记录' },
    ]
  }

  if (trace.eventType === 'AGENT_TOOLS_ATTACHED') {
    return [
      { label: '工具数', value: formatScalar(payload.toolCount) },
      { label: '工具列表', value: formatScalar(payload.toolNames) },
    ]
  }

  if (trace.eventType === 'AGENT_TOOLS_UNAVAILABLE') {
    return [
      { label: '原因', value: formatScalar(payload.reason) },
      { label: '消息', value: formatScalar(payload.message) },
    ]
  }

  if (trace.eventType === 'TOOL_EXECUTION_REQUESTED') {
    return [
      { label: '工具名', value: formatScalar(payload.toolName) },
      { label: '调用 ID', value: formatScalar(payload.toolCallId) },
      { label: '模型轮次', value: formatScalar(payload.modelRound) },
      { label: '参数预览', value: formatScalar(payload.arguments) },
    ]
  }

  if (trace.eventType === 'TOOL_RISK_EVALUATED') {
    return [
      { label: '工具名', value: formatScalar(payload.toolName) },
      { label: '风险等级', value: formatScalar(payload.riskLevel) },
      { label: '决策', value: formatScalar(payload.decision) },
      { label: '判定原因', value: formatScalar(payload.reason) },
      { label: 'Server', value: formatScalar(payload.serverName) },
    ]
  }

  if (trace.eventType === 'TOOL_CONFIRMATION_REQUIRED') {
    return [
      { label: '工具名', value: formatScalar(payload.toolName) },
      { label: '风险等级', value: formatScalar(payload.riskLevel) },
      { label: '决策', value: formatScalar(payload.decision) },
      { label: '判定原因', value: formatScalar(payload.reason) },
      { label: '参数预览', value: formatScalar(payload.arguments) },
    ]
  }

  if (trace.eventType === 'TOOL_EXECUTION_BLOCKED') {
    return [
      { label: '工具名', value: formatScalar(payload.toolName) },
      { label: '风险等级', value: formatScalar(payload.riskLevel) },
      { label: '决策', value: formatScalar(payload.decision) },
      { label: '判定原因', value: formatScalar(payload.reason) },
      { label: 'Server', value: formatScalar(payload.serverName) },
    ]
  }

  if (trace.eventType === 'TOOL_EXECUTION_COMPLETED') {
    return [
      { label: '工具名', value: formatScalar(payload.toolName) },
      { label: '调用 ID', value: formatScalar(payload.toolCallId) },
      { label: '风险等级', value: formatScalar(payload.riskLevel) },
      { label: '决策', value: formatScalar(payload.decision) },
      { label: '模型轮次', value: formatScalar(payload.modelRound) },
      { label: '是否错误', value: formatScalar(payload.isError) },
      { label: '耗时', value: trace.costMillis ? `${trace.costMillis} ms` : '未记录' },
      { label: '结果预览', value: formatScalar(payload.resultPreview) },
    ]
  }

  return Object.entries(payload).map(([label, value]) => ({
    label,
    value: formatScalar(value),
  }))
}

function selectedSegmentRefs(trace: TraceEvent) {
  const payload = parseTracePayload(trace.eventPayloadJson)
  if (!payload || trace.eventType !== 'RAG_SEGMENTS_SELECTED' || !Array.isArray(payload.segmentRefs)) {
    return []
  }
  return payload.segmentRefs.map((item) => String(item))
}

function isStructuredTrace(trace: TraceEvent) {
  return [
    'PROMPT_ASSEMBLY_RESOLVED',
    'RAG_RETRIEVAL_STARTED',
    'RAG_SEGMENTS_SELECTED',
    'RAG_RETRIEVAL_FINISHED',
    'MODEL_REQUEST_STARTED',
    'CHAT_EXECUTION_SPEC_RESOLVED',
    'FINAL_RESPONSE_COMPLETED',
    'AGENT_TOOLS_ATTACHED',
    'AGENT_TOOLS_UNAVAILABLE',
    'TOOL_EXECUTION_REQUESTED',
    'TOOL_RISK_EVALUATED',
    'TOOL_CONFIRMATION_REQUIRED',
    'TOOL_EXECUTION_BLOCKED',
    'TOOL_EXECUTION_COMPLETED',
  ].includes(trace.eventType)
}

function isModelTrace(trace: TraceEvent) {
  return trace.eventType.startsWith('MODEL_') || trace.eventType.startsWith('FINAL_RESPONSE_')
}

function isPromptTrace(trace: TraceEvent) {
  return trace.eventType.startsWith('PROMPT_') || trace.eventStage === 'PROMPT'
}

function isToolTrace(trace: TraceEvent) {
  return (
    trace.eventType.startsWith('TOOL_') ||
    trace.eventType.startsWith('AGENT_TOOL') ||
    trace.eventStage === 'TOOL'
  )
}

function isLowValueTrace(trace: TraceEvent) {
  return trace.successFlag && !isStructuredTrace(trace)
}

function shouldExpandTraceByDefault(trace: TraceEvent) {
  return !isLowValueTrace(trace)
}

function matchesTraceFilter(trace: TraceEvent) {
  if (traceFilter.value === 'PROMPT') {
    return isPromptTrace(trace)
  }
  if (traceFilter.value === 'RAG') {
    return trace.eventType.startsWith('RAG_')
  }
  if (traceFilter.value === 'MODEL') {
    return isModelTrace(trace)
  }
  if (traceFilter.value === 'TOOL') {
    return isToolTrace(trace)
  }
  if (traceFilter.value === 'FAILED') {
    return !trace.successFlag
  }
  return true
}

function isTraceExpanded(traceId: number) {
  return traceExpandedIds.value.includes(traceId)
}

function toggleTraceExpanded(traceId: number) {
  if (isTraceExpanded(traceId)) {
    traceExpandedIds.value = traceExpandedIds.value.filter((id) => id !== traceId)
    return
  }
  traceExpandedIds.value = [...traceExpandedIds.value, traceId]
}

function applyDefaultTraceExpansion() {
  traceExpandedIds.value = traces.value
    .filter((trace) => shouldExpandTraceByDefault(trace))
    .map((trace) => trace.id)
}

function expandAllTraces() {
  traceExpandedIds.value = traces.value.map((trace) => trace.id)
}

function formatKnowledgeBaseList(ids: unknown[]) {
  if (ids.length === 0) {
    return '未指定'
  }

  return ids
    .map((item) => {
      const numericId = Number(item)
      return knowledgeBaseNameMap.value.get(numericId) ?? `#${numericId}`
    })
    .join('、')
}

function formatMcpServerList(ids: unknown[]) {
  if (ids.length === 0) {
    return '未指定'
  }

  return ids
    .map((item) => {
      const numericId = Number(item)
      return mcpServerNameMap.value.get(numericId) ?? `#${numericId}`
    })
    .join('、')
}

function formatSegmentRefCount(value: unknown) {
  if (!Array.isArray(value)) {
    return '0'
  }
  return String(value.length)
}

function formatBooleanLabel(value: unknown) {
  if (value === true) {
    return '开启'
  }
  if (value === false) {
    return '关闭'
  }
  return '未记录'
}

function formatScalar(value: unknown) {
  if (Array.isArray(value)) {
    return value.join(', ')
  }
  if (value === null || value === undefined || value === '') {
    return '未记录'
  }
  return String(value)
}

watch(
  traces,
  (next, previous) => {
    const previousIds = new Set(previous.map((item) => item.id))
    const nextIds = new Set(next.map((item) => item.id))
    const expanded = new Set(traceExpandedIds.value.filter((id) => nextIds.has(id)))

    for (const trace of next) {
      if (!previousIds.has(trace.id) && shouldExpandTraceByDefault(trace)) {
        expanded.add(trace.id)
      }
    }

    traceExpandedIds.value = Array.from(expanded)
  },
  { deep: false },
)

watch(messageRenderSignature, async () => {
  await nextTick()
  keepChatHistoryPinned()
})

onMounted(async () => {
  thinkingTicker = window.setInterval(() => {
    thinkingRenderNow.value = Date.now()
  }, 1000)
  await Promise.all([loadConversations(false), loadKnowledgeOptions(), loadMcpServerOptions()])
  await focusChatInput()
})

onBeforeUnmount(() => {
  if (thinkingTicker !== null) {
    window.clearInterval(thinkingTicker)
  }
  if (chatScrollFrame !== null) {
    window.cancelAnimationFrame(chatScrollFrame)
  }
  if (conversationTitleRefreshTimer !== null) {
    window.clearTimeout(conversationTitleRefreshTimer)
  }
  resetAssistantMarkdownRenderState()
  closeStreams()
})
</script>

<template>
  <section class="chat-shell">
    <aside class="chat-sidebar">
      <el-card class="panel sidebar-panel" shadow="never">
        <div class="sidebar-fixed">
          <RouterLink class="sidebar-brand" to="/chat">
            <p class="sidebar-brand-eyebrow">LangChain4j Learning Platform</p>
            <h1 class="sidebar-brand-title">Open Agent Platform</h1>
          </RouterLink>

          <div class="sidebar-actions">
            <button class="sidebar-action-button" type="button" @click="createNewConversation">
              <el-icon><Plus /></el-icon>
              <span>新聊天</span>
            </button>
            <RouterLink class="sidebar-action-button sidebar-action-button--secondary" to="/admin/system">
              <el-icon><Setting /></el-icon>
              <span>进入后台管理</span>
            </RouterLink>
          </div>
        </div>

        <div class="sidebar-divider" aria-hidden="true"></div>

        <div class="sidebar-scroll-area">
          <div v-if="conversations.length === 0" class="sidebar-empty-state">还没有聊天记录</div>

          <div v-else class="conversation-list">
            <article
              v-for="conversation in conversations"
              :key="conversation.id"
              class="conversation-item"
              :class="{ 'conversation-item--active': conversation.id === activeConversationId }"
            >
              <button class="conversation-item-main" type="button" @click="selectConversation(conversation.id)">
                <strong class="conversation-item-title">{{ conversation.title }}</strong>
              </button>

              <el-dropdown
                placement="bottom-end"
                popper-class="conversation-item-dropdown"
                trigger="click"
                @command="handleConversationDropdownCommand(conversation, $event)"
              >
                <button class="conversation-item-menu" type="button" aria-label="更多操作">
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="open">打开会话</el-dropdown-item>
                    <el-dropdown-item command="settings">打开会话配置</el-dropdown-item>
                    <el-dropdown-item command="copy-id">复制会话 ID</el-dropdown-item>
                    <el-dropdown-item
                      class="conversation-item-dropdown__item conversation-item-dropdown__item--danger"
                      command="delete"
                    >
                      <el-icon><Delete /></el-icon>
                      <span>删除会话</span>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </article>
          </div>
        </div>
      </el-card>
    </aside>

    <main class="chat-main">
    <el-card class="panel chat-stage" shadow="never">
      <el-alert v-if="chatError" :closable="false" show-icon title="聊天请求失败" type="error">
        <template #default>{{ chatError }}</template>
      </el-alert>

      <div class="chat-history-shell">
        <div
          ref="chatHistoryRef"
          class="chat-history"
          :class="{ 'chat-history--empty': !chatStarted }"
          @scroll="handleChatHistoryScroll"
        >
          <div v-if="messages.length === 0" class="empty-state">
            当前会话还没有消息。输入一条内容后，后端会把 user / assistant 消息持久化到
            `conversation_message`，并在启用 RAG 时附带知识库检索上下文。
          </div>

          <article
            v-for="message in messages"
            :key="`${message.id}-${message.createdAt}`"
            class="message-item"
            :class="`message-item--${message.roleCode.toLowerCase()}`"
          >
            <div class="message-meta">
              <strong>{{ message.roleCode }}</strong>
              <span>{{ formatTime(message.createdAt) }}</span>
            </div>
            <div
              v-if="message.roleCode === 'ASSISTANT' && message.content"
              class="assistant-markdown"
              v-html="message.renderedContentHtml || ''"
            ></div>
            <p v-else-if="message.content">{{ message.content }}</p>
            <div
              v-else-if="
                message.roleCode === 'ASSISTANT' && message.uiState === 'pending_confirmation'
              "
              class="pending-confirmation-block"
            >
              <p class="pending-confirmation-block__title">
                {{ formatPendingConfirmationLabel(message.pendingConfirmation) }} 需要确认
              </p>
              <p class="pending-confirmation-block__desc">
                {{ message.pendingConfirmation?.statusMessage || '当前工具调用已被风险策略拦截。' }}
              </p>
              <div class="pending-confirmation-block__meta">
                <span v-if="message.pendingConfirmation?.serverName">
                  Server: {{ message.pendingConfirmation.serverName }}
                </span>
                <span v-if="message.pendingConfirmation?.riskLevel">
                  风险等级: {{ message.pendingConfirmation.riskLevel }}
                </span>
              </div>
              <div class="pending-confirmation-block__actions">
                <el-button
                  type="primary"
                  size="small"
                  :disabled="chatLoading"
                  @click="handleApproveToolConfirmation(message)"
                >
                  确认执行
                </el-button>
                <el-button
                  size="small"
                  :disabled="chatLoading"
                  @click="handleRejectToolConfirmation(message)"
                >
                  拒绝执行
                </el-button>
              </div>
            </div>
            <div v-else-if="message.roleCode === 'ASSISTANT' && message.uiState === 'thinking'" class="thinking-block">
              <span class="thinking-dot"></span>
              <span>{{ message.progressMessage || '模型正在思考，请稍候…' }}</span>
              <span class="thinking-elapsed">{{ formatThinkingElapsed(message.thinkingStartedAt) }}</span>
            </div>
            <p v-else>...</p>

            <div v-if="canOpenTraceForMessage(message)" class="message-actions">
              <el-button link type="primary" @click="openTraceDialogForMessage(message)">当前轮次摘要</el-button>
            </div>
          </article>
        </div>

        <button
          v-if="showScrollToLatest"
          class="scroll-to-latest-button"
          title="回到最新消息"
          type="button"
          @click="scrollChatHistoryToBottom(true)"
        >
          <span aria-hidden="true">↓</span>
        </button>
      </div>

      <div class="chat-editor" :class="{ 'chat-editor--welcome': !chatStarted }">
        <div class="editor-topbar">
          <div class="stage-facts">
            <span v-for="item in stageFactItems" :key="`${item.label}-${item.value}`" class="stage-fact-pill">
              <strong>{{ item.label }}</strong>
              <small>{{ item.value }}</small>
            </span>
          </div>
          <span class="editor-shortcut">Enter 发送 · Shift + Enter 换行</span>
        </div>

        <el-input
          ref="chatInputRef"
          v-model="chatInput"
          :autosize="{ minRows: 3, maxRows: 7 }"
          :disabled="chatLoading"
          maxlength="20000"
          @keydown.enter.exact.prevent="handleComposerEnter"
          placeholder="输入一条消息，验证会话、Trace 与 RAG 上下文注入。"
          resize="none"
          show-word-limit
          type="textarea"
        />

        <div class="chat-actions">
          <button
            class="editor-action-button editor-action-button--config"
            title="打开模式、Memory、知识库和 MCP 配置"
            type="button"
            @click="settingsDrawerVisible = true"
          >
            <el-icon><Setting /></el-icon>
            <span>聊天配置</span>
          </button>

          <div class="chat-actions-primary">
            <button
              :disabled="chatLoading"
              class="editor-action-button editor-action-button--primary"
              title="优先使用流式输出，边生成边返回"
              type="button"
              @click="handleSend"
            >
              <span>{{ chatLoading ? '发送中...' : '流式发送' }}</span>
            </button>
            <button
              :disabled="chatLoading"
              class="editor-action-button editor-action-button--secondary"
              title="等待完整结果后一次性返回"
              type="button"
              @click="handleSendSync"
            >
              <span>同步发送</span>
            </button>
          </div>
        </div>
      </div>
    </el-card>
    </main>
  </section>

  <el-dialog v-model="traceDialogVisible" title="当前轮次摘要" width="880px">
    <div class="trace-dialog">
      <div class="trace-dialog-head">
        <div class="trace-summary-grid">
          <div class="trace-summary-card">
            <span>requestId</span>
            <strong>{{ currentRoundSummary.requestId || '未发送' }}</strong>
          </div>
          <div class="trace-summary-card">
            <span>模式</span>
            <strong>{{ activeModeLabel }}</strong>
            <small>{{ currentRoundSummary.requestStatus }}</small>
          </div>
          <div class="trace-summary-card">
            <span>知识库</span>
            <strong>{{ currentRoundSummary.knowledgeBaseText }}</strong>
          </div>
          <div class="trace-summary-card">
            <span>RAG</span>
            <strong>{{ currentRoundSummary.ragStatus }}</strong>
            <small>命中 {{ currentRoundSummary.retrievedCount }} 个片段</small>
          </div>
          <div class="trace-summary-card">
            <span>Prompt</span>
            <strong>{{ currentRoundSummary.promptSummaryText }}</strong>
            <small>{{ currentRoundSummary.promptSource }} · 共 {{ currentRoundSummary.promptBlocks }} 块</small>
          </div>
        </div>

        <div class="trace-toolbar">
          <el-select v-model="traceFilter" class="trace-filter-select">
            <el-option label="全部事件" value="ALL" />
            <el-option label="只看 PROMPT" value="PROMPT" />
            <el-option label="只看 RAG" value="RAG" />
            <el-option label="只看 MODEL" value="MODEL" />
            <el-option label="只看 TOOL" value="TOOL" />
            <el-option label="只看失败" value="FAILED" />
          </el-select>
          <el-button link @click="applyDefaultTraceExpansion">按默认折叠</el-button>
          <el-button link @click="expandAllTraces">展开全部</el-button>
        </div>
      </div>

      <el-alert v-if="traceError" :closable="false" show-icon title="Trace 详情加载失败" type="error">
        <template #default>{{ traceError }}</template>
      </el-alert>

      <div v-loading="traceDialogLoading" class="trace-list">
        <div v-if="traces.length === 0" class="empty-state">当前还没有可展示的 Trace 事件。</div>

        <div v-else-if="filteredTraces.length === 0" class="empty-state">
          当前筛选条件下没有匹配的 Trace 事件。
        </div>

        <article v-for="trace in filteredTraces" :key="trace.id" class="trace-item">
          <div class="trace-head">
            <div class="trace-head-main">
              <strong>{{ traceTitle(trace) }}</strong>
              <span>{{ formatTime(trace.createdAt) }}</span>
            </div>
            <el-button link type="primary" @click="toggleTraceExpanded(trace.id)">
              {{ isTraceExpanded(trace.id) ? '收起详情' : '展开详情' }}
            </el-button>
          </div>
          <div class="trace-meta">
            <span>{{ trace.eventType }}</span>
            <span>{{ trace.eventStage || 'NO_STAGE' }}</span>
            <span>{{ trace.eventSource }}</span>
            <span>{{ trace.successFlag ? 'SUCCESS' : 'FAILED' }}</span>
          </div>
          <div v-if="isTraceExpanded(trace.id)" class="trace-body">
            <p v-if="traceDescription(trace)" class="trace-description">{{ traceDescription(trace) }}</p>
            <div v-if="traceFacts(trace).length > 0" class="trace-facts">
              <div v-for="fact in traceFacts(trace)" :key="`${trace.id}-${fact.label}`" class="trace-fact">
                <span>{{ fact.label }}</span>
                <strong>{{ fact.value }}</strong>
              </div>
            </div>
            <div v-if="selectedSegmentRefs(trace).length > 0" class="segment-ref-list">
              <div class="segment-ref-head">命中片段引用</div>
              <code v-for="segmentRef in selectedSegmentRefs(trace)" :key="segmentRef">{{ segmentRef }}</code>
            </div>
            <details class="trace-raw" :open="!isStructuredTrace(trace)">
              <summary>原始 Payload</summary>
              <pre>{{ formatTracePayload(trace.eventPayloadJson) }}</pre>
            </details>
          </div>
        </article>
      </div>
    </div>
  </el-dialog>

  <el-drawer
    v-model="settingsDrawerVisible"
    :title="activeConversationId ? '会话配置与本轮覆盖' : '发送前配置草稿'"
    direction="rtl"
    size="480px"
  >
    <div class="drawer-intro">
      <p>
        {{
          activeConversationId
            ? '这里集中管理当前会话的默认配置，以及本轮运行时的临时覆盖。默认配置会写回会话；覆盖配置只影响本轮。'
            : '当前还没有选中会话；模式与本轮覆盖可直接参与首轮发送，默认知识库 / MCP 绑定需在具体会话上保存。'
        }}
      </p>
    </div>

    <div class="settings-panel">
      <div class="rag-attach-head">
        <strong>会话默认配置</strong>
        <span>{{ activeConversationId ? '修改后会立即保存到当前会话。' : '当前仅保留发送前草稿。' }}</span>
      </div>

      <p v-if="!activeConversationId" class="hint-text">
        草稿态下这里的配置不会提前创建空会话；首轮发送时会和新会话一起落库。
      </p>
      <div class="chat-toolbar">
        <el-switch v-model="modeState.enableRag" active-text="启用 RAG" @change="handleConversationSettingsChange" />
        <el-switch
          v-model="modeState.enableAgent"
          active-text="启用 Agent"
          @change="handleConversationSettingsChange"
        />
        <el-switch
          v-model="modeState.memoryEnabled"
          active-text="启用 Memory"
          @change="handleConversationSettingsChange"
        />
      </div>

      <p class="mode-hint">
        Agent 模式当前会优先挂载“已启用且健康”的 MCP Server 工具；如果没有可用工具，本轮会自动退化为普通模型回答。
      </p>

      <div class="settings-grid">
        <div class="settings-block">
          <div class="settings-inline-head">
            <span class="settings-label">默认知识库</span>
            <small>{{ activeConversationId ? '持久化到会话绑定' : '先选择会话后可保存' }}</small>
          </div>
          <el-select
            v-model="selectedKnowledgeBaseIds"
            :disabled="!modeState.enableRag || knowledgeLoading"
            clearable
            collapse-tags
            collapse-tags-tooltip
            filterable
            multiple
            placeholder="为当前会话选择默认知识库"
            @change="handleConversationSettingsChange"
          >
            <el-option
              v-for="knowledgeBase in availableKnowledgeBases"
              :key="knowledgeBase.id"
              :label="`${knowledgeBase.name} · ${knowledgeBase.segmentCount} 段`"
              :value="knowledgeBase.id"
            />
          </el-select>
          <div v-if="selectedKnowledgeBases.length > 0" class="tag-row">
            <el-tag
              v-for="knowledgeBase in selectedKnowledgeBases"
              :key="knowledgeBase.id"
              effect="plain"
              round
              type="warning"
            >
              {{ knowledgeBase.name }}
            </el-tag>
          </div>
        </div>

        <div class="settings-block">
          <div class="settings-inline-head">
            <span class="settings-label">默认 MCP Server</span>
            <small>{{ activeConversationId ? '持久化到会话绑定' : '先选择会话后可保存' }}</small>
          </div>
          <el-select
            v-model="selectedMcpServerIds"
            :disabled="!modeState.enableAgent || mcpLoading"
            clearable
            collapse-tags
            collapse-tags-tooltip
            filterable
            multiple
            placeholder="为当前会话选择默认 MCP Server"
            @change="handleConversationSettingsChange"
          >
            <el-option
              v-for="server in availableMcpServers"
              :key="server.id"
              :label="`${server.name} · ${server.healthStatus} · ${server.toolCount} tools`"
              :value="server.id"
            />
          </el-select>
          <div v-if="selectedMcpServers.length > 0" class="tag-row">
            <el-tag v-for="server in selectedMcpServers" :key="server.id" effect="plain" round type="success">
              {{ server.name }}
            </el-tag>
          </div>
        </div>
      </div>

      <div class="settings-actions">
        <el-button
          :disabled="!activeConversationId || memoryClearing"
          :loading="memoryClearing"
          plain
          @click="handleClearConversationMemory"
        >
          清空会话 Memory
        </el-button>
      </div>

      <el-alert v-if="knowledgeError" :closable="false" show-icon title="知识库列表加载失败" type="error">
        <template #default>{{ knowledgeError }}</template>
      </el-alert>
      <el-alert v-if="mcpError" :closable="false" show-icon title="MCP Server 列表加载失败" type="error">
        <template #default>{{ mcpError }}</template>
      </el-alert>
      <p v-if="!knowledgeError && availableKnowledgeBases.length === 0" class="hint-text">
        当前还没有可选知识库。可以去 <RouterLink to="/admin/knowledge">知识库页面</RouterLink> 创建后再回来。
      </p>
    </div>

    <div class="settings-panel">
      <div class="rag-attach-head">
        <strong>本次请求覆盖</strong>
        <span>只影响当前轮运行时，不会写回会话默认配置。</span>
      </div>

      <div class="settings-grid settings-grid--request">
        <div class="settings-block">
          <div class="settings-inline-head">
            <span class="settings-label">知识库覆盖</span>
            <el-switch
              v-model="requestKnowledgeBaseOverride.enabled"
              :disabled="!modeState.enableRag"
              active-text="启用覆盖"
            />
          </div>
          <el-select
            v-model="requestKnowledgeBaseOverride.ids"
            :disabled="!modeState.enableRag || !requestKnowledgeBaseOverride.enabled || knowledgeLoading"
            clearable
            collapse-tags
            collapse-tags-tooltip
            filterable
            multiple
            placeholder="不启用覆盖时，自动回落到会话默认知识库"
          >
            <el-option
              v-for="knowledgeBase in availableKnowledgeBases"
              :key="knowledgeBase.id"
              :label="`${knowledgeBase.name} · ${knowledgeBase.segmentCount} 段`"
              :value="knowledgeBase.id"
            />
          </el-select>
          <p class="hint-text">
            {{
              requestKnowledgeBaseOverride.enabled
                ? requestKnowledgeBaseOverride.ids.length > 0
                  ? `本轮显式覆盖 ${requestKnowledgeBaseOverride.ids.length} 个知识库。`
                  : '本轮显式清空知识库绑定；即使会话已绑定，也不再注入。'
                : `当前回落到会话默认：${selectedKnowledgeBaseIds.length} 个知识库。`
            }}
          </p>
        </div>

        <div class="settings-block">
          <div class="settings-inline-head">
            <span class="settings-label">MCP Server 覆盖</span>
            <el-switch
              v-model="requestMcpServerOverride.enabled"
              :disabled="!modeState.enableAgent"
              active-text="启用覆盖"
            />
          </div>
          <el-select
            v-model="requestMcpServerOverride.ids"
            :disabled="!modeState.enableAgent || !requestMcpServerOverride.enabled || mcpLoading"
            clearable
            collapse-tags
            collapse-tags-tooltip
            filterable
            multiple
            placeholder="不启用覆盖时，自动回落到会话默认 MCP Server"
          >
            <el-option
              v-for="server in availableMcpServers"
              :key="server.id"
              :label="`${server.name} · ${server.healthStatus} · ${server.toolCount} tools`"
              :value="server.id"
            />
          </el-select>
          <p class="hint-text">
            {{
              requestMcpServerOverride.enabled
                ? requestMcpServerOverride.ids.length > 0
                  ? `本轮显式覆盖 ${requestMcpServerOverride.ids.length} 个 MCP Server。`
                  : '本轮显式清空 MCP 绑定；即使会话已绑定，也不挂载工具。'
                : `当前回落到会话默认：${selectedMcpServerIds.length} 个 MCP Server。`
            }}
          </p>
        </div>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.chat-shell {
  display: grid;
  gap: 14px;
  grid-template-columns: 292px minmax(0, 1fr);
  align-items: stretch;
  height: 100vh;
  padding: 12px 10px 12px 0;
  overflow: hidden;
}

.chat-sidebar {
  min-width: 0;
  position: sticky;
  top: 12px;
  align-self: stretch;
  height: calc(100vh - 24px);
}

.chat-main {
  min-width: 0;
  height: calc(100vh - 24px);
  width: 100%;
  max-width: none;
  justify-self: stretch;
}

.panel {
  min-height: 0;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
}

.sidebar-panel {
  height: 100%;
  border-left: none;
  border-radius: 0 24px 24px 0;
  background:
    linear-gradient(180deg, rgba(246, 249, 252, 0.96), rgba(255, 255, 255, 0.88)),
    rgba(255, 255, 255, 0.86);
}

.chat-stage {
  border-radius: 22px;
  height: 100%;
  background:
    linear-gradient(180deg, rgba(255, 252, 244, 0.96), rgba(255, 255, 255, 0.9)),
    rgba(255, 255, 255, 0.86);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.section-head h2 {
  margin: 0;
  font-size: 20px;
}

.section-kicker {
  margin: 0 0 6px;
  font-size: 12px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #8b5e21;
}

.meta-inline,
.trace-meta,
.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  color: #64748b;
  font-size: 13px;
}

.sidebar-panel,
.chat-stage,
.trace-inline-panel {
  display: grid;
  gap: 10px;
}

:deep(.chat-stage > .el-card__body) {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  padding: 16px 18px 18px;
}

:deep(.sidebar-panel > .el-card__body) {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  height: 100%;
  padding: 0;
  overflow: hidden;
}

.sidebar-fixed {
  display: grid;
  gap: 14px;
  padding: 18px 16px 14px;
}

.sidebar-brand {
  display: grid;
  gap: 2px;
  color: inherit;
  text-decoration: none;
}

.sidebar-brand-eyebrow {
  margin: 0;
  color: #8b5e21;
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.sidebar-brand-title {
  margin: 0;
  color: #0f172a;
  font-size: clamp(18px, 1.8vw, 22px);
  line-height: 1.15;
}

.sidebar-actions {
  display: grid;
  gap: 8px;
}

.sidebar-action-button {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 44px;
  padding: 0 14px;
  border: 1px solid rgba(31, 78, 121, 0.12);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.74);
  color: #123b63;
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  opacity: 0.94;
  transition:
    opacity 160ms ease,
    transform 160ms ease,
    border-color 160ms ease,
    background 160ms ease,
    box-shadow 160ms ease;
}

.sidebar-action-button:hover {
  opacity: 1;
  transform: translateY(-1px);
  border-color: rgba(31, 78, 121, 0.26);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.06);
}

.sidebar-action-button .el-icon {
  font-size: 15px;
}

.sidebar-action-button--secondary {
  color: #3c4f61;
}

.sidebar-divider {
  height: 1px;
  margin: 0 16px;
  background: rgba(15, 23, 42, 0.08);
}

.sidebar-scroll-area {
  min-height: 0;
  overflow-y: auto;
  padding: 12px 10px 14px 12px;
}

.sidebar-empty-state {
  padding: 10px 8px;
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
}

.conversation-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.62);
  transition:
    transform 160ms ease,
    border-color 160ms ease,
    box-shadow 160ms ease,
    background 160ms ease;
}

.conversation-item:hover,
.conversation-item--active {
  border-color: rgba(31, 78, 121, 0.28);
  background: linear-gradient(135deg, rgba(255, 248, 230, 0.76), rgba(255, 255, 255, 0.92));
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.05);
}

.conversation-item-main {
  display: grid;
  gap: 0;
  min-width: 0;
  padding: 10px 0 10px 12px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.conversation-item strong {
  color: #0f172a;
}

.conversation-item-menu {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  margin-right: 6px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #64748b;
  opacity: 0.48;
  cursor: pointer;
  transition:
    opacity 140ms ease,
    background 140ms ease,
    color 140ms ease;
}

.conversation-item:hover .conversation-item-menu,
.conversation-item:focus-within .conversation-item-menu,
.conversation-item--active .conversation-item-menu {
  opacity: 1;
  background: rgba(15, 23, 42, 0.06);
  color: #0f172a;
}

.chat-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.sidebar-footnote {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
}

.conversation-item-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  line-height: 1.5;
}

:global(.conversation-item-dropdown) {
  padding: 8px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.12);
  backdrop-filter: blur(18px);
}

:global(.conversation-item-dropdown .el-dropdown-menu) {
  padding: 0;
  border: none;
  background: transparent;
  box-shadow: none;
}

:global(.conversation-item-dropdown .el-dropdown-menu__item) {
  min-width: 180px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 2px 0;
  padding: 10px 12px;
  border-radius: 12px;
  color: #334155;
  font-size: 13px;
  line-height: 1.4;
}

:global(.conversation-item-dropdown .el-dropdown-menu__item .el-icon) {
  color: #64748b;
  font-size: 15px;
}

:global(.conversation-item-dropdown .el-dropdown-menu__item:not(.is-disabled):hover),
:global(.conversation-item-dropdown .el-dropdown-menu__item:not(.is-disabled):focus) {
  background: rgba(245, 247, 250, 0.96);
  color: #0f172a;
}

:global(.conversation-item-dropdown .conversation-item-dropdown__item--danger) {
  color: #b42318;
}

:global(.conversation-item-dropdown .conversation-item-dropdown__item--danger .el-icon) {
  color: #dc2626;
}

:global(.conversation-item-dropdown .conversation-item-dropdown__item--danger:not(.is-disabled):hover),
:global(.conversation-item-dropdown .conversation-item-dropdown__item--danger:not(.is-disabled):focus) {
  background: rgba(254, 242, 242, 0.96);
  color: #991b1b;
}

:global(.conversation-delete-dialog) {
  width: min(480px, calc(100vw - 32px));
  border-radius: 22px;
  padding: 12px;
}

:global(.conversation-delete-dialog .el-message-box__container) {
  padding: 4px 0 0;
}

:global(.conversation-delete-dialog .el-message-box__status) {
  display: none;
}

:global(.conversation-delete-dialog .el-message-box__header) {
  padding: 6px 0 0;
}

:global(.conversation-delete-dialog .el-message-box__title) {
  color: #0f172a;
  font-size: 18px;
  font-weight: 700;
}

:global(.conversation-delete-dialog .el-message-box__content) {
  padding: 14px 0 6px;
  color: #475569;
  line-height: 1.7;
}

:global(.conversation-delete-dialog .el-message-box__message) {
  margin: 0;
}

:global(.conversation-delete-dialog .el-message-box__message p) {
  margin: 0;
}

:global(.conversation-delete-dialog .el-message-box__btns) {
  gap: 10px;
  padding: 14px 0 0;
}

:global(.conversation-delete-dialog .el-message-box__btns .el-button) {
  min-height: 40px;
  padding: 0 18px;
  border-radius: 14px;
  font-weight: 600;
}

:global(.conversation-delete-dialog__cancel) {
  border-color: rgba(15, 23, 42, 0.1);
  background: rgba(248, 250, 252, 0.92);
  color: #334155;
}

:global(.conversation-delete-dialog__cancel:hover),
:global(.conversation-delete-dialog__cancel:focus-visible) {
  border-color: rgba(15, 23, 42, 0.16);
  background: rgba(241, 245, 249, 0.96);
  color: #0f172a;
}

:global(.conversation-delete-dialog__confirm) {
  border-color: #dc2626;
  background: #dc2626;
}

:global(.conversation-delete-dialog__confirm:hover),
:global(.conversation-delete-dialog__confirm:focus-visible) {
  border-color: #b91c1c;
  background: #b91c1c;
}

.conversation-list {
  display: grid;
  gap: 6px;
}

.trace-inline-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px 16px;
}

.trace-inline-head h3 {
  margin: 0;
}

.stage-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.stage-fact-pill {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(248, 250, 252, 0.9);
  border: 1px solid rgba(15, 23, 42, 0.06);
  color: #516172;
  font-size: 12px;
}

.stage-fact-pill strong {
  color: #0f172a;
  font-size: 12px;
}

.stage-fact-pill small {
  color: #64748b;
}

.trace-inline-head h3 {
  font-size: 20px;
}

.mode-hint {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.rag-attach-panel {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.76);
}

.rag-attach-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: #516172;
  font-size: 13px;
}

.conversation-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.overview-card {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.78);
}

.overview-card span,
.overview-card small {
  color: #64748b;
  font-size: 12px;
}

.overview-card strong {
  color: #0f172a;
  line-height: 1.5;
}

.trace-inline-panel,
.drawer-intro {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.76);
}

.trace-list {
  display: grid;
  gap: 12px;
}

.trace-panel-note {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.7;
}

.settings-panel {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.76);
}

.settings-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.settings-grid--request {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.settings-block {
  display: grid;
  gap: 10px;
  align-content: start;
  padding: 14px;
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.78);
}

.settings-inline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.settings-inline-head small {
  color: #64748b;
  font-size: 12px;
}

.settings-label {
  color: #0f172a;
  font-size: 14px;
  font-weight: 600;
}

.settings-actions {
  display: flex;
  justify-content: flex-end;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hint-text {
  margin: 0;
  color: #64748b;
  line-height: 1.7;
}

.chat-history {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  height: 100%;
  overflow-y: auto;
  padding: 16px 44px 14px;
  margin: 4px 0;
  border-radius: 22px;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.9), rgba(241, 245, 249, 0.68));
}

.chat-history--empty {
  min-height: 0;
  align-content: center;
}

.chat-history-shell {
  position: relative;
  flex: 1;
  min-height: 0;
}

.message-item {
  max-width: min(82%, 840px);
  padding: 14px 16px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.04);
}

.message-item--user {
  justify-self: end;
  max-width: min(74%, 720px);
  border-radius: 20px 20px 8px 20px;
  background: linear-gradient(135deg, #1f4e79, #285f8e);
  color: #f8fafc;
}

.message-item--assistant {
  justify-self: start;
  max-width: min(86%, 920px);
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 20px 20px 20px 8px;
}

.message-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
  opacity: 0;
  pointer-events: none;
  transition: opacity 140ms ease;
}

.message-item--assistant:hover .message-actions,
.message-item--assistant:focus-within .message-actions {
  opacity: 1;
  pointer-events: auto;
}

.message-item p {
  margin: 8px 0 0;
  white-space: pre-wrap;
  line-height: 1.75;
}

.assistant-markdown {
  display: grid;
  gap: 12px;
  margin-top: 8px;
}

.assistant-markdown :deep(p),
.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol),
.assistant-markdown :deep(blockquote),
.assistant-markdown :deep(pre),
.assistant-markdown :deep(table) {
  margin: 0;
}

.assistant-markdown :deep(h1),
.assistant-markdown :deep(h2),
.assistant-markdown :deep(h3),
.assistant-markdown :deep(h4),
.assistant-markdown :deep(h5),
.assistant-markdown :deep(h6) {
  margin: 0;
  color: #0f172a;
  line-height: 1.35;
}

.assistant-markdown :deep(h1) {
  font-size: 24px;
}

.assistant-markdown :deep(h2) {
  font-size: 20px;
}

.assistant-markdown :deep(h3) {
  font-size: 17px;
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  padding-left: 20px;
  line-height: 1.75;
}

.assistant-markdown :deep(li + li) {
  margin-top: 6px;
}

.assistant-markdown :deep(blockquote) {
  padding-left: 12px;
  border-left: 3px solid rgba(31, 78, 121, 0.2);
  color: #475569;
}

.assistant-markdown :deep(code) {
  padding: 0.14em 0.4em;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.06);
  color: #8b5e21;
  font-size: 0.92em;
}

.assistant-markdown :deep(pre) {
  overflow-x: auto;
  padding: 12px 14px;
  border-radius: 14px;
  background: #102033;
  color: #e2e8f0;
}

.assistant-markdown :deep(pre code) {
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
}

.assistant-markdown :deep(a) {
  color: #1f4e79;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.assistant-markdown :deep(table) {
  display: block;
  overflow-x: auto;
  border-collapse: collapse;
}

.assistant-markdown :deep(th),
.assistant-markdown :deep(td) {
  padding: 8px 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  text-align: left;
  white-space: nowrap;
}

.assistant-markdown :deep(th) {
  background: rgba(241, 245, 249, 0.9);
  color: #0f172a;
}

.message-meta strong {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.thinking-block {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  color: #475569;
  line-height: 1.6;
}

.pending-confirmation-block {
  display: grid;
  gap: 10px;
  margin-top: 8px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 247, 237, 0.92);
  border: 1px solid rgba(249, 115, 22, 0.2);
}

.pending-confirmation-block__title {
  margin: 0;
  font-weight: 700;
  color: #9a3412;
}

.pending-confirmation-block__desc {
  margin: 0;
  line-height: 1.7;
  color: #7c2d12;
}

.pending-confirmation-block__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #9a3412;
}

.pending-confirmation-block__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.thinking-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: #c58a1d;
  box-shadow: 0 0 0 rgba(197, 138, 29, 0.45);
  animation: pulse 1.4s infinite;
}

.thinking-elapsed {
  font-size: 12px;
  color: #64748b;
}

.scroll-to-latest-button {
  position: absolute;
  right: 18px;
  bottom: 18px;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1px solid rgba(31, 78, 121, 0.16);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: #1f4e79;
  font-size: 18px;
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.12);
  cursor: pointer;
  transition:
    transform 160ms ease,
    opacity 160ms ease,
    border-color 160ms ease,
    background 160ms ease;
}

.scroll-to-latest-button:hover {
  transform: translateY(-1px);
  border-color: rgba(31, 78, 121, 0.28);
  background: rgba(255, 255, 255, 0.98);
}

.chat-editor {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.8);
}

.chat-editor--welcome {
  padding: 14px 16px;
  background: linear-gradient(145deg, rgba(255, 248, 230, 0.62), rgba(255, 255, 255, 0.92));
}

.editor-topbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.editor-shortcut {
  color: #64748b;
  font-size: 12px;
}

.chat-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.chat-actions-primary {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.editor-action-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.78);
  color: #274865;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 160ms ease,
    border-color 160ms ease,
    background 160ms ease,
    box-shadow 160ms ease,
    opacity 160ms ease;
}

.editor-action-button:hover:not(:disabled) {
  transform: translateY(-1px);
  border-color: rgba(31, 78, 121, 0.22);
  box-shadow: 0 10px 22px rgba(15, 23, 42, 0.06);
}

.editor-action-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.editor-action-button--config {
  background: rgba(255, 255, 255, 0.64);
  color: #435669;
}

.editor-action-button--primary {
  border-color: rgba(31, 78, 121, 0.18);
  background: linear-gradient(135deg, #1f4e79, #285f8e);
  color: #f8fafc;
}

.editor-action-button--primary:hover:not(:disabled) {
  border-color: rgba(31, 78, 121, 0.3);
  background: linear-gradient(135deg, #224f7b, #2e6798);
}

.editor-action-button--secondary {
  background: rgba(244, 247, 251, 0.88);
  color: #667789;
  font-weight: 500;
}

.trace-dialog {
  display: grid;
  gap: 16px;
}

.trace-dialog-head {
  display: grid;
  gap: 14px;
}

.trace-toolbar,
.trace-head-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trace-toolbar {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.trace-filter-select {
  width: 160px;
}

.trace-summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.trace-summary-card {
  display: grid;
  gap: 6px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.82);
}

.trace-summary-card span,
.trace-summary-card small {
  color: #64748b;
  font-size: 12px;
}

.trace-summary-card strong {
  color: #0f172a;
  line-height: 1.5;
}

.trace-item {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(241, 245, 249, 0.86)),
    rgba(248, 250, 252, 0.9);
}

.trace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.trace-body {
  display: grid;
  gap: 10px;
}

.trace-description {
  margin: 0;
  color: #425466;
  line-height: 1.7;
}

.trace-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.trace-fact {
  display: grid;
  gap: 6px;
  padding: 12px;
  border-radius: 14px;
  background: rgba(241, 245, 249, 0.86);
}

.trace-fact span {
  color: #64748b;
  font-size: 12px;
}

.trace-fact strong {
  color: #0f172a;
  line-height: 1.5;
}

.segment-ref-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.segment-ref-head {
  width: 100%;
  color: #64748b;
  font-size: 12px;
}

.segment-ref-list code {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.08);
  color: #0f172a;
  font-size: 12px;
}

.trace-raw summary {
  cursor: pointer;
  color: #516172;
  font-size: 13px;
}

.trace-item pre {
  margin: 8px 0 0;
  padding: 12px;
  overflow: auto;
  border-radius: 12px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.6;
}

.empty-state {
  color: #64748b;
  line-height: 1.8;
}

@media (max-width: 1200px) {
  .chat-shell {
    grid-template-columns: 1fr;
    height: auto;
    padding: 12px;
    overflow: visible;
  }

  .chat-sidebar {
    position: static;
    height: auto;
  }

  .sidebar-panel {
    min-height: 0;
    height: auto;
    border-left: 1px solid rgba(15, 23, 42, 0.08);
    border-radius: 22px;
  }

  :deep(.sidebar-panel > .el-card__body) {
    min-height: 0;
    height: auto;
  }

  .sidebar-scroll-area {
    overflow: visible;
  }

  .chat-main {
    height: auto;
    width: 100%;
    max-width: none;
  }

  .chat-stage {
    height: auto;
    border-radius: 22px;
  }

  :deep(.chat-stage > .el-card__body) {
    height: auto;
  }

  .chat-history {
    max-height: 68vh;
    padding: 14px 24px 12px;
  }

  .message-item {
    max-width: min(86%, 760px);
  }

  .message-item--user {
    max-width: min(78%, 680px);
  }

  .message-item--assistant {
    max-width: min(88%, 820px);
  }

  .conversation-overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .section-head,
  .trace-inline-head,
  .editor-topbar,
  .chat-actions,
  .trace-head,
  .rag-attach-head,
  .settings-inline-head,
  .trace-head-main {
    flex-direction: column;
    align-items: flex-start;
  }

  .chat-actions-primary {
    width: 100%;
    justify-content: flex-start;
  }

  .editor-action-button,
  .chat-actions-primary .editor-action-button {
    width: 100%;
  }

  .chat-shell {
    padding: 8px;
  }

  .chat-history {
    padding: 12px;
  }

  .message-item {
    max-width: min(92%, 100%);
  }

  .message-item--user,
  .message-item--assistant {
    max-width: min(92%, 100%);
  }

  .sidebar-fixed {
    padding: 14px 14px 12px;
  }

  .sidebar-divider {
    margin: 0 14px;
  }

  .sidebar-scroll-area {
    padding: 10px 8px 12px 10px;
  }

  .conversation-overview-grid,
  .settings-grid,
  .settings-grid--request,
  .trace-summary-grid,
  .trace-facts {
    grid-template-columns: 1fr;
  }

  .trace-filter-select {
    width: 100%;
  }
}

@keyframes pulse {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(197, 138, 29, 0.4);
  }
  70% {
    transform: scale(1);
    box-shadow: 0 0 0 10px rgba(197, 138, 29, 0);
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(197, 138, 29, 0);
  }
}
</style>
