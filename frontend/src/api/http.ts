export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  timestamp: number
  requestId: string
}

interface ApiErrorPayload {
  code: number
  message: string
  detail?: string
  timestamp: number
  requestId?: string
}

export class RequestError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly requestId?: string,
    public readonly detail?: string,
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
  const payload = parseJson<ApiResponse<T> | ApiErrorPayload>(rawText)

  if (!response.ok) {
    throw new RequestError(
      resolveErrorMessage(payload, `请求失败，HTTP 状态码 ${response.status}`, rawText),
      response.status,
      payload?.requestId,
      payload && 'detail' in payload ? payload.detail : undefined,
    )
  }

  if (!payload) {
    throw new RequestError('服务端未返回可解析的 JSON 数据', response.status)
  }

  if (payload.code !== 0) {
    throw new RequestError(
      resolveErrorMessage(payload, payload.message, rawText),
      response.status,
      payload.requestId,
      'detail' in payload ? payload.detail : undefined,
    )
  }

  return payload as ApiResponse<T>
}

function parseJson<T>(rawText: string): T | undefined {
  if (!rawText) {
    return undefined
  }

  try {
    return JSON.parse(rawText) as T
  } catch {
    return undefined
  }
}

function resolveErrorMessage(
  payload: ApiResponse<unknown> | ApiErrorPayload | undefined,
  fallbackMessage: string,
  rawText: string,
) {
  if (payload && 'detail' in payload && payload.detail) {
    return `${payload.message}：${payload.detail}`
  }

  if (payload?.message) {
    return payload.message
  }

  const compactRawText = rawText.trim().replace(/\s+/g, ' ')
  if (compactRawText) {
    return `${fallbackMessage}：${compactRawText.slice(0, 200)}`
  }

  return fallbackMessage
}
