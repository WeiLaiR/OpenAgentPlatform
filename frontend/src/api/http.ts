export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
  requestId: string
}

export class RequestError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly requestId?: string,
  ) {
    super(message)
    this.name = 'RequestError'
  }
}

export async function request<T>(input: string, init: RequestInit = {}): Promise<ApiResponse<T>> {
  const headers = new Headers(init.headers)

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json')
  }

  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(input, {
    ...init,
    headers,
  })

  const rawText = await response.text()
  const payload = rawText ? (JSON.parse(rawText) as ApiResponse<T>) : undefined

  if (!response.ok) {
    throw new RequestError(
      payload?.message ?? `请求失败，HTTP 状态码 ${response.status}`,
      response.status,
      payload?.requestId,
    )
  }

  if (!payload) {
    throw new RequestError('服务端未返回 JSON 数据', response.status)
  }

  if (payload.code !== 0) {
    throw new RequestError(payload.message, response.status, payload.requestId)
  }

  return payload
}
