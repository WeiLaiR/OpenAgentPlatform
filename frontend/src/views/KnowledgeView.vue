<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { RequestError } from '@/api/http'
import {
  createKnowledgeBase,
  indexKnowledgeFile,
  listKnowledgeSegments,
  listKnowledgeBases,
  listKnowledgeFiles,
  retrieveKnowledge,
  uploadKnowledgeFile,
  type KnowledgeBase,
  type KnowledgeFile,
  type KnowledgeSegment,
  type KnowledgeRetrieveResult,
  type RagSnippet,
} from '@/api/knowledge'

const knowledgeBases = ref<KnowledgeBase[]>([])
const knowledgeFiles = ref<KnowledgeFile[]>([])
const knowledgeSegments = ref<KnowledgeSegment[]>([])
const selectedKnowledgeBaseId = ref<number | null>(null)
const selectedSegmentFileId = ref<number | null>(null)
const loadingKnowledgeBases = ref(false)
const loadingFiles = ref(false)
const loadingSegments = ref(false)
const creatingKnowledgeBase = ref(false)
const uploadingFile = ref(false)
const retrieving = ref(false)
const knowledgeError = ref('')
const fileError = ref('')
const segmentError = ref('')
const retrieveError = ref('')
const createDialogVisible = ref(false)
const selectedUploadFile = ref<File | null>(null)
const retrieveResult = ref<KnowledgeRetrieveResult | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const segmentPanelRef = ref<HTMLElement | null>(null)
const pendingIndexRequestIds = ref<number[]>([])
const autoRefreshEnabled = ref(true)
const segmentKeyword = ref('')
const expandedSegmentKeys = ref<string[]>([])
const fileFilters = reactive({
  keyword: '',
  parseStatus: 'ALL',
  indexStatus: 'ALL',
})
const fileSort = reactive({
  field: 'updatedAt' as 'updatedAt' | 'fileName' | 'parseStatus' | 'indexStatus',
  order: 'DESC' as 'ASC' | 'DESC',
})
const expandedSnippetKeys = ref<string[]>([])

let fileRefreshTimer: number | null = null
const parseStatusSortWeight: Record<string, number> = {
  UPLOADED: 0,
  PARSING: 1,
  PARSED: 2,
  FAILED: 3,
}
const indexStatusSortWeight: Record<string, number> = {
  PENDING: 0,
  INDEXING: 1,
  INDEXED: 2,
  FAILED: 3,
}
const DEFAULT_PARSER_STRATEGY = 'TIKA'
const DEFAULT_CHUNK_STRATEGY = 'DEFAULT'
const DEFAULT_CHUNK_SIZE = 1000
const DEFAULT_CHUNK_OVERLAP = 150
const DEFAULT_RETRIEVE_TOP_K = 4
const DEFAULT_RETRIEVE_MIN_SCORE = 0.55
const DEFAULT_SEGMENT_LIMIT = 300

const createForm = reactive({
  name: '',
  description: '',
  embeddingModelName: '',
  embeddingDimension: undefined as number | undefined,
  parserStrategy: '',
  chunkStrategy: '',
  chunkSize: DEFAULT_CHUNK_SIZE,
  chunkOverlap: DEFAULT_CHUNK_OVERLAP,
})

const retrieveForm = reactive({
  query: '',
  knowledgeBaseIds: [] as number[],
  topK: DEFAULT_RETRIEVE_TOP_K,
  minScore: DEFAULT_RETRIEVE_MIN_SCORE,
})

const activeKnowledgeBase = computed(() => {
  return knowledgeBases.value.find((item) => item.id === selectedKnowledgeBaseId.value) ?? null
})

const knowledgeBaseNameMap = computed(() => {
  return new Map(knowledgeBases.value.map((item) => [item.id, item.name]))
})

const knowledgeFileNameMap = computed(() => {
  return new Map(knowledgeFiles.value.map((item) => [item.id, item.fileName]))
})

const indexedKnowledgeFiles = computed(() => {
  return knowledgeFiles.value.filter((item) => item.indexStatus === 'INDEXED')
})

const retrieveScopeNames = computed(() => {
  return retrieveForm.knowledgeBaseIds
    .map((id) => knowledgeBaseNameMap.value.get(id))
    .filter((name): name is string => Boolean(name))
})

const indexedFileCount = computed(() => {
  return knowledgeFiles.value.filter((item) => item.indexStatus === 'INDEXED').length
})

const filteredKnowledgeFiles = computed(() => {
  const keyword = fileFilters.keyword.trim().toLowerCase()
  const filtered = knowledgeFiles.value.filter((item) => {
    const matchesKeyword = !keyword || item.fileName.toLowerCase().includes(keyword)
    const matchesParseStatus =
      fileFilters.parseStatus === 'ALL' || item.parseStatus === fileFilters.parseStatus
    const matchesIndexStatus =
      fileFilters.indexStatus === 'ALL' || item.indexStatus === fileFilters.indexStatus

    return matchesKeyword && matchesParseStatus && matchesIndexStatus
  })

  return [...filtered].sort((left, right) => compareKnowledgeFile(left, right))
})

const fileStatusSummary = computed(() => {
  const summary = {
    total: knowledgeFiles.value.length,
    pending: 0,
    indexing: 0,
    failed: 0,
    indexed: 0,
  }

  for (const item of knowledgeFiles.value) {
    if (item.indexStatus === 'PENDING' || item.parseStatus === 'UPLOADED') {
      summary.pending += 1
    }
    if (item.indexStatus === 'INDEXING' || item.parseStatus === 'PARSING') {
      summary.indexing += 1
    }
    if (item.indexStatus === 'FAILED' || item.parseStatus === 'FAILED') {
      summary.failed += 1
    }
    if (item.indexStatus === 'INDEXED') {
      summary.indexed += 1
    }
  }

  return summary
})

const shouldPollFiles = computed(() => {
  if (!autoRefreshEnabled.value || !selectedKnowledgeBaseId.value) {
    return false
  }

  return (
    pendingIndexRequestIds.value.length > 0 ||
    knowledgeFiles.value.some((item) => item.indexStatus === 'INDEXING' || item.parseStatus === 'PARSING')
  )
})

