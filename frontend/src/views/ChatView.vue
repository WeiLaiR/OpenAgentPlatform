<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import {
  createConversation,
  listConversationMessages,
  listConversations,
  type Conversation,
  type ConversationMessage,
} from '@/api/conversation'
import {
  submitChat,
  sendChatSync,
  type ChatSendRequest,
  type StreamCompletedEvent,
  type StreamErrorEvent,
  type StreamProgressEvent,
  type StreamTokenEvent,
} from '@/api/chat'
import { RequestError } from '@/api/http'
import { listKnowledgeBases, type KnowledgeBase } from '@/api/knowledge'
import { getTraceDetail, type TraceEvent } from '@/api/trace'

const chatInput = ref('')
const chatLoading = ref(false)
const chatError = ref('')
const activeChatRequestId = ref('')
const modeState = ref({
  enableRag: false,
  enableAgent: false,
})
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
const traceFilter = ref<'ALL' | 'RAG' | 'MODEL' | 'FAILED'>('ALL')
const traceExpandedIds = ref<number[]>([])

let activeChatSource: EventSource | null = null
let activeTraceSource: EventSource | null = null
let thinkingTicker: number | null = null

const activeConversation = computed(() => {
  return conversations.value.find((item) => item.id === activeConversationId.value) ?? null
})

const selectedKnowledgeBases = computed(() => {
  return availableKnowledgeBases.value.filter((item) =>
    selectedKnowledgeBaseIds.value.includes(item.id),
  )
})

const knowledgeBaseNameMap = computed(() => {
  return new Map(availableKnowledgeBases.value.map((item) => [item.id, item.name]))
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

  const knowledgeBaseIds =
    readTraceNumericArray(ragStarted, 'knowledgeBaseIds') ??
    readTraceNumericArray(ragFinished, 'knowledgeBaseIds') ??
    (modeState.value.enableRag ? [...selectedKnowledgeBaseIds.value] : [])
  const retrievedCount =
    readTraceNumber(ragSelected, 'count') ?? readTraceNumber(ragFinished, 'count') ?? 0
  const ragSnippetCount = readTraceNumber(modelStarted, 'ragSnippetCount') ?? retrievedCount

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
  }
})

async function loadConversations(preserveActive = true) {
  const list = await listConversations()
  conversations.value = list

  if (preserveActive && activeConversationId.value) {
    const exists = list.some((item) => item.id === activeConversationId.value)
    if (exists) {
      return
    }
  }

  const firstConversation = list.length > 0 ? list[0]! : null
  if (firstConversation) {
    await selectConversation(firstConversation.id)
    return
  }

  activeConversationId.value = null
  messages.value = []
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
  } catch (error) {
    availableKnowledgeBases.value = []
    selectedKnowledgeBaseIds.value = []
    knowledgeError.value =
      error instanceof RequestError ? error.message : '知识库列表加载失败，请稍后重试。'
  } finally {
    knowledgeLoading.value = false
  }
}

async function createNewConversation() {
  const conversation = await createConversation({
    title: '新会话',
    enableRag: modeState.value.enableRag,
    enableAgent: modeState.value.enableAgent,
  })
  conversations.value = [conversation, ...conversations.value]
  await selectConversation(conversation.id)
}

async function selectConversation(conversationId: number) {
  activeConversationId.value = conversationId
  activeChatRequestId.value = ''
  traces.value = []
  traceError.value = ''
  closeStreams()

  const conversation = conversations.value.find((item) => item.id === conversationId)
  if (conversation) {
    modeState.value.enableRag = conversation.enableRag
    modeState.value.enableAgent = conversation.enableAgent
  }

  messages.value = normalizeMessages(await listConversationMessages(conversationId))
}

