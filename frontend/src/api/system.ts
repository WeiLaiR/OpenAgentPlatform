import { request } from './http'

export interface SystemHealth {
  appStatus: string
  mysqlStatus: string
  milvusStatus: string
  chatModelStatus: string
  embeddingModelStatus: string
  healthyMcpServers: number
  timestamp: number
}

export async function getSystemHealth(): Promise<{ data: SystemHealth; requestId: string }> {
  const response = await request<SystemHealth>('/api/v1/system/health')
  return {
    data: response.data,
    requestId: response.requestId,
  }
}
