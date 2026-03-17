import { request } from './http'

export interface Conversation {
  id: number
  title: string
  modeCode: string
  enableRag: boolean
  enableAgent: boolean
  status: string
  lastMessageAt: number | null
  createdAt: number
  updatedAt: number
}

export interface ConversationCreateRequest {
  title?: string
  enableRag?: boolean
  enableAgent?: boolean
}

export interface ConversationMessage {
  id: number
  conversationId: number
  roleCode: string
  messageType: string
  content: string
  requestId: string | null
  finishReason: string | null
  createdAt: number
  uiState?: 'thinking' | 'streaming' | 'done' | 'error'
  thinkingStartedAt?: number
  progressMessage?: string
}

export async function createConversation(
  requestBody: ConversationCreateRequest,
): Promise<Conversation> {
  const response = await request<Conversation>('/api/v1/conversations', {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
  return response.data
}

export async function listConversations(): Promise<Conversation[]> {
  const response = await request<Conversation[]>('/api/v1/conversations')
  return response.data
}

export async function listConversationMessages(
  conversationId: number,
): Promise<ConversationMessage[]> {
  const response = await request<ConversationMessage[]>(
    `/api/v1/conversations/${conversationId}/messages`,
  )
  return response.data
}