async function loadKnowledgeBases(preserveSelection = true) {
  loadingKnowledgeBases.value = true
  knowledgeError.value = ''

  try {
    const list = await listKnowledgeBases({ limit: 100 })
    knowledgeBases.value = list

    if (preserveSelection && selectedKnowledgeBaseId.value) {
      const current = list.find((item) => item.id === selectedKnowledgeBaseId.value)
      if (current) {
        retrieveForm.knowledgeBaseIds = retrieveForm.knowledgeBaseIds.filter((id) =>
          list.some((item) => item.id === id),
        )
        return
      }
    }

    const first = list.length > 0 ? list[0]! : null
    selectedKnowledgeBaseId.value = first?.id ?? null
    retrieveForm.knowledgeBaseIds = first ? [first.id] : []
    await loadKnowledgeFiles()
  } catch (error) {
    knowledgeBases.value = []
    selectedKnowledgeBaseId.value = null
    knowledgeFiles.value = []
    knowledgeError.value =
      error instanceof RequestError ? error.message : '知识库列表加载失败，请稍后重试。'
  } finally {
    loadingKnowledgeBases.value = false
  }
}

async function loadKnowledgeFiles() {
  if (!selectedKnowledgeBaseId.value) {
    knowledgeFiles.value = []
    clearSegmentSelection()
    stopFileRefresh()
    return
  }

  loadingFiles.value = true
  fileError.value = ''

  try {
    knowledgeFiles.value = await listKnowledgeFiles(selectedKnowledgeBaseId.value, 100)
    if (
      selectedSegmentFileId.value &&
      !knowledgeFiles.value.some((item) => item.id === selectedSegmentFileId.value)
    ) {
      clearSegmentSelection()
    }
  } catch (error) {
    knowledgeFiles.value = []
    clearSegmentSelection()
    fileError.value = error instanceof RequestError ? error.message : '文件列表加载失败，请稍后重试。'
  } finally {
    loadingFiles.value = false
    reconcileFileRefresh()
  }
}

async function selectKnowledgeBase(knowledgeBaseId: number) {
  selectedKnowledgeBaseId.value = knowledgeBaseId
  if (!retrieveForm.knowledgeBaseIds.includes(knowledgeBaseId)) {
    retrieveForm.knowledgeBaseIds = [knowledgeBaseId]
  }
  clearSegmentSelection()
  await loadKnowledgeFiles()
}

async function handleCreateKnowledgeBase() {
  if (!createForm.name.trim()) {
    ElMessage.error('请输入知识库名称。')
    return
  }

  creatingKnowledgeBase.value = true

  try {
    const knowledgeBase = await createKnowledgeBase({
      name: createForm.name.trim(),
      description: createForm.description.trim() || undefined,
      embeddingModelName: createForm.embeddingModelName.trim() || undefined,
      embeddingDimension: createForm.embeddingDimension,
      parserStrategy: createForm.parserStrategy.trim() || undefined,
      chunkStrategy: createForm.chunkStrategy.trim() || undefined,
      chunkSize: createForm.chunkSize,
      chunkOverlap: createForm.chunkOverlap,
    })

    ElMessage.success(`知识库“${knowledgeBase.name}”已创建。`)
    createDialogVisible.value = false
    resetCreateForm()
    await loadKnowledgeBases(false)
    await selectKnowledgeBase(knowledgeBase.id)
  } catch (error) {
    ElMessage.error(error instanceof RequestError ? error.message : '知识库创建失败。')
  } finally {
    creatingKnowledgeBase.value = false
  }
}

function resetCreateForm() {
  createForm.name = ''
  createForm.description = ''
  createForm.embeddingModelName = ''
  createForm.embeddingDimension = undefined
  createForm.parserStrategy = ''
  createForm.chunkStrategy = ''
  createForm.chunkSize = DEFAULT_CHUNK_SIZE
  createForm.chunkOverlap = DEFAULT_CHUNK_OVERLAP
}

function openFilePicker() {
  fileInputRef.value?.click()
}

function handleLocalFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  selectedUploadFile.value = target.files?.[0] ?? null
}

async function handleUploadFile() {
  if (!selectedKnowledgeBaseId.value) {
    ElMessage.error('请先选择一个知识库。')
    return
  }

  if (!selectedUploadFile.value) {
    ElMessage.error('请先选择一个文件。')
    return
  }

  uploadingFile.value = true

  try {
    await uploadKnowledgeFile(selectedKnowledgeBaseId.value, selectedUploadFile.value)
    ElMessage.success(`文件“${selectedUploadFile.value.name}”上传成功。`)
    selectedUploadFile.value = null
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
    await Promise.all([loadKnowledgeBases(), loadKnowledgeFiles()])
  } catch (error) {
    ElMessage.error(error instanceof RequestError ? error.message : '文件上传失败。')
  } finally {
    uploadingFile.value = false
  }
}

async function handleIndexFile(fileId: number) {
  markFileAsIndexing(fileId)
  reconcileFileRefresh()

  try {
    const result = await indexKnowledgeFile(fileId)
    ElMessage.success(`文件索引完成，共生成 ${result.segmentCount} 个片段。`)
    await Promise.all([loadKnowledgeBases(), loadKnowledgeFiles()])
    if (selectedSegmentFileId.value === fileId) {
      await loadKnowledgeSegments(fileId)
    }
  } catch (error) {
    ElMessage.error(error instanceof RequestError ? error.message : '文件索引失败。')
    await loadKnowledgeFiles()
  } finally {
    pendingIndexRequestIds.value = pendingIndexRequestIds.value.filter((id) => id !== fileId)
    reconcileFileRefresh()
  }
}

async function handleRetrieve() {
  if (!retrieveForm.query.trim()) {
    retrieveError.value = '请输入检索内容。'
    return
  }

  if (retrieveForm.knowledgeBaseIds.length === 0) {
    retrieveError.value = '请至少选择一个知识库。'
    return
  }

  retrieving.value = true
  retrieveError.value = ''

  try {
    retrieveResult.value = await retrieveKnowledge({
      query: retrieveForm.query.trim(),
      knowledgeBaseIds: retrieveForm.knowledgeBaseIds,
      topK: retrieveForm.topK,
      minScore: retrieveForm.minScore,
    })
    expandedSnippetKeys.value = retrieveResult.value.snippets.slice(0, 1).map((item) => item.milvusPrimaryKey)
  } catch (error) {
    retrieveResult.value = null
    retrieveError.value = error instanceof RequestError ? error.message : '检索测试失败。'
  } finally {
    retrieving.value = false
  }
}

