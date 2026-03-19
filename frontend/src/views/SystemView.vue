<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { RequestError } from '@/api/http'
import { getSystemHealth, type SystemHealth } from '@/api/system'

const health = ref<SystemHealth | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const requestId = ref('')

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

onMounted(loadHealth)
</script>

<template>
  <section class="system-grid">
    <el-card class="hero-card" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">系统概览</p>
            <h2>运行时状态与开发入口</h2>
          </div>
          <el-button :loading="loading" type="primary" @click="loadHealth">刷新健康检查</el-button>
        </div>
      </template>

      <p class="lead">
        当前前端已经从单页联调面板拆成独立页面：系统页负责查看运行状态，对话页负责验证会话、
        Trace 与 RAG 主链路，知识库页负责文件级管理与检索测试。
      </p>

      <div class="jump-grid">
        <RouterLink class="jump-card" to="/chat">
          <strong>进入对话页</strong>
          <span>查看会话、流式响应、RAG 检索事件和 Trace 时间线。</span>
        </RouterLink>
        <RouterLink class="jump-card" to="/knowledge">
          <strong>进入知识库页</strong>
          <span>创建知识库、上传文件、触发索引并做检索测试。</span>
        </RouterLink>
        <RouterLink class="jump-card" to="/mcp">
          <strong>进入 MCP 页</strong>
          <span>管理 MCP Server、测试连通性并同步可用工具快照。</span>
        </RouterLink>
      </div>
    </el-card>

    <el-card class="status-card" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">健康检查</p>
            <h2>System Health</h2>
          </div>
          <span class="meta-inline">最后刷新：{{ lastCheckedAt }}</span>
        </div>
      </template>

      <el-alert v-if="errorMessage" :closable="false" show-icon title="健康检查失败" type="error">
        <template #default>{{ errorMessage }}</template>
      </el-alert>

      <div v-else-if="health" class="status-grid">
        <div v-for="item in statusItems" :key="item.label" class="status-item">
          <span class="status-label">{{ item.label }}</span>
          <el-tag :type="resolveTagType(item.value)">{{ item.value }}</el-tag>
        </div>
      </div>

      <div class="meta-row">
        <span>requestId：{{ requestId || '未生成' }}</span>
        <span>Milvus database：openagent</span>
        <span>Milvus collection：knowledge_segment</span>
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.system-grid {
  display: grid;
  gap: 20px;
}

.hero-card,
.status-card {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
}

.hero-card {
  background:
    linear-gradient(135deg, rgba(255, 246, 230, 0.95), rgba(255, 255, 255, 0.82)),
    rgba(255, 255, 255, 0.86);
}

.status-card {
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

.jump-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.jump-card {
  display: grid;
  gap: 8px;
  padding: 18px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.74);
  transition:
    transform 160ms ease,
    border-color 160ms ease;
}

.jump-card:hover {
  transform: translateY(-1px);
  border-color: rgba(31, 78, 121, 0.45);
}

.jump-card strong {
  color: #0f172a;
}

.jump-card span,
.meta-inline,
.meta-row {
  color: #64748b;
  font-size: 13px;
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

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-top: 16px;
}

@media (max-width: 960px) {
  .jump-grid,
  .status-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .section-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