async function handleSendSync() {
  const message = chatInput.value.trim()
  if (!message || chatLoading.value || !validateBeforeSend()) {
    return
  }

  chatLoading.value = true
  chatError.value = ''
  traceError.value = ''

  try {
    const answer = await sendChatSync(buildChatRequest(message))
    activeChatRequestId.value = answer.requestId ?? ''
    chatInput.value = ''
    await refreshAfterRound(answer.conversationId, answer.requestId ?? null)
  } catch (error) {
    chatError.value = error instanceof RequestError ? error.message : '同步聊天调用失败。'
  } finally {
    chatLoading.value = false
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
  traces.value = []
  closeStreams()

  const optimisticUserMessageId = -Date.now()
  const optimisticAssistantMessageId = -(Date.now() + 1)
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

  try {
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
    await chatStreamPromise
    await refreshAfterRound(accepted.conversationId, accepted.requestId)
  } catch (error) {
    removeOptimisticMessages([optimisticUserMessageId, optimisticAssistantMessageId])
    chatError.value = error instanceof RequestError ? error.message : '聊天请求提交失败。'
  } finally {
    chatLoading.value = false
  }
}

function validateBeforeSend() {
  if (modeState.value.enableRag && selectedKnowledgeBaseIds.value.length === 0) {
    chatError.value = '已开启 RAG，请至少选择一个知识库后再发送。'
    return false
  }

  return true
}

function buildChatRequest(message: string): ChatSendRequest {
  return {
    conversationId: activeConversationId.value ?? undefined,
    message,
    enableRag: modeState.value.enableRag,
    enableAgent: modeState.value.enableAgent,
    knowledgeBaseIds: modeState.value.enableRag ? selectedKnowledgeBaseIds.value : [],
  }
}

function openChatStream(streamRequestId: string, assistantMessageId: number): Promise<void> {
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
      completeAssistantMessage(assistantMessageId, streamRequestId, payload.answer)
      activeChatSource?.close()
      activeChatSource = null
      resolve()
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
    return
  }

  messages.value = [
    ...messages.value,
    {
      id: -Date.now(),
      conversationId: activeConversationId.value ?? 0,
      roleCode: 'ASSISTANT',
      messageType: 'TEXT',
      content,
      requestId: streamRequestId,
      finishReason: null,
      createdAt: Date.now(),
      uiState: 'streaming',
    },
  ]
}

function completeAssistantMessage(assistantMessageId: number, streamRequestId: string, answer: string) {
  const target = messages.value.find((item) => item.id === assistantMessageId)
  if (target) {
    target.requestId = streamRequestId
    target.content = answer
    target.finishReason = 'stop'
    target.uiState = 'done'
  }
}