function applyRetrievePreset(topK: number, minScore: number) {
  retrieveForm.topK = topK
  retrieveForm.minScore = minScore
}

async function loadKnowledgeSegments(fileId = selectedSegmentFileId.value) {
  if (!fileId) {
    knowledgeSegments.value = []
    segmentError.value = ''
    expandedSegmentKeys.value = []
    return
  }

  loadingSegments.value = true
  segmentError.value = ''

  try {
    knowledgeSegments.value = await listKnowledgeSegments(fileId, {
      keyword: segmentKeyword.value.trim() || undefined,
      limit: DEFAULT_SEGMENT_LIMIT,
    })
    expandedSegmentKeys.value = knowledgeSegments.value.slice(0, 1).map((item) => item.milvusPrimaryKey)
  } catch (error) {
    knowledgeSegments.value = []
    expandedSegmentKeys.value = []
    segmentError.value = error instanceof RequestError ? error.message : '分片列表加载失败，请稍后重试。'
  } finally {
    loadingSegments.value = false
  }
}

function clearSegmentSelection() {
  selectedSegmentFileId.value = null
  segmentKeyword.value = ''
  knowledgeSegments.value = []
  segmentError.value = ''
  expandedSegmentKeys.value = []
}

async function focusSegmentsForFile(fileId: number) {
  const targetFile = knowledgeFiles.value.find((item) => item.id === fileId)
  if (!targetFile || targetFile.indexStatus !== 'INDEXED') {
    ElMessage.warning('只有已完成索引的文件才有可浏览的分片内容。')
    return
  }

  selectedSegmentFileId.value = fileId
  await loadKnowledgeSegments(fileId)
  await nextTick()
  segmentPanelRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function markFileAsIndexing(fileId: number) {
  pendingIndexRequestIds.value = Array.from(new Set([...pendingIndexRequestIds.value, fileId]))
  const target = knowledgeFiles.value.find((item) => item.id === fileId)
  if (!target) {
    return
  }

  target.parseStatus = 'PARSING'
  target.indexStatus = 'INDEXING'
  target.errorMessage = null
}

function compareKnowledgeFile(left: KnowledgeFile, right: KnowledgeFile) {
  let result = 0

  if (fileSort.field === 'updatedAt') {
    result = (left.updatedAt ?? 0) - (right.updatedAt ?? 0)
  } else if (fileSort.field === 'fileName') {
    result = left.fileName.localeCompare(right.fileName, 'zh-CN', {
      numeric: true,
      sensitivity: 'base',
    })
  } else if (fileSort.field === 'parseStatus') {
    result =
      resolveStatusWeight(left.parseStatus, parseStatusSortWeight) -
      resolveStatusWeight(right.parseStatus, parseStatusSortWeight)
  } else if (fileSort.field === 'indexStatus') {
    result =
      resolveStatusWeight(left.indexStatus, indexStatusSortWeight) -
      resolveStatusWeight(right.indexStatus, indexStatusSortWeight)
  }

  if (result === 0) {
    result = (left.updatedAt ?? 0) - (right.updatedAt ?? 0)
  }

  return fileSort.order === 'ASC' ? result : -result
}

function resolveStatusWeight(status: string, weightMap: Record<string, number>) {
  return weightMap[status] ?? Number.MAX_SAFE_INTEGER
}

function startFileRefresh() {
  if (fileRefreshTimer !== null) {
    return
  }

  fileRefreshTimer = window.setInterval(() => {
    void loadKnowledgeFiles()
    void loadKnowledgeBases()
  }, 3000)
}

function stopFileRefresh() {
  if (fileRefreshTimer !== null) {
    window.clearInterval(fileRefreshTimer)
    fileRefreshTimer = null
  }
}

function reconcileFileRefresh() {
  if (shouldPollFiles.value) {
    startFileRefresh()
    return
  }

  stopFileRefresh()
}

function resolveStatusTag(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'COMPLETED' || status === 'SUCCESS' || status === 'INDEXED') {
    return 'success'
  }
  if (status === 'FAILED' || status === 'ERROR') {
    return 'danger'
  }
  if (status === 'UPLOADED' || status === 'PENDING' || status === 'PARSING' || status === 'INDEXING') {
    return 'warning'
  }
  return 'info'
}

function resolveKnowledgeBaseName(knowledgeBaseId: number) {
  return knowledgeBaseNameMap.value.get(knowledgeBaseId) ?? `知识库 #${knowledgeBaseId}`
}

function resolveKnowledgeFileName(fileId: number) {
  return knowledgeFileNameMap.value.get(fileId) ?? `文件 #${fileId}`
}

function isFileIndexing(fileId: number) {
  return (
    pendingIndexRequestIds.value.includes(fileId) ||
    knowledgeFiles.value.some(
      (item) => item.id === fileId && (item.indexStatus === 'INDEXING' || item.parseStatus === 'PARSING'),
    )
  )
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

function formatFileSize(fileSize: number) {
  if (fileSize < 1024) {
    return `${fileSize} B`
  }
  if (fileSize < 1024 * 1024) {
    return `${(fileSize / 1024).toFixed(1)} KB`
  }
  return `${(fileSize / (1024 * 1024)).toFixed(1)} MB`
}

async function copyText(content: string, successMessage: string) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(content)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = content
      textarea.setAttribute('readonly', 'true')
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    ElMessage.success(successMessage)
  } catch {
    ElMessage.error('复制失败，请手动复制。')
  }
}

function buildSnippetSource(snippet: RagSnippet) {
  return [
    snippet.sourceTitle,
    snippet.sourcePath,
    `KB #${snippet.knowledgeBaseId}`,
    `File #${snippet.fileId}`,
    `Segment #${snippet.segmentNo}`,
    `Milvus Key ${snippet.milvusPrimaryKey}`,
  ].join('\n')
}

function highlightSnippetText(content: string) {
  return highlightText(content, retrieveForm.query)
}

function highlightSegmentText(content: string) {
  return highlightText(content, segmentKeyword.value)
}

