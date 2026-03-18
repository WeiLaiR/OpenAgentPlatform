import { request } from './http'

export interface KnowledgeBase {
  id: number
  name: string
  description: string | null
  status: string
  embeddingModelName: string
  embeddingDimension: number
  milvusDatabaseName: string
  milvusCollectionName: string
  milvusPartitionName: string
  parserStrategy: string
  chunkStrategy: string
  chunkSize: number
  chunkOverlap: number
  fileCount: number
  segmentCount: number
  createdAt: number
  updatedAt: number
}

export interface KnowledgeBaseCreateRequest {
  name: string
  description?: string
  embeddingModelName?: string
  embeddingDimension?: number
  parserStrategy?: string
  chunkStrategy?: string
  chunkSize?: number
  chunkOverlap?: number
}

export interface KnowledgeFile {
  id: number
  knowledgeBaseId: number
  fileName: string
  fileExt: string
  fileSize: number
  parseStatus: string
  indexStatus: string
  storageUri: string
  errorMessage: string | null
  createdAt: number
  updatedAt: number
}

export interface KnowledgeFileIndexResult {
  fileId: number
  knowledgeBaseId: number
  parseStatus: string
  indexStatus: string
  parserName: string
  segmentCount: number
  errorMessage: string | null
  updatedAt: number
}

export interface KnowledgeSegment {
  id: number
  knowledgeBaseId: number
  fileId: number
  segmentNo: number
  textPreview: string
  fullText: string
  tokenCount: number
  pageNo: number | null
  sourceTitle: string
  sourcePath: string
  metadataJson: Record<string, unknown> | string | null
  milvusPrimaryKey: string
  createdAt: number
}

export interface RagSnippet {
  knowledgeBaseId: number
  fileId: number
  segmentNo: number
  score: number
  textPreview: string
  fullText: string
  tokenCount: number
  pageNo: number | null
  sourceTitle: string
  sourcePath: string
  milvusPrimaryKey: string
}

export interface KnowledgeRetrieveRequest {
  query: string
  knowledgeBaseIds: number[]
  topK?: number
  minScore?: number
}

export interface KnowledgeRetrieveResult {
  query: string
  snippets: RagSnippet[]
  total: number
}

export async function createKnowledgeBase(
  requestBody: KnowledgeBaseCreateRequest,
): Promise<KnowledgeBase> {
  const response = await request<KnowledgeBase>('/api/v1/knowledge-bases', {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
  return response.data
}

export async function listKnowledgeBases(params: {
  keyword?: string
  status?: number
  limit?: number
} = {}): Promise<KnowledgeBase[]> {
  const searchParams = new URLSearchParams()

  if (params.keyword) {
    searchParams.set('keyword', params.keyword)
  }
  if (typeof params.status === 'number') {
    searchParams.set('status', String(params.status))
  }
  if (typeof params.limit === 'number') {
    searchParams.set('limit', String(params.limit))
  }

  const suffix = searchParams.toString() ? `?${searchParams.toString()}` : ''
  const response = await request<KnowledgeBase[]>(`/api/v1/knowledge-bases${suffix}`)
  return response.data
}

export async function listKnowledgeFiles(
  knowledgeBaseId: number,
  limit?: number,
): Promise<KnowledgeFile[]> {
  const suffix = typeof limit === 'number' ? `?limit=${limit}` : ''
  const response = await request<KnowledgeFile[]>(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/files${suffix}`,
  )
  return response.data
}

export async function uploadKnowledgeFile(
  knowledgeBaseId: number,
  file: File,
  autoIndex = false,
): Promise<KnowledgeFile> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('autoIndex', String(autoIndex))

  const response = await request<KnowledgeFile>(
    `/api/v1/knowledge-bases/${knowledgeBaseId}/files/upload`,
    {
      method: 'POST',
      body: formData,
    },
  )
  return response.data
}

export async function indexKnowledgeFile(fileId: number): Promise<KnowledgeFileIndexResult> {
  const response = await request<KnowledgeFileIndexResult>(`/api/v1/knowledge-files/${fileId}/index`, {
    method: 'POST',
  })
  return response.data
}

export async function listKnowledgeSegments(
  fileId: number,
  params: {
    keyword?: string
    limit?: number
  } = {},
): Promise<KnowledgeSegment[]> {
  const searchParams = new URLSearchParams()

  if (params.keyword) {
    searchParams.set('keyword', params.keyword)
  }
  if (typeof params.limit === 'number') {
    searchParams.set('limit', String(params.limit))
  }

  const suffix = searchParams.toString() ? `?${searchParams.toString()}` : ''
  const response = await request<KnowledgeSegment[]>(`/api/v1/knowledge-files/${fileId}/segments${suffix}`)
  return response.data
}

export async function retrieveKnowledge(
  requestBody: KnowledgeRetrieveRequest,
): Promise<KnowledgeRetrieveResult> {
  const response = await request<KnowledgeRetrieveResult>('/api/v1/knowledge-bases/retrieve', {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
  return response.data
}
