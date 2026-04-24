import { request } from './http'

export interface ChatSendRequest {
  conversationId?: number
  message: string
  enableRag: boolean
  enableAgent: boolean
  memoryEnabled?: boolean
  knowledgeBaseIds?: number[]
  mcpServerIds?: number[]
}

export interface ChatRequestAccepted {
  requestId: string
  conversationId: number | null
  status: string
  acceptedAt: number
}

export interface ToolConfirmationPending {
  id: number
  requestId: string
  conversationId: number
  toolCallId: string
  toolName: string
  toolTitle: string | null
  serverName: string | null
  riskLevel: string
  status: string
  statusMessage: string
}

export interface ChatAnswer {
  requestId: string | null
  conversationId: number | null
  answer: string
  finishReason: string
  usedRag: boolean
  usedTools: boolean
  elapsedMillis: number
  pendingConfirmation?: ToolConfirmationPending | null
}

export async function submitChat(requestBody: ChatSendRequest): Promise<ChatRequestAccepted> {
  const response = await request<ChatRequestAccepted>('/api/v1/chat/send', {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
  return response.data
}

export async function sendChatSync(requestBody: ChatSendRequest): Promise<ChatAnswer> {
  const response = await request<ChatAnswer>('/api/v1/chat/send-sync', {
    method: 'POST',
    body: JSON.stringify(requestBody),
  })
  return response.data
}

export interface StreamStartEvent {
  requestId: string
  conversationId: number | null
}

export interface StreamTokenEvent {
  requestId: string
  content: string
}

export interface StreamProgressEvent {
  requestId: string
  status: string
  message: string
  elapsedMillis: number
}

export interface StreamCompletedEvent {
  requestId: string
  answer: string
  finishReason: string
  pendingConfirmation?: ToolConfirmationPending | null
}

export interface StreamErrorEvent {
  requestId: string
  message: string
}

export async function approveToolConfirmation(confirmationId: number): Promise<ChatRequestAccepted> {
  const response = await request<ChatRequestAccepted>(
    `/api/v1/chat/tool-confirmations/${confirmationId}/approve`,
    {
      method: 'POST',
    },
  )
  return response.data
}

export async function rejectToolConfirmation(confirmationId: number): Promise<ChatRequestAccepted> {
  const response = await request<ChatRequestAccepted>(
    `/api/v1/chat/tool-confirmations/${confirmationId}/reject`,
    {
      method: 'POST',
    },
  )
  return response.data
}
