<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

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
import { getSystemHealth, type SystemHealth } from '@/api/system'
import { getTraceDetail, type TraceEvent } from '@/api/trace'

const health = ref<SystemHealth | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const requestId = ref('')
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

let activeChatSource: EventSource | null = null
let activeTraceSource: EventSource | null = null
let thinkingTicker: number | null = null

const activeConversation = computed(() => {
  return conversations.value.find((item) => item.id === activeConversationId.value) ?? null
})

const statusItems = computed(() => {
  if (!health.value) {
    return []
  }

  return [
    { label: '应用状态', value: health.value.appStatus },
    { label: 'MySQL', value: health.value.mysqlStatus },
    { label: 'Milvus', value: health.value.milvusStatus },
    { label: 'ChatModel', value: health.value.chatModelStatus },
    { label: 'EmbeddingModel', value: health.value.embeddingModelStatus },
    { label: '可用 MCP Server 数', value: String(health.value.healthyMcpServers) },
  ]
})

const lastCheckedAt = computed(() => {
  if (!health.value) {
    return '未检查'
  }

  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(health.value.timestamp)
})

async function loadHealth() {
  loading.value = true
  errorMessage.value = ''

  try {
    const response = await getSystemHealth()
    health.value = response.data
    requestId.value = response.requestId
  } catch (error) {
    health.value = null
    requestId.value = ''
    errorMessage.value =
      error instanceof RequestError ? error.message : '系统健康检查失败，请确认后端是否启动。'
  } finally {
    loading.value = false
  }
}

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
  if (!message || chatLoading.value) {
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
  if (!message || chatLoading.value) {
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

function buildChatRequest(message: string): ChatSendRequest {
  return {
    conversationId: activeConversationId.value ?? undefined,
    message,
    enableRag: modeState.value.enableRag,
    enableAgent: modeState.value.enableAgent,
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
  }
}

function resolveTagType(status: string): '' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'UP') {
    return 'success'
  }

  if (status === 'DOWN') {
    return 'danger'
  }

  if (status === 'NOT_CONFIGURED') {
    return 'info'
  }

  return 'warning'
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

onMounted(async () => {
  thinkingTicker = window.setInterval(() => {
    thinkingRenderNow.value = Date.now()
  }, 1000)
  await Promise.all([loadHealth(), loadConversations(false)])
})

onBeforeUnmount(() => {
  if (thinkingTicker !== null) {
    window.clearInterval(thinkingTicker)
  }
  closeStreams()
})
</script>

<template>
  <section class="top-grid">
    <el-card class="summary-card" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">阶段三</p>
            <h2>会话、消息、Trace 已进入主链路</h2>
          </div>
          <el-button :loading="loading" type="primary" @click="loadHealth">刷新健康检查</el-button>
        </div>
      </template>

      <p class="lead">
        当前页面不再只是聊天联调面板，而是最小平台形态：左侧会话列表，中间聊天，右侧请求级
        Trace。下一步就可以在这个骨架上继续接 RAG 和 Agent。
      </p>

      <div v-if="errorMessage" class="alert-wrap">
        <el-alert :closable="false" show-icon title="健康检查失败" type="error">
          <template #default>{{ errorMessage }}</template>
        </el-alert>
      </div>

      <div v-else-if="health" class="status-grid">
        <div v-for="item in statusItems" :key="item.label" class="status-item">
          <span class="status-label">{{ item.label }}</span>
          <el-tag :type="resolveTagType(item.value)">{{ item.value }}</el-tag>
        </div>
      </div>

      <div class="meta-row">
        <span>最近检查：{{ lastCheckedAt }}</span>
        <span>HTTP requestId：{{ requestId || '未生成' }}</span>
        <span>聊天 requestId：{{ activeChatRequestId || '未发送' }}</span>
      </div>
    </el-card>
  </section>

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
            <p class="section-kicker">聊天</p>
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

      <el-alert v-if="chatError" :closable="false" show-icon title="聊天请求失败" type="error">
        <template #default>{{ chatError }}</template>
      </el-alert>

      <div class="chat-history">
        <div v-if="messages.length === 0" class="empty-state">
          当前会话还没有消息。输入一条内容后，后端会把 user / assistant 消息持久化到
          `conversation_message`。
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
          placeholder="输入一条消息，验证会话持久化、流式输出和 Trace 双通道。"
          resize="none"
          show-word-limit
          type="textarea"
        />

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
        </div>
      </template>

      <el-alert v-if="traceError" :closable="false" show-icon title="Trace 订阅失败" type="error">
        <template #default>{{ traceError }}</template>
      </el-alert>

      <div v-if="traces.length === 0" class="empty-state">
        当前还没有 trace 事件。发送消息后，这里会展示 `USER_MESSAGE_RECEIVED`、
        `MODEL_REQUEST_STARTED`、`FINAL_RESPONSE_COMPLETED` 等过程事件。
      </div>

      <article v-for="trace in traces" :key="trace.id" class="trace-item">
        <div class="trace-head">
          <strong>{{ trace.eventType }}</strong>
          <span>{{ formatTime(trace.createdAt) }}</span>
        </div>
        <div class="trace-meta">
          <span>{{ trace.eventStage || 'NO_STAGE' }}</span>
          <span>{{ trace.eventSource }}</span>
          <span>{{ trace.successFlag ? 'SUCCESS' : 'FAILED' }}</span>
        </div>
        <pre>{{ formatTracePayload(trace.eventPayloadJson) }}</pre>
      </article>
    </el-card>
  </section>
</template>

<style scoped>
.top-grid,
.workspace-grid {
  display: grid;
  gap: 20px;
}

.summary-card,
.panel {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
}

.summary-card {
  background:
    linear-gradient(135deg, rgba(255, 246, 230, 0.95), rgba(255, 255, 255, 0.82)),
    rgba(255, 255, 255, 0.86);
}

.workspace-grid {
  grid-template-columns: minmax(240px, 0.85fr) minmax(0, 1.4fr) minmax(320px, 1fr);
  align-items: start;
}

.panel {
  min-height: 640px;
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

.lead {
  margin: 0 0 20px;
  line-height: 1.75;
  color: #425466;
}

.alert-wrap {
  margin-bottom: 16px;
}

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.status-item {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.74);
}

.status-label {
  color: #475569;
}

.meta-row,
.trace-meta,
.message-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  color: #64748b;
  font-size: 13px;
}

.meta-inline {
  color: #64748b;
  font-size: 13px;
}

.conversations-panel,
.trace-panel {
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

.chat-toolbar {
  margin-bottom: 16px;
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

.trace-item pre {
  margin: 0;
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

  .status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 768px) {
  .section-head,
  .chat-actions,
  .trace-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .status-grid {
    grid-template-columns: 1fr;
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