function highlightText(content: string, query: string) {
  const safeText = escapeHtml(content)
  const pattern = buildHighlightPattern(query)
  if (!pattern) {
    return safeText
  }
  return safeText.replace(pattern, '<mark class="snippet-highlight">$1</mark>')
}

function buildHighlightPattern(query: string) {
  const terms = Array.from(
    new Set(
      query
        .trim()
        .split(/\s+/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ).sort((left, right) => right.length - left.length)

  if (terms.length === 0) {
    return null
  }

  return new RegExp(`(${terms.map((item) => escapeRegExp(item)).join('|')})`, 'gi')
}

function escapeRegExp(content: string) {
  return content.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function escapeHtml(content: string) {
  return content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function formatSegmentMetadata(metadata: KnowledgeSegment['metadataJson']) {
  if (metadata == null) {
    return '无'
  }
  if (typeof metadata === 'string') {
    return metadata
  }
  return JSON.stringify(metadata, null, 2)
}

onMounted(async () => {
  await loadKnowledgeBases(false)
})

onBeforeUnmount(() => {
  stopFileRefresh()
})
</script>

<template>
  <section class="knowledge-layout">
    <el-card class="panel knowledge-list-panel" shadow="never">
      <template #header>
        <div class="section-head">
          <div>
            <p class="section-kicker">知识库</p>
            <h2>Knowledge Bases</h2>
            <p class="section-description">
              每个知识库会映射到一个独立的 Milvus partition。先建库，再上传文件并完成索引，后续聊天和检索测试才能用到这里的内容。
            </p>
          </div>
          <el-button type="primary" @click="createDialogVisible = true">新建知识库</el-button>
        </div>
      </template>

      <el-alert
        v-if="knowledgeError"
        :closable="false"
        show-icon
        title="知识库列表加载失败"
        type="error"
      >
        <template #default>{{ knowledgeError }}</template>
      </el-alert>

      <div v-else-if="knowledgeBases.length === 0" class="empty-state">
        当前还没有知识库。先创建一个知识库，再上传 `txt / md / docx` 文件并触发索引。
      </div>

      <button
        v-for="knowledgeBase in knowledgeBases"
        :key="knowledgeBase.id"
        class="knowledge-item"
        :class="{ 'knowledge-item--active': knowledgeBase.id === selectedKnowledgeBaseId }"
        type="button"
        @click="selectKnowledgeBase(knowledgeBase.id)"
      >
        <strong>{{ knowledgeBase.name }}</strong>
        <span>{{ knowledgeBase.fileCount }} 个文件 / {{ knowledgeBase.segmentCount }} 个片段</span>
        <span>{{ knowledgeBase.milvusPartitionName }}</span>
      </button>
    </el-card>

    <div class="knowledge-main">
      <el-card class="panel detail-panel" shadow="never">
        <template #header>
          <div class="section-head">
            <div>
              <p class="section-kicker">详情</p>
              <h2>{{ activeKnowledgeBase?.name ?? '未选择知识库' }}</h2>
              <p class="section-description">
                这里展示当前知识库的向量模型、Milvus 位置和分片参数，方便你排查“为什么这批文档会被切成现在这样”。
              </p>
            </div>
            <el-button :loading="loadingKnowledgeBases" @click="loadKnowledgeBases">刷新列表</el-button>
          </div>
        </template>

        <div v-if="activeKnowledgeBase" class="detail-grid">
          <div class="metric-card">
            <span>Milvus</span>
            <strong>{{ activeKnowledgeBase.milvusDatabaseName }} / {{ activeKnowledgeBase.milvusCollectionName }}</strong>
            <small>{{ activeKnowledgeBase.milvusPartitionName }}</small>
          </div>
          <div class="metric-card">
            <span>Embedding</span>
            <strong>{{ activeKnowledgeBase.embeddingModelName }}</strong>
            <small>{{ activeKnowledgeBase.embeddingDimension }} 维</small>
          </div>
          <div class="metric-card">
            <span>Chunk</span>
            <strong>{{ activeKnowledgeBase.chunkStrategy }}</strong>
            <small>{{ activeKnowledgeBase.chunkSize }} / overlap {{ activeKnowledgeBase.chunkOverlap }}</small>
          </div>
        </div>

        <p v-if="activeKnowledgeBase?.description" class="description-text">
          {{ activeKnowledgeBase.description }}
        </p>
        <p v-else class="description-text">
          当前知识库还没有描述。现在已支持文件级管理和按文件浏览分片；片段级编辑与单片重算仍留到后续接口补齐后实现。
        </p>
      </el-card>

      <el-card class="panel file-panel" shadow="never">
        <template #header>
          <div class="section-head">
            <div>
              <p class="section-kicker">文件管理</p>
              <h2>Files</h2>
              <p class="section-description">
                上传只负责保存原文件和元数据；点击“触发索引”后，系统才会解析文本、分片、向量化并写入 Milvus。
              </p>
            </div>
            <div class="head-actions">
              <el-switch v-model="autoRefreshEnabled" active-text="自动刷新" @change="reconcileFileRefresh" />
              <el-button :loading="loadingFiles" @click="loadKnowledgeFiles">刷新文件</el-button>
            </div>
          </div>
        </template>

        <div class="summary-grid">
          <div class="summary-card">
            <span>总文件数</span>
            <strong>{{ fileStatusSummary.total }}</strong>
          </div>
          <div class="summary-card">
            <span>待索引</span>
            <strong>{{ fileStatusSummary.pending }}</strong>
          </div>
          <div class="summary-card">
            <span>索引中</span>
            <strong>{{ fileStatusSummary.indexing }}</strong>
          </div>
          <div class="summary-card">
            <span>失败</span>
            <strong>{{ fileStatusSummary.failed }}</strong>
          </div>
        </div>

        <div class="panel-note">
          <span>支持上传：`txt / md / docx`</span>
          <span>上传成功后状态通常先是 `UPLOADED / PENDING`</span>
          <span>只有 `INDEXED` 的片段会参与检索测试和聊天 RAG</span>
        </div>

        <div class="upload-bar">
          <input
            ref="fileInputRef"
            accept=".txt,.md,.docx"
            class="hidden-input"
            type="file"
            @change="handleLocalFileChange"
          />
          <el-button :disabled="!selectedKnowledgeBaseId" @click="openFilePicker">选择文件</el-button>
          <span class="meta-inline">{{ selectedUploadFile?.name ?? '未选择文件' }}</span>
          <el-button
            :disabled="!selectedKnowledgeBaseId || !selectedUploadFile"
            :loading="uploadingFile"
            type="primary"
            @click="handleUploadFile"
          >
            上传文件
          </el-button>
        </div>

        <div class="filter-bar">
          <el-input
            v-model="fileFilters.keyword"
            clearable
            placeholder="按文件名筛选"
          />
          <el-select v-model="fileFilters.parseStatus">
            <el-option label="全部解析状态" value="ALL" />
            <el-option label="UPLOADED" value="UPLOADED" />
            <el-option label="PARSING" value="PARSING" />
            <el-option label="PARSED" value="PARSED" />
            <el-option label="FAILED" value="FAILED" />
          </el-select>
          <el-select v-model="fileFilters.indexStatus">
            <el-option label="全部索引状态" value="ALL" />
            <el-option label="PENDING" value="PENDING" />
            <el-option label="INDEXING" value="INDEXING" />
            <el-option label="INDEXED" value="INDEXED" />
            <el-option label="FAILED" value="FAILED" />
          </el-select>
          <el-select v-model="fileSort.field">
            <el-option label="按更新时间排序" value="updatedAt" />
            <el-option label="按文件名排序" value="fileName" />
            <el-option label="按解析状态排序" value="parseStatus" />
            <el-option label="按索引状态排序" value="indexStatus" />
          </el-select>
          <el-select v-model="fileSort.order">
            <el-option label="倒序" value="DESC" />
            <el-option label="正序" value="ASC" />
          </el-select>
        </div>

        <el-alert v-if="fileError" :closable="false" show-icon title="文件列表加载失败" type="error">
          <template #default>{{ fileError }}</template>
        </el-alert>

        <div v-else-if="knowledgeFiles.length === 0 && !loadingFiles" class="empty-state">
          当前知识库还没有文件。上传后会先进入 `UPLOADED / PENDING`，然后可手动触发索引。
        </div>

        <div v-else-if="filteredKnowledgeFiles.length === 0" class="empty-state">
          当前筛选条件下没有匹配文件，可以调整关键字或状态筛选。
        </div>

        <el-table v-else :data="filteredKnowledgeFiles" class="knowledge-file-table" stripe>
          <el-table-column label="文件名" min-width="200">
            <template #default="{ row }">
              <div class="table-cell-stack">
                <strong>{{ row.fileName }}</strong>
                <span>{{ row.fileExt.toUpperCase() }} · {{ formatFileSize(row.fileSize) }}</span>
                <span v-if="row.storageUri">{{ row.storageUri }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="解析状态" width="120">
            <template #default="{ row }">
              <el-tag :type="resolveStatusTag(row.parseStatus)">{{ row.parseStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="索引状态" width="120">
            <template #default="{ row }">
              <el-tag :type="resolveStatusTag(row.indexStatus)">{{ row.indexStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="140">
            <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
          </el-table-column>
          <el-table-column label="错误信息" min-width="180">
            <template #default="{ row }">
              <span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
              <span v-else class="meta-inline">无</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="116">
            <template #default="{ row }">
              <div class="table-action-stack">
                <el-button
                  :disabled="row.indexStatus !== 'INDEXED'"
                  link
                  type="primary"
                  @click="focusSegmentsForFile(row.id)"
                >
                  查看分片
                </el-button>
                <el-button
                  :loading="isFileIndexing(row.id)"
                  link
                  type="primary"
                  @click="handleIndexFile(row.id)"
                >
                  {{ isFileIndexing(row.id) ? '索引中' : '触发索引' }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <div ref="segmentPanelRef">
        <el-card class="panel segment-panel" shadow="never">
          <template #header>
            <div class="section-head">
              <div>
                <p class="section-kicker">分片管理</p>
                <h2>Segments</h2>
                <p class="section-description">
                  这里按“文件 -> 片段”查看实际切分结果，用来排查某个文件为什么会被切成这些段，以及检索命中时拿到的原文内容。
                </p>
              </div>
              <el-button :disabled="!selectedSegmentFileId" :loading="loadingSegments" @click="loadKnowledgeSegments()">
                刷新片段
              </el-button>
            </div>
          </template>

          <div class="panel-note">
            <span>先在文件列表点“查看分片”，或在这里手动选择一个已索引文件</span>
            <span>当前最多展示 {{ DEFAULT_SEGMENT_LIMIT }} 个片段，按 `segmentNo` 顺序排列</span>
            <span>片段编辑和单片向量重算还未接入，本轮先解决“可查看、可定位”</span>
          </div>

          <div class="segment-toolbar">
            <el-select
              v-model="selectedSegmentFileId"
              clearable
              filterable
              placeholder="选择一个已索引文件"
              @change="loadKnowledgeSegments"
            >
              <el-option
                v-for="file in indexedKnowledgeFiles"
                :key="file.id"
                :label="file.fileName"
                :value="file.id"
              />
            </el-select>
            <el-input
              v-model="segmentKeyword"
              clearable
              placeholder="按片段内容或标题筛选"
              @keyup.enter="loadKnowledgeSegments()"
            />
            <el-button :disabled="!selectedSegmentFileId" :loading="loadingSegments" type="primary" @click="loadKnowledgeSegments()">
              查询片段
            </el-button>
            <el-button @click="clearSegmentSelection">清空选择</el-button>
          </div>

          <div v-if="!selectedSegmentFileId" class="empty-state">
            先选择一个已索引文件，才能查看它被切成了哪些片段。
          </div>

          <el-alert v-else-if="segmentError" :closable="false" show-icon title="分片列表加载失败" type="error">
            <template #default>{{ segmentError }}</template>
          </el-alert>

          <div v-else-if="knowledgeSegments.length === 0 && !loadingSegments" class="empty-state">
            当前文件没有匹配的片段。可能是还未完成索引，或者当前关键字没有命中。
          </div>

          <div v-else class="segment-list">
            <article
              v-for="segment in knowledgeSegments"
              :key="segment.milvusPrimaryKey"
              class="segment-card"
            >
              <div class="snippet-head">
                <div class="snippet-title-block">
                  <strong>#{{ segment.segmentNo }} {{ segment.sourceTitle || resolveKnowledgeFileName(segment.fileId) }}</strong>
                  <span>{{ resolveKnowledgeFileName(segment.fileId) }}</span>
                </div>
                <div class="snippet-actions">
                  <el-button
                    link
                    type="primary"
                    @click="copyText(segment.fullText, '已复制完整片段。')"
                  >
                    复制片段
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="copyText(segment.milvusPrimaryKey, '已复制 Milvus Primary Key。')"
                  >
                    复制 Key
                  </el-button>
                </div>
              </div>
              <div class="meta-row">
                <span>KB #{{ segment.knowledgeBaseId }}</span>
                <span>File #{{ segment.fileId }}</span>
                <span>Tokens {{ segment.tokenCount }}</span>
                <span v-if="segment.pageNo !== null">Page {{ segment.pageNo }}</span>
                <span>{{ segment.milvusPrimaryKey }}</span>
              </div>
              <p class="snippet-preview" v-html="highlightSegmentText(segment.textPreview)"></p>
              <div class="meta-row">
                <span>sourcePath：{{ segment.sourcePath }}</span>
              </div>
              <el-collapse v-model="expandedSegmentKeys">
                <el-collapse-item :name="segment.milvusPrimaryKey" title="查看完整片段">
                  <div class="snippet-full-text" v-html="highlightSegmentText(segment.fullText)"></div>
                </el-collapse-item>
                <el-collapse-item
                  v-if="segment.metadataJson"
                  :name="`${segment.milvusPrimaryKey}:metadata`"
                  title="查看片段元数据"
                >
                  <pre class="segment-metadata">{{ formatSegmentMetadata(segment.metadataJson) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </article>
          </div>
        </el-card>
      </div>

      <el-card class="panel retrieval-panel" shadow="never">
        <template #header>
          <div class="section-head">
            <div>
              <p class="section-kicker">检索测试</p>
              <h2>Retrieve</h2>
              <p class="section-description">
                这个区域用于验证“问题能不能从当前知识库里召回正确片段”。它不会生成最终回答，只负责检查检索链路是否正常。
              </p>
            </div>
          </div>
        </template>

        <div class="retrieve-overview">
          <div class="overview-card">
            <span>检索范围</span>
            <strong>{{ retrieveForm.knowledgeBaseIds.length }} 个知识库</strong>
            <small>{{ retrieveScopeNames.join(' / ') || '未选择知识库' }}</small>
          </div>
          <div class="overview-card">
            <span>当前参数</span>
            <strong>topK {{ retrieveForm.topK }} / minScore {{ retrieveForm.minScore.toFixed(2) }}</strong>
            <small>topK 决定最多返回多少个候选片段，minScore 决定过滤有多严格。</small>
          </div>
          <div class="overview-card">
            <span>当前前置条件</span>
            <strong>{{ indexedFileCount }} 个已索引文件</strong>
            <small>只有已完成索引的文件片段才能被召回；当前知识库总片段数 {{ activeKnowledgeBase?.segmentCount ?? 0 }}。</small>
          </div>
        </div>

        <div class="retrieve-form">
          <div class="retrieve-field retrieve-field--full">
            <label class="field-label" for="retrieve-query">检索问题</label>
            <el-input
              id="retrieve-query"
              v-model="retrieveForm.query"
              :rows="3"
              placeholder="输入一个问题、关键词或短句，验证当前知识库片段是否能被召回。"
              resize="none"
              type="textarea"
            />
            <div class="form-item-hint">
              这里建议直接输入你希望命中的问题，比如“如何配置向量检索”或“知识库上传后的状态流转是什么”。
            </div>
          </div>

          <div class="retrieve-controls-grid">
            <div class="retrieve-field retrieve-field--wide">
              <label class="field-label">检索范围</label>
              <el-select
                v-model="retrieveForm.knowledgeBaseIds"
                clearable
                collapse-tags
                collapse-tags-tooltip
                filterable
                multiple
                placeholder="选择哪些知识库参与本次检索"
              >
                <el-option
                  v-for="knowledgeBase in knowledgeBases"
                  :key="knowledgeBase.id"
                  :label="knowledgeBase.name"
                  :value="knowledgeBase.id"
                />
              </el-select>
              <div class="form-item-hint">
                可以单选也可以多选。这个选项决定后端会去哪些知识库 partition 中做联合检索。
              </div>
            </div>

            <div class="retrieve-field">
              <label class="field-label">返回片段数 `topK`</label>
              <el-input-number v-model="retrieveForm.topK" :max="20" :min="1" />
              <div class="form-item-hint">
                最多返回多少条候选片段。值越大，召回范围越宽，但噪音也可能更多。当前默认 {{ DEFAULT_RETRIEVE_TOP_K }}。
              </div>
            </div>

            <div class="retrieve-field">
              <label class="field-label">最低相似度 `minScore`</label>
              <el-input-number
                v-model="retrieveForm.minScore"
                :max="1"
                :min="0"
                :precision="2"
                :step="0.05"
              />
              <div class="form-item-hint">
                用来过滤“不够像”的结果，范围 0~1。值越高越严格。当前默认 {{ DEFAULT_RETRIEVE_MIN_SCORE.toFixed(2) }}，如果噪音偏多可以调到 0.65~0.75。
              </div>
            </div>
          </div>

          <div class="retrieve-actions">
            <div class="retrieve-presets">
              <span class="meta-inline">快速预设</span>
              <el-button text @click="applyRetrievePreset(3, 0.75)">严格</el-button>
              <el-button text @click="applyRetrievePreset(DEFAULT_RETRIEVE_TOP_K, DEFAULT_RETRIEVE_MIN_SCORE)">
                平衡
              </el-button>
              <el-button text @click="applyRetrievePreset(6, 0.35)">宽松</el-button>
            </div>
            <el-button :loading="retrieving" type="primary" @click="handleRetrieve">开始检索</el-button>
          </div>
        </div>

        <el-alert v-if="retrieveError" :closable="false" show-icon title="检索测试失败" type="error">
          <template #default>{{ retrieveError }}</template>
        </el-alert>

        <div v-if="retrieveResult" class="result-list">
          <div class="meta-row">
            <span>检索文本：{{ retrieveResult.query }}</span>
            <span>命中数：{{ retrieveResult.total }}</span>
            <span>返回片段数 topK：{{ retrieveForm.topK }}</span>
            <span>最低相似度 minScore：{{ retrieveForm.minScore.toFixed(2) }}</span>
          </div>

          <article
            v-for="(snippet, index) in retrieveResult.snippets"
            :key="snippet.milvusPrimaryKey"
            class="snippet-card"
          >
            <div class="snippet-head">
              <div class="snippet-title-block">
                <strong>#{{ index + 1 }} {{ snippet.sourceTitle }}</strong>
                <span>{{ resolveKnowledgeBaseName(snippet.knowledgeBaseId) }}</span>
              </div>
              <div class="snippet-head-actions">
                <el-tag effect="plain" round type="success">score {{ snippet.score.toFixed(3) }}</el-tag>
                <div class="snippet-actions">
                  <el-button
                    link
                    type="primary"
                    @click="copyText(snippet.fullText, '已复制完整片段。')"
                  >
                    复制片段
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="copyText(buildSnippetSource(snippet), '已复制片段来源。')"
                  >
                    复制来源
                  </el-button>
                  <el-button
                    link
                    type="primary"
                    @click="copyText(snippet.milvusPrimaryKey, '已复制 Milvus Primary Key。')"
                  >
                    复制 Key
                  </el-button>
                </div>
              </div>
            </div>
            <div class="meta-row">
              <span>KB #{{ snippet.knowledgeBaseId }}</span>
              <span>File #{{ snippet.fileId }}</span>
              <span>Segment #{{ snippet.segmentNo }}</span>
              <span>Tokens {{ snippet.tokenCount }}</span>
              <span v-if="snippet.pageNo !== null">Page {{ snippet.pageNo }}</span>
              <span>{{ snippet.milvusPrimaryKey }}</span>
            </div>
            <p class="snippet-preview" v-html="highlightSnippetText(snippet.textPreview)"></p>
            <div class="meta-row">
              <span>sourcePath：{{ snippet.sourcePath }}</span>
            </div>
            <el-collapse v-model="expandedSnippetKeys">
              <el-collapse-item :name="snippet.milvusPrimaryKey" title="查看完整片段">
                <div class="snippet-full-text" v-html="highlightSnippetText(snippet.fullText)"></div>
              </el-collapse-item>
            </el-collapse>
          </article>
        </div>
      </el-card>
    </div>
  </section>

  <el-dialog v-model="createDialogVisible" title="新建知识库" width="760">
    <el-form label-position="top">
      <div class="dialog-intro">
        <p>
          名称和说明主要帮助团队识别知识库；下面的处理配置会影响文档如何被解析、切片和向量化。普通文档库通常保持默认即可。
        </p>
        <div class="dialog-intro-tags">
          <el-tag effect="plain" round>默认解析：{{ DEFAULT_PARSER_STRATEGY }}</el-tag>
          <el-tag effect="plain" round>默认分片：{{ DEFAULT_CHUNK_STRATEGY }}</el-tag>
          <el-tag effect="plain" round>默认单片长度：{{ DEFAULT_CHUNK_SIZE }}</el-tag>
          <el-tag effect="plain" round>默认重叠长度：{{ DEFAULT_CHUNK_OVERLAP }}</el-tag>
        </div>
      </div>

      <el-form-item label="知识库名称">
        <el-input v-model="createForm.name" maxlength="128" placeholder="例如：产品文档库" />
        <div class="form-item-hint">用于列表展示、聊天页知识库选择和检索范围识别，建议按内容域命名。</div>
      </el-form-item>
      <el-form-item label="知识库说明">
        <el-input
          v-model="createForm.description"
          :rows="3"
          maxlength="512"
          placeholder="可选，用一句话描述知识库用途。"
          resize="none"
          show-word-limit
          type="textarea"
        />
        <div class="form-item-hint">可选，说明这个知识库收录什么资料、适合回答哪些问题。</div>
      </el-form-item>

      <div class="form-section-head">
        <strong>文档处理配置</strong>
        <p>这些字段会影响索引效果。当前版本只稳定支持基础策略，不确定时建议直接使用默认值。</p>
      </div>

      <div class="form-grid">
        <el-form-item label="向量模型名称">
          <el-input
            v-model="createForm.embeddingModelName"
            placeholder="例如：text-embedding-3-large；留空使用系统默认模型"
          />
          <div class="form-item-hint">决定文本转向量时使用的模型。留空后会回退到系统默认配置。</div>
        </el-form-item>
        <el-form-item label="向量维度">
          <el-input-number v-model="createForm.embeddingDimension" :min="1" />
          <div class="form-item-hint">
            必须和所选向量模型的输出维度一致。留空后使用系统默认维度。
          </div>
        </el-form-item>
        <el-form-item label="文档解析策略">
          <el-input
            v-model="createForm.parserStrategy"
            :placeholder="`留空使用 ${DEFAULT_PARSER_STRATEGY}`"
          />
          <div class="form-item-hint">
            决定上传文件如何提取纯文本。当前版本仅支持 {{ DEFAULT_PARSER_STRATEGY }}。
          </div>
        </el-form-item>
        <el-form-item label="文本分片策略">
          <el-input
            v-model="createForm.chunkStrategy"
            :placeholder="`留空使用 ${DEFAULT_CHUNK_STRATEGY}`"
          />
          <div class="form-item-hint">
            决定解析后的文本如何切成可检索片段。当前版本仅支持 {{ DEFAULT_CHUNK_STRATEGY }}。
          </div>
        </el-form-item>
        <el-form-item label="单片长度">
          <el-input-number v-model="createForm.chunkSize" :min="1" />
          <div class="form-item-hint">
            每个片段的目标字符数。默认 {{ DEFAULT_CHUNK_SIZE }}，建议控制在 800~1200 之间。
          </div>
        </el-form-item>
        <el-form-item label="重叠长度">
          <el-input-number v-model="createForm.chunkOverlap" :min="0" />
          <div class="form-item-hint">
            相邻片段之间重复保留的字符数，用于减少内容被切断。默认 {{ DEFAULT_CHUNK_OVERLAP }}，建议控制在 100~200 之间。
          </div>
        </el-form-item>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="createDialogVisible = false">取消</el-button>
      <el-button :loading="creatingKnowledgeBase" type="primary" @click="handleCreateKnowledgeBase">
        创建知识库
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.knowledge-layout {
  display: grid;
  grid-template-columns: minmax(260px, 0.9fr) minmax(0, 1.8fr);
  gap: 20px;
  align-items: start;
}

.knowledge-main {
  display: grid;
  gap: 20px;
}

.panel {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(12px);
}

.knowledge-list-panel {
  min-height: 820px;
  background:
    linear-gradient(180deg, rgba(246, 249, 252, 0.96), rgba(255, 255, 255, 0.88)),
    rgba(255, 255, 255, 0.86);
}

.detail-panel {
  background:
    linear-gradient(135deg, rgba(255, 246, 230, 0.95), rgba(255, 255, 255, 0.86)),
    rgba(255, 255, 255, 0.86);
}

.file-panel,
.segment-panel,
.retrieval-panel {
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.96), rgba(243, 247, 252, 0.88)),
    rgba(255, 255, 255, 0.86);
}

:deep(.file-panel > .el-card__body) {
  overflow-x: hidden;
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

.section-description {
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.7;
  max-width: 720px;
}

.section-kicker {
  margin: 0 0 6px;
  font-size: 12px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #8b5e21;
}

.knowledge-item {
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background: rgba(248, 250, 252, 0.9);
  text-align: left;
  cursor: pointer;
  transition:
    transform 160ms ease,
    border-color 160ms ease;
}

.knowledge-item:hover,
.knowledge-item--active {
  transform: translateY(-1px);
  border-color: rgba(31, 78, 121, 0.45);
  background:
    linear-gradient(135deg, rgba(255, 248, 230, 0.9), rgba(255, 255, 255, 0.96)),
    rgba(248, 250, 252, 0.9);
}

.knowledge-item strong {
  color: #0f172a;
}

.knowledge-item span,
.meta-inline,
.meta-row {
  color: #64748b;
  font-size: 13px;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.76);
}

.metric-card span,
.metric-card small {
  color: #64748b;
}

.metric-card strong {
  color: #0f172a;
}

.description-text {
  margin: 16px 0 0;
  color: #425466;
  line-height: 1.8;
}

.upload-bar,
.retrieve-controls,
.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  align-items: center;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-card {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.78);
}

.summary-card span {
  color: #64748b;
  font-size: 13px;
}

.summary-card strong {
  color: #0f172a;
  font-size: 24px;
}

.panel-note {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  margin: 16px 0;
}

.panel-note span {
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(248, 250, 252, 0.92);
  color: #425466;
  font-size: 12px;
}

.filter-bar {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.hidden-input {
  display: none;
}

.knowledge-file-table :deep(.el-table__cell) {
  vertical-align: top;
}

.knowledge-file-table :deep(.el-table__cell .cell) {
  white-space: normal;
  overflow-wrap: anywhere;
}

.table-cell-stack {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.table-cell-stack strong {
  overflow-wrap: anywhere;
}

.table-cell-stack span {
  color: #64748b;
  font-size: 13px;
  white-space: normal;
  overflow-wrap: anywhere;
}

.table-action-stack {
  display: grid;
  justify-items: start;
  gap: 4px;
}

.segment-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1.2fr) minmax(220px, 1fr) auto auto;
  gap: 12px;
  align-items: center;
}

.segment-toolbar :deep(.el-select),
.segment-toolbar :deep(.el-input) {
  width: 100%;
}

.segment-list {
  display: grid;
  gap: 14px;
  margin-top: 16px;
}

.segment-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.78);
}

.segment-metadata {
  margin: 0;
  padding: 14px;
  overflow: auto;
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.94);
  color: #dbe7f3;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.retrieve-form {
  display: grid;
  gap: 12px;
}

.retrieve-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.overview-card {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.82);
}

.overview-card span,
.overview-card small {
  color: #64748b;
}

.overview-card strong {
  color: #0f172a;
}

.retrieve-controls-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) repeat(2, minmax(220px, 1fr));
  gap: 16px;
}

.retrieve-field {
  display: grid;
  gap: 8px;
}

.retrieve-field--full {
  grid-column: 1 / -1;
}

.field-label {
  color: #0f172a;
  font-size: 13px;
  font-weight: 600;
}

.retrieve-field :deep(.el-select),
.retrieve-field :deep(.el-input-number) {
  width: 100%;
}

.retrieve-actions,
.retrieve-presets {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
}

.retrieve-actions {
  justify-content: space-between;
}

.result-list {
  display: grid;
  gap: 14px;
  margin-top: 16px;
}

.snippet-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.78);
}

.snippet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.snippet-head-actions,
.snippet-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px 12px;
}

