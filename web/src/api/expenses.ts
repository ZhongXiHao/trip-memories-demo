import { http } from './http'

/** 分摊方式：equal 平均分、custom 按金额分、none 个人支出（只有自己看得见） */
export type SplitMode = 'equal' | 'custom' | 'none'

/** 由 splitMode 推出，客户端不传：共同支出是 trip，个人支出是 private */
export type Visibility = 'trip' | 'private'

export type ExpenseCategory = 'flight' | 'lodging' | 'food' | 'transport' | 'shopping' | 'ticket' | 'other'

export const EXPENSE_CATEGORIES: ExpenseCategory[] = [
  'food',
  'transport',
  'lodging',
  'ticket',
  'shopping',
  'flight',
  'other',
]

/** 这笔是从哪里记的：表单、指令、Agent。前端表单不传，后端默认 form */
export type ExpenseSource = 'form' | 'command' | 'agent'

/** 支出对象里的一条分摊。金额是"最小单位 × 100" */
export interface ExpenseSplit {
  memberId: number
  amountMinor: number
}

/** 对应后端 ExpenseView（契约「支出对象」） */
export interface ExpenseView {
  id: number
  tripId: number
  title: string
  amountMinor: number
  currency: string
  paidByMemberId: number
  /** 记账人：创建时的当前成员，之后不变 */
  createdByMemberId: number
  splitMode: SplitMode
  visibility: Visibility
  splits: ExpenseSplit[]
  /** UTC 的 ISO-8601（带 Z） */
  occurredAt: string
  /** IANA 时区，"哪一天"按它换算 */
  occurredTz: string
  /** 第几天，由后端算；行程没有开始日期时为 null */
  dayIndex: number | null
  category: ExpenseCategory
  note: string | null
  source: ExpenseSource
  /** 乐观锁，修改时原样带回 */
  version: number
  createdAt: string
}

/** 请求里的一条分摊：equal 只传 memberId，custom 再带 amountMinor，none 不传 */
export interface SplitRequest {
  memberId: number
  amountMinor?: number
}

/** 预览、创建、修改共有的字段（契约「预览、创建的请求体」「修改的请求体」） */
export interface ExpenseFields {
  title: string
  amountMinor: number
  currency: string
  paidByMemberId: number
  splitMode: SplitMode
  splits?: SplitRequest[]
  /** ISO-8601，必须带时区；修改时没传保持原值 */
  occurredAt?: string
  /** IANA 时区；创建时默认行程时区，修改时没传保持原值 */
  occurredTz?: string
  /** 没传是 other（修改时也回到 other） */
  category?: ExpenseCategory
  /** 没传是空（修改时也会清空） */
  note?: string | null
}

/** POST /expenses 的请求体：clientUuid 是幂等键，一次"记一笔"的意图只生成一次 */
export interface CreateExpenseRequest extends ExpenseFields {
  clientUuid: string
}

/** POST /expenses/preview 的请求体：修改某笔前预览时带上 expenseId（原本就在这笔里、后来退出的人不报错） */
export interface PreviewExpenseRequest extends ExpenseFields {
  expenseId?: number
}

/** PUT /expenses/{id} 的请求体：整体替换，带上详情里的 version */
export interface UpdateExpenseRequest extends ExpenseFields {
  version: number
}

/** 预览结果里的一条分摊。remainderMinor 只出现在拿到余数的那一项上，并且已经算进它的 amountMinor */
export interface PreviewSplit {
  memberId: number
  amountMinor: number
  remainderMinor?: number
}

export interface ExpenseWarning {
  code: string
  message: string
}

export interface ExpensePreview {
  amountMinor: number
  currency: string
  splits: PreviewSplit[]
  warnings: ExpenseWarning[]
}

/** 列表的筛选条件，都可选，同时给取交集 */
export interface ExpenseListQuery {
  /** 当地日期 "YYYY-MM-DD"，按每笔自己的 occurredTz 判断 */
  date?: string
  /** 付款人或分摊者 */
  memberId?: number
  currency?: string
  category?: ExpenseCategory
}

const base = (tripId: number) => `/trips/${tripId}/expenses`

/** POST /trips/{tripId}/expenses/preview：只算不写，校验规则和创建一样 */
export async function previewExpense(tripId: number, request: PreviewExpenseRequest): Promise<ExpensePreview> {
  const { data } = await http.post<ExpensePreview>(`${base(tripId)}/preview`, request)
  return data
}

/**
 * POST /trips/{tripId}/expenses：首次创建 201；同一个 clientUuid、内容一致的重复提交返回 200 和已有那一笔
 * （replayed 为 true，不是错误）。同一个 clientUuid 但内容不同 → 409 IDEMPOTENCY_KEY_REUSED。
 */
export async function createExpense(
  tripId: number,
  request: CreateExpenseRequest,
): Promise<{ expense: ExpenseView; replayed: boolean }> {
  const response = await http.post<ExpenseView>(base(tripId), request)
  return { expense: response.data, replayed: response.status === 200 }
}

/** GET /trips/{tripId}/expenses：当前用户看得见的支出，后端已按 occurredAt 倒序（相同按 id 倒序） */
export async function listExpenses(tripId: number, query: ExpenseListQuery = {}): Promise<ExpenseView[]> {
  const params = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== undefined && value !== ''))
  const { data } = await http.get<{ items: ExpenseView[] }>(base(tripId), { params })
  return data.items
}

/** GET /trips/{tripId}/expenses/{expenseId}：看不见（不存在、已删除、别人的个人支出）都是 404 */
export async function getExpense(tripId: number, expenseId: number): Promise<ExpenseView> {
  const { data } = await http.get<ExpenseView>(`${base(tripId)}/${expenseId}`)
  return data
}

/**
 * PUT /trips/{tripId}/expenses/{expenseId}：只有付款人或记账人能改（负责人也没有特权，否则 403）。
 * 整体替换；occurredAt、occurredTz 没传保持原值。version 对不上 → 409 VERSION_CONFLICT。
 */
export async function updateExpense(
  tripId: number,
  expenseId: number,
  request: UpdateExpenseRequest,
): Promise<ExpenseView> {
  const { data } = await http.put<ExpenseView>(`${base(tripId)}/${expenseId}`, request)
  return data
}

/** POST /trips/{tripId}/expenses/{expenseId}/delete：只有付款人或记账人能删，软删除，成功 204 */
export async function deleteExpense(tripId: number, expenseId: number): Promise<void> {
  await http.post(`${base(tripId)}/${expenseId}/delete`)
}
