<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  submitChat,
  sendChatSync,
  type ChatSendRequest,
  type StreamCompletedEvent,
  type StreamErrorEvent,
  type StreamTokenEvent,
} from '@/api/chat'
import { getSystemHealth, type SystemHealth } from '@/api/system'
import { RequestError } from '@/api/http'

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
const messages = ref<Array<{ id: string; role: 'user' | 'assistant'; content: string; state?: string }>>([])

const modeCards = [
  {
    title: 'LLM',
    description: '先跑通最小问答闭环，确认前后端接口、requestId 和流式入口都成立。',
  },
  {
    title: 'RAG',
    description: '在聊天入口稳定后接入知识库索引、检索和上下文注入。',
  },
  {
    title: 'Agent',
    description: '把 MCP Server 与 Tool snapshot 纳入可配置能力，再交给模型调度。',
  },
  {
    title: 'RAG + Agent',
    description: '最后做统一编排，让检索和工具调用进入同一条主链路。',
  },
]

async function handleSendSync() {
  const message = chatInput.value.trim()
  if (!message || chatLoading.value) {
    return
  }

  chatLoading.value = true
  chatError.value = ''

  try {
    const answer = await sendChatSync(buildChatRequest(message))
    messages.value.push(
      { id: crypto.randomUUID(), role: 'user', content: message },
      { id: crypto.randomUUID(), role: 'assistant', content: answer.answer, state: 'done' },
    )
    chatInput.value = ''
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

  const userMessageId = crypto.randomUUID()
  const assistantMessageId = crypto.randomUUID()

  messages.value.push(
    { id: userMessageId, role: 'user', content: message },
    { id: assistantMessageId, role: 'assistant', content: '', state: 'streaming' },
  )

  try {
    const accepted = await submitChat(buildChatRequest(message))
    activeChatRequestId.value = accepted.requestId
    chatInput.value = ''
    await openStream(accepted.requestId, assistantMessageId)
  } catch (error) {
    removeMessageById(userMessageId)
    removeMessageById(assistantMessageId)
    chatLoading.value = false
    chatError.value = error instanceof RequestError ? error.message : '聊天请求提交失败。'
  }
}

function buildChatRequest(message: string): ChatSendRequest {
  return {
    message,
    enableRag: modeState.value.enableRag,
    enableAgent: modeState.value.enableAgent,
  }
}

function openStream(streamRequestId: string, assistantMessageId: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const eventSource = new EventSource(`/api/v1/chat/stream/${streamRequestId}`)

    eventSource.addEventListener('token', (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as StreamTokenEvent
      updateAssistantMessage(assistantMessageId, (current) => current + payload.content, 'streaming')
    })

    eventSource.addEventListener('message_end', (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as StreamCompletedEvent
      updateAssistantMessage(assistantMessageId, () => payload.answer, 'done')
      activeChatRequestId.value = payload.requestId
      chatLoading.value = false
      eventSource.close()
      resolve()
    })

    eventSource.addEventListener('error', (event) => {
      if ((event as MessageEvent).data) {
        const payload = JSON.parse((event as MessageEvent).data) as StreamErrorEvent
        chatError.value = payload.message
      } else {
        chatError.value = '流式连接已中断。'
      }
      updateAssistantMessage(assistantMessageId, (current) => current || '请求失败。', 'error')
      chatLoading.value = false
      eventSource.close()
      reject(new Error(chatError.value))
    })
  })
}

function removeMessageById(id: string) {
  messages.value = messages.value.filter((item) => item.id !== id)
}

function updateAssistantMessage(id: string, updater: (current: string) => string, state: string) {
  const target = messages.value.find((item) => item.id === id)
  if (!target) {
    return
  }
  target.content = updater(target.content)
  target.state = state
}

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

onMounted(() => {
  void loadHealth()
})
</script>