.snippet-title-block {
  display: grid;
  gap: 6px;
}

.snippet-title-block span {
  color: #64748b;
  font-size: 13px;
}

.snippet-card p,
.empty-state {
  margin: 0;
  color: #425466;
  line-height: 1.75;
}

.snippet-preview,
.snippet-full-text {
  margin: 0;
  white-space: pre-wrap;
}

.snippet-full-text {
  padding: 14px;
  overflow: auto;
  border-radius: 14px;
  background: #0f172a;
  color: #e2e8f0;
  line-height: 1.75;
}

:deep(.snippet-highlight) {
  padding: 0 2px;
  border-radius: 4px;
  background: rgba(197, 138, 29, 0.22);
  color: inherit;
}

.error-text {
  color: #b42318;
  line-height: 1.6;
  white-space: normal;
  overflow-wrap: anywhere;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.dialog-intro {
  display: grid;
  gap: 12px;
  padding: 16px;
  margin-bottom: 20px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 18px;
  background: rgba(248, 250, 252, 0.92);
}

.dialog-intro p,
.form-section-head p {
  margin: 0;
  color: #425466;
  line-height: 1.75;
}

.dialog-intro-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.form-section-head {
  display: grid;
  gap: 6px;
  margin-bottom: 12px;
}

.form-section-head strong {
  color: #0f172a;
  font-size: 15px;
}

.form-item-hint {
  margin-top: 8px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 1200px) {
  .knowledge-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .section-head,
  .snippet-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .detail-grid,
  .form-grid,
  .segment-toolbar,
  .retrieve-overview,
  .retrieve-controls-grid,
  .summary-grid,
  .filter-bar {
    grid-template-columns: 1fr;
  }

  .head-actions {
    width: 100%;
    align-items: flex-start;
    flex-direction: column;
  }

  .retrieve-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
