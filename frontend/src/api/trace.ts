import { request } from './http'

export interface TraceEvent {
  id: number
  requestId: string
  conversationId: number
  messageId: number | null
  eventType: string
  eventStage: string | null
  eventSource: string
  eventPayloadJson: string
  successFlag: boolean
  costMillis: number | null
  createdAt: number
}

export interface TraceDetail {
  requestId: string
  conversationId: number | null
  events: TraceEvent[]
}

export async function getTraceDetail(requestId: string): Promise<TraceDetail> {
  const response = await request<TraceDetail>(`/api/v1/traces/${requestId}`)
  return response.data
}
