import axios from 'axios'

/** 后端错误体里的单个字段错误，见契约「错误结构」 */
export interface FieldError {
  field: string
  message: string
}

/**
 * 所有失败的请求最终都变成这个类型，页面只需要认识它。
 * 调用方靠 code 决定怎么处理，不要解析 message（和契约对 Agent 的要求一致）。
 * 后端的 code 之外，前端自己补两个：NETWORK_ERROR（没连上）、TIMEOUT（超时）。
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fields: FieldError[]

  constructor(status: number, code: string, message: string, fields: FieldError[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fields = fields
  }

  /** 某个字段的错误文字，没有则 undefined；表单用它把错误挂到对应输入框 */
  fieldMessage(field: string): string | undefined {
    return this.fields.find((f) => f.field === field)?.message
  }
}

interface ErrorBody {
  code: string
  message?: string
  fields?: FieldError[]
}

function isErrorBody(data: unknown): data is ErrorBody {
  return typeof data === 'object' && data !== null && typeof (data as ErrorBody).code === 'string'
}

/** 把 axios 抛出的各种错误统一成 ApiError */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error

  if (axios.isAxiosError(error)) {
    if (error.response) {
      const { status, data } = error.response
      if (isErrorBody(data)) {
        return new ApiError(status, data.code, data.message ?? '', data.fields ?? [])
      }
      // 后端没起来时，Vite 代理或网关会返回没有 JSON 体的 5xx
      return new ApiError(status, 'SERVER_ERROR', error.message)
    }
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      return new ApiError(0, 'TIMEOUT', error.message)
    }
    return new ApiError(0, 'NETWORK_ERROR', error.message)
  }

  return new ApiError(0, 'UNKNOWN', error instanceof Error ? error.message : String(error))
}

const MESSAGES: Record<string, string> = {
  NETWORK_ERROR: '连不上服务器，检查网络后重试',
  TIMEOUT: '服务器响应太慢，请重试',
  SERVER_ERROR: '服务器出了点问题，请稍后重试',
  UNAUTHENTICATED: '身份已失效，请重新选择身份',
  FORBIDDEN: '你没有权限这样做',
  NOT_FOUND: '内容不存在，或者你已经不在这个行程里了',
  AMOUNT_NOT_ROUNDABLE: '金额的小数位数不对，这个币别不支持这么细的金额',
  SPLIT_SUM_MISMATCH: '每人分摊的金额加起来要等于总额',
  MEMBER_NOT_IN_TRIP: '付款人或分摊的人不是这个行程的成员，刷新后重选',
  MEMBER_LEFT: '付款人或分摊的人已经退出行程，刷新后重选',
  VERSION_CONFLICT: '这条内容刚被别人改过，刷新后再改',
  IDEMPOTENCY_KEY_REUSED: '这一笔之前已经提交过，内容不一样。回到列表看看是否已经记上',
}

/** 给用户看的错误文字（中文）。表单字段错误由页面自己用 fieldMessage 处理。 */
export function describeError(error: ApiError): string {
  if (error.code === 'VALIDATION_FAILED') {
    return error.fields[0]?.message ?? '填写的内容有误，请检查后重试'
  }
  return MESSAGES[error.code] ?? (error.message || '操作失败，请重试')
}