<template>
  <section class="hero-grid">
    <el-card class="hero-card hero-card--intro" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">阶段一骨架</p>
            <h2>项目已经可以正式进入开发</h2>
          </div>
          <el-tag type="warning">当前起步面：基础工程 + 健康检查</el-tag>
        </div>
      </template>

      <p class="lead">
        `docs/` 已经把技术基线、模式编排、接口形态和阶段顺序定义清楚了。现在缺的不是继续写方案，而是开始把第一阶段骨架做成可验证的代码闭环。
      </p>

      <div class="mode-grid">
        <article v-for="mode in modeCards" :key="mode.title" class="mode-item">
          <h3>{{ mode.title }}</h3>
          <p>{{ mode.description }}</p>
        </article>
      </div>
    </el-card>

    <el-card class="hero-card hero-card--health" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">后端连通性</p>
            <h2>系统健康检查</h2>
          </div>
          <el-button :loading="loading" type="primary" @click="loadHealth">重新检查</el-button>
        </div>
      </template>

      <el-alert
        v-if="errorMessage"
        :closable="false"
        show-icon
        title="健康检查调用失败"
        type="error"
      >
        <template #default>{{ errorMessage }}</template>
      </el-alert>

      <template v-else-if="health">
        <div class="meta-row">
          <span>最近检查时间：{{ lastCheckedAt }}</span>
          <span>requestId：{{ requestId }}</span>
        </div>

        <div class="status-grid">
          <div v-for="item in statusItems" :key="item.label" class="status-item">
            <span class="status-label">{{ item.label }}</span>
            <el-tag :type="resolveTagType(item.value)">{{ item.value }}</el-tag>
          </div>
        </div>
      </template>

      <el-skeleton v-else :rows="6" animated />
    </el-card>
  </section>

  <section class="next-grid">
    <el-card shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">当前已落地</p>
            <h2>第一批基础能力</h2>
          </div>
        </div>
      </template>

      <ul class="plain-list">
        <li>后端统一返回结构 `ApiResponse` 与错误结构 `ApiError`。</li>
        <li>后端请求级 `requestId` 生成与透传，便于后续 SSE 与 Trace 对齐。</li>
        <li>系统健康检查接口 `/api/v1/system/health` 可直接被前端调用。</li>
        <li>前端基础控制台、路由和请求封装已具备继续扩展的落点。</li>
      </ul>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">下一步建议</p>
            <h2>按文档顺序继续推进</h2>
          </div>
        </div>
      </template>

      <ol class="plain-list">
        <li>接入 LangChain4j `ChatModel` 与 `StreamingChatModel`，开始做最小聊天闭环。</li>
        <li>补 `conversation` 和 `conversation_message`，让聊天从一次性请求变成有状态会话。</li>
        <li>再引入 SSE 双通道，为后续 RAG、Agent 和 Trace 留稳定入口。</li>
      </ol>
    </el-card>
  </section>

  <section class="chat-section">
    <el-card shadow="never" class="chat-card">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">阶段二起点</p>
            <h2>最小聊天闭环</h2>
          </div>
          <span class="meta-inline">当前 requestId：{{ activeChatRequestId || '尚未发送' }}</span>
        </div>
      </template>

      <div class="chat-toolbar">
        <el-switch v-model="modeState.enableRag" active-text="RAG" />
        <el-switch v-model="modeState.enableAgent" active-text="Agent" />
      </div>

      <el-alert
        v-if="chatError"
        :closable="false"
        show-icon
        title="聊天请求失败"
        type="error"
      >
        <template #default>{{ chatError }}</template>
      </el-alert>

      <div class="chat-history">
        <div v-if="messages.length === 0" class="chat-empty">
          现在已经能直接调用后端聊天接口。先配置 `OPENAGENT_CHAT_BASE_URL`、`OPENAGENT_CHAT_API_KEY`、
          `OPENAGENT_CHAT_MODEL_NAME`，再从这里开始联调。
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message-item"
          :class="`message-item--${message.role}`"
        >
          <div class="message-meta">
            <strong>{{ message.role === 'user' ? 'User' : 'Assistant' }}</strong>
            <span v-if="message.state">{{ message.state }}</span>
          </div>
          <p>{{ message.content || '...' }}</p>
        </article>
      </div>

      <div class="chat-editor">
        <el-input
          v-model="chatInput"
          :autosize="{ minRows: 4, maxRows: 8 }"
          :disabled="chatLoading"
          maxlength="20000"
          placeholder="输入一条消息，验证 LangChain4j ChatModel 和 StreamingChatModel 接入。"
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
  </section>
</template>

<style scoped>
.hero-grid,
.next-grid,
.chat-section {
  display: grid;
  gap: 20px;
}

.hero-grid {
  grid-template-columns: minmax(0, 1.4fr) minmax(320px, 0.9fr);
  margin-bottom: 20px;
}

.next-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.hero-card {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(12px);
}

.hero-card--intro {
  background:
    linear-gradient(135deg, rgba(255, 248, 230, 0.95), rgba(255, 255, 255, 0.78)),
    rgba(255, 255, 255, 0.8);
}

.hero-card--health {
  background:
    linear-gradient(180deg, rgba(236, 245, 255, 0.92), rgba(255, 255, 255, 0.8)),
    rgba(255, 255, 255, 0.8);
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
  color: #7c5b27;
}

.lead {
  margin: 0 0 20px;
  font-size: 16px;
  line-height: 1.75;
  color: #3d4d63;
}

.mode-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.mode-item {
  padding: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.72);
}

.mode-item h3,
.status-label {
  margin: 0 0 8px;
}

.mode-item p {
  margin: 0;
  line-height: 1.7;
  color: #4b5563;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
  margin-bottom: 18px;
  color: #4b5563;
}

.status-grid {
  display: grid;
  gap: 12px;
}

.status-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
}

.plain-list {
  margin: 0;
  padding-left: 20px;
  color: #374151;
  line-height: 1.9;
}

.chat-card {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(12px);
}

.meta-inline {
  color: #6b7280;
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
  min-height: 220px;
  padding: 18px;
  margin: 16px 0;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.9), rgba(241, 245, 249, 0.7));
}

.chat-empty {
  color: #64748b;
  line-height: 1.8;
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

.message-meta {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 12px;
  opacity: 0.75;
}

.message-item p {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.75;
}

.chat-editor {
  display: grid;
  gap: 12px;
}

@media (max-width: 960px) {
  .hero-grid,
  .next-grid,
  .mode-grid {
    grid-template-columns: 1fr;
  }

  .section-head,
  .status-item,
  .chat-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