function markAssistantMessageError(assistantMessageId: number, streamRequestId: string) {
  const target = messages.value.find((item) => item.id === assistantMessageId)
  if (target && !target.content) {
    target.requestId = streamRequestId
    target.content = '请求失败。'
    target.uiState = 'error'
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
  target.requestId = streamRequestId
  target.uiState = 'thinking'
  target.progressMessage = payload.message
  if (!target.thinkingStartedAt) {
    target.thinkingStartedAt = Date.now() - payload.elapsedMillis
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

function normalizeMessages(records: ConversationMessage[]) {
  return records.map((item) => ({
    ...item,
    uiState: item.roleCode === 'ASSISTANT' ? ('done' as const) : undefined,
  }))
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
  return trace.eventType
}

function traceDescription(trace: TraceEvent) {
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
    ]
  }

  if (trace.eventType === 'FINAL_RESPONSE_COMPLETED') {
    return [
      { label: '回答长度', value: formatScalar(payload.answerLength) },
      { label: '耗时', value: trace.costMillis ? `${trace.costMillis} ms` : '未记录' },
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
    'RAG_RETRIEVAL_STARTED',
    'RAG_SEGMENTS_SELECTED',
    'RAG_RETRIEVAL_FINISHED',
    'MODEL_REQUEST_STARTED',
    'FINAL_RESPONSE_COMPLETED',
  ].includes(trace.eventType)
}

function isModelTrace(trace: TraceEvent) {
  return trace.eventType.startsWith('MODEL_') || trace.eventType.startsWith('FINAL_RESPONSE_')
}

function isLowValueTrace(trace: TraceEvent) {
  return trace.successFlag && !isStructuredTrace(trace)
}

function shouldExpandTraceByDefault(trace: TraceEvent) {
  return !isLowValueTrace(trace)
}

function matchesTraceFilter(trace: TraceEvent) {
  if (traceFilter.value === 'RAG') {
    return trace.eventType.startsWith('RAG_')
  }
  if (traceFilter.value === 'MODEL') {
    return isModelTrace(trace)
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

function formatSegmentRefCount(value: unknown) {
  if (!Array.isArray(value)) {
    return '0'
  }
  return String(value.length)
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

onMounted(async () => {
  thinkingTicker = window.setInterval(() => {
    thinkingRenderNow.value = Date.now()
  }, 1000)
  await Promise.all([loadConversations(false), loadKnowledgeOptions()])
})

onBeforeUnmount(() => {
  if (thinkingTicker !== null) {
    window.clearInterval(thinkingTicker)
  }
  closeStreams()
})
</script>

<template>
  <section class="workspace-grid">
    <el-card class="panel conversations-panel" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">会话</p>
            <h2>Conversation</h2>
          </div>
          <el-button type="primary" @click="createNewConversation">新建会话</el-button>
        </div>
      </template>

      <div v-if="conversations.length === 0" class="empty-state">
        还没有会话。可以先新建一个，或者直接发送第一条消息让后端自动创建。
      </div>

      <button
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conversation-item"
        :class="{ 'conversation-item--active': conversation.id === activeConversationId }"
        type="button"
        @click="selectConversation(conversation.id)"
      >
        <strong>{{ conversation.title }}</strong>
        <span>{{ conversation.modeCode }}</span>
        <span>{{ formatTime(conversation.lastMessageAt ?? conversation.updatedAt) }}</span>
      </button>
    </el-card>

    <el-card class="panel chat-panel" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">对话主链路</p>
            <h2>{{ activeConversation?.title ?? '未选择会话' }}</h2>
          </div>
          <span class="meta-inline">
            {{ activeConversation ? `会话 #${activeConversation.id}` : '可直接发送首条消息自动创建' }}
          </span>
        </div>
      </template>

      <div class="chat-toolbar">
        <el-switch v-model="modeState.enableRag" active-text="RAG" />
        <el-switch v-model="modeState.enableAgent" active-text="Agent" />
      </div>

      <div class="rag-attach-panel">
        <div class="rag-attach-head">
          <strong>本次请求知识库</strong>
          <span>当前只做请求级绑定，不写回 conversation。</span>
        </div>
        <el-select
          v-model="selectedKnowledgeBaseIds"
          :disabled="!modeState.enableRag || knowledgeLoading"
          clearable
          collapse-tags
          collapse-tags-tooltip
          filterable
          multiple
          placeholder="开启 RAG 后选择一个或多个知识库"
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
        <el-alert
          v-if="knowledgeError"
          :closable="false"
          show-icon
          title="知识库列表加载失败"
          type="error"
        >
          <template #default>{{ knowledgeError }}</template>
        </el-alert>
        <p v-else-if="availableKnowledgeBases.length === 0" class="hint-text">
          当前还没有可选知识库。可以去 <RouterLink to="/knowledge">知识库页面</RouterLink> 创建后再回来。
        </p>
      </div>

      <el-alert v-if="chatError" :closable="false" show-icon title="聊天请求失败" type="error">
        <template #default>{{ chatError }}</template>
      </el-alert>

      <div class="chat-history">
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
          <p v-if="message.content">{{ message.content }}</p>
          <div v-else-if="message.roleCode === 'ASSISTANT' && message.uiState === 'thinking'" class="thinking-block">
            <span class="thinking-dot"></span>
            <span>{{ message.progressMessage || '模型正在思考，请稍候…' }}</span>
            <span class="thinking-elapsed">{{ formatThinkingElapsed(message.thinkingStartedAt) }}</span>
          </div>
          <p v-else>...</p>
        </article>
      </div>

      <div class="chat-editor">
        <el-input
          v-model="chatInput"
          :autosize="{ minRows: 4, maxRows: 8 }"
          :disabled="chatLoading"
          maxlength="20000"
          placeholder="输入一条消息，验证会话、Trace 与 RAG 上下文注入。"
          resize="none"
          show-word-limit
          type="textarea"
        />

        <div class="meta-row">
          <span>聊天 requestId：{{ activeChatRequestId || '未发送' }}</span>
          <span>已选知识库：{{ selectedKnowledgeBaseIds.length }}</span>
        </div>

        <div class="chat-actions">
          <el-button :disabled="chatLoading" @click="handleSendSync">同步发送</el-button>
          <el-button :loading="chatLoading" type="primary" @click="handleSend">流式发送</el-button>
        </div>
      </div>
    </el-card>

    <el-card class="panel trace-panel" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">Trace</p>
            <h2>Request Timeline</h2>
          </div>
          <div class="trace-toolbar">
            <el-select v-model="traceFilter" class="trace-filter-select">
              <el-option label="全部事件" value="ALL" />
              <el-option label="只看 RAG" value="RAG" />
              <el-option label="只看 MODEL" value="MODEL" />
              <el-option label="只看失败" value="FAILED" />
            </el-select>
            <el-button link @click="applyDefaultTraceExpansion">按默认折叠</el-button>
            <el-button link @click="expandAllTraces">展开全部</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="traceError" :closable="false" show-icon title="Trace 订阅失败" type="error">
        <template #default>{{ traceError }}</template>
      </el-alert>

      <div class="trace-summary-grid">
        <div class="trace-summary-card">
          <span>当前 requestId</span>
          <strong>{{ currentRoundSummary.requestId || '未发送' }}</strong>
        </div>
        <div class="trace-summary-card">
          <span>选中知识库</span>
          <strong>{{ currentRoundSummary.knowledgeBaseText }}</strong>
        </div>
        <div class="trace-summary-card">
          <span>召回片段数</span>
          <strong>{{ currentRoundSummary.retrievedCount }}</strong>
        </div>
        <div class="trace-summary-card">
          <span>RAG 状态</span>
          <strong>{{ currentRoundSummary.ragStatus }}</strong>
          <small>{{ currentRoundSummary.requestStatus }}</small>
        </div>
      </div>

      <div v-if="traces.length === 0" class="empty-state">
        当前还没有 trace 事件。发送消息后，这里会展示 `USER_MESSAGE_RECEIVED`、
        `RAG_RETRIEVAL_STARTED`、`RAG_SEGMENTS_SELECTED`、`FINAL_RESPONSE_COMPLETED` 等过程事件。
      </div>

      <div v-else-if="filteredTraces.length === 0" class="empty-state">
        当前筛选条件下没有匹配的 Trace 事件，可以切换过滤条件查看完整时间线。
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
    </el-card>
  </section>
</template>

<style scoped>
.workspace-grid {
  display: grid;
  gap: 20px;
  grid-template-columns: minmax(240px, 0.85fr) minmax(0, 1.4fr) minmax(320px, 1fr);
  align-items: start;
}

.panel {
  min-height: 640px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
}

.conversations-panel {
  background:
    linear-gradient(180deg, rgba(246, 249, 252, 0.96), rgba(255, 255, 255, 0.88)),
    rgba(255, 255, 255, 0.86);
}

.chat-panel {
  background:
    linear-gradient(180deg, rgba(255, 252, 244, 0.96), rgba(255, 255, 255, 0.9)),
    rgba(255, 255, 255, 0.86);
}

.trace-panel {
  background:
    linear-gradient(180deg, rgba(241, 247, 255, 0.96), rgba(255, 255, 255, 0.9)),
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
  font-size: 24px;
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
.message-meta,
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  color: #64748b;
  font-size: 13px;
}

.conversations-panel,
.trace-panel,
.chat-panel {
  display: grid;
  gap: 12px;
}

.conversation-item {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 14px 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 16px;
  background: rgba(248, 250, 252, 0.9);
  text-align: left;
  cursor: pointer;
  transition:
    transform 160ms ease,
    border-color 160ms ease;
}

.conversation-item:hover,
.conversation-item--active {
  transform: translateY(-1px);
  border-color: rgba(31, 78, 121, 0.45);
  background:
    linear-gradient(135deg, rgba(255, 248, 230, 0.9), rgba(255, 255, 255, 0.96)),
    rgba(248, 250, 252, 0.9);
  box-shadow: 0 14px 28px rgba(15, 23, 42, 0.08);
}

.conversation-item strong {
  color: #0f172a;
}

.conversation-item span {
  color: #64748b;
  font-size: 13px;
}

.chat-toolbar,
.chat-actions {
  display: flex;
  gap: 12px;
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
  gap: 14px;
  min-height: 400px;
  padding: 18px;
  margin: 16px 0;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.94), rgba(241, 245, 249, 0.72));
}

.message-item {
  max-width: min(100%, 760px);
  padding: 16px 18px;
  border-radius: 18px;
  background: white;
}

.message-item--user {
  justify-self: end;
  background: #1f4e79;
  color: #f8fafc;
}

.message-item--assistant {
  justify-self: start;
  border: 1px solid rgba(15, 23, 42, 0.08);
}

.message-item p {
  margin: 8px 0 0;
  white-space: pre-wrap;
  line-height: 1.75;
}

.thinking-block {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  color: #475569;
  line-height: 1.6;
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

.chat-editor {
  display: grid;
  gap: 12px;
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
  .workspace-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .section-head,
  .chat-actions,
  .trace-head,
  .rag-attach-head,
  .trace-head-main {
    flex-direction: column;
    align-items: flex-start;
  }

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
