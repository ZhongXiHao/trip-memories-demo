import { http } from './http'

/**
 * pending：付款方标记了已还，等收款方确认；confirmed：已确认，抵销欠款；
 * cancelled：待确认时被取消，不抵销欠款。列表里不会出现 cancelled，只在确认、取消的响应里出现。
 */
export type RepaymentStatus = 'pending' | 'confirmed' | 'cancelled'

/**
 * 还款对象（契约「还款」）。同时保存"销帐"（抵销了多少欠款）和"实付"（实际转了多少钱），
 * 同币别时两组金额相等。汇率接口不返回，需要时用 paid / cleared 算。
 */
export interface RepaymentView {
  id: number
  /** 一次转账一个组，取创建请求的 clientUuid；组里每个 clearedCurrency 至多一条 */
  settlementGroupId: string
  /** 针对某笔支出还款时是那笔的 id，否则 null */
  expenseId: number | null
  fromMemberId: number
  toMemberId: number
  clearedAmountMinor: number
  clearedCurrency: string
  paidAmountMinor: number
  paidCurrency: string
  status: RepaymentStatus
  /** UTC 的 ISO-8601（带 Z）；没确认时为 null */
  confirmedAt: string | null
  note: string | null
  createdByMemberId: number
  version: number
  createdAt: string
}

/** 创建请求里要结清的一项欠款，三个字段都必填；同币别时 paidAmountMinor 必须等于 clearedAmountMinor */
export interface RepaymentItemRequest {
  clearedCurrency: string
  clearedAmountMinor: number
  paidAmountMinor: number
}

/** POST /repayments 的请求体 */
export interface CreateRepaymentRequest {
  /** 幂等键，同时作为 settlementGroupId。一次"还款"的意图只生成一次，重试时沿用 */
  clientUuid: string
  fromMemberId: number
  toMemberId: number
  paidCurrency: string
  /** 各项 clearedCurrency 不能重复；带 expenseId 时只能有一项 */
  items: RepaymentItemRequest[]
  expenseId?: number
  /** ≤ 512 */
  note?: string
}

/** 创建的响应：这一次转账（一个 settlementGroupId）里的所有还款，按 clearedCurrency 升序 */
export interface RepaymentGroup {
  settlementGroupId: string
  items: RepaymentView[]
}

/** 列表的筛选条件，都可选 */
export interface RepaymentListQuery {
  /** 只能是 pending / confirmed（已取消的不在列表里） */
  status?: Exclude<RepaymentStatus, 'cancelled'>
  /** 付款方或收款方是他 */
  memberId?: number
  /** 必须是 UUID 格式，否则 400 */
  settlementGroupId?: string
}

const base = (tripId: number) => `/trips/${tripId}/repayments`
const group = (tripId: number, settlementGroupId: string) =>
  `${base(tripId)}/groups/${encodeURIComponent(settlementGroupId)}`

/**
 * POST /trips/{tripId}/repayments：创建者是付款方 → pending，等对方确认；创建者是收款方 → 直接 confirmed。
 * 首次创建 201；同一个 clientUuid、内容一致的重复提交 200（replayed 为 true，不是错误）。
 * 内容不一致、换了创建者、或那一组里有已取消的 → 409 IDEMPOTENCY_KEY_REUSED。
 * 超过当前可还的额度（含"现在没有 from 欠 to"）→ 400 REPAYMENT_EXCEEDS_DEBT。
 */
export async function createRepayment(
  tripId: number,
  request: CreateRepaymentRequest,
): Promise<{ group: RepaymentGroup; replayed: boolean }> {
  const response = await http.post<RepaymentGroup>(base(tripId), request)
  return { group: response.data, replayed: response.status === 200 }
}

/** GET /trips/{tripId}/repayments：行程里全部没取消的还款，后端已按 createdAt 倒序（相同按 id 倒序） */
export async function listRepayments(tripId: number, query: RepaymentListQuery = {}): Promise<RepaymentView[]> {
  const params = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== undefined && value !== ''))
  const { data } = await http.get<{ items: RepaymentView[] }>(base(tripId), { params })
  return data.items
}

/** POST .../repayments/{id}/confirm：只有收款方能确认（其他人 403）；已确认的再确认 200；已取消的 404 */
export async function confirmRepayment(tripId: number, repaymentId: number): Promise<RepaymentView> {
  const { data } = await http.post<RepaymentView>(`${base(tripId)}/${repaymentId}/confirm`)
  return data
}

/**
 * POST .../repayments/groups/{settlementGroupId}/confirm：确认组里所有待确认的。
 * 返回该组所有还款，已取消的也在其中（status 为 cancelled）。组不存在或全部已取消 → 404。
 */
export async function confirmRepaymentGroup(tripId: number, settlementGroupId: string): Promise<RepaymentView[]> {
  const { data } = await http.post<{ items: RepaymentView[] }>(`${group(tripId, settlementGroupId)}/confirm`)
  return data.items
}

/** POST .../repayments/{id}/cancel：付款方、收款方都能取消待确认的；已确认的 → 409 REPAYMENT_ALREADY_CONFIRMED */
export async function cancelRepayment(tripId: number, repaymentId: number): Promise<RepaymentView> {
  const { data } = await http.post<RepaymentView>(`${base(tripId)}/${repaymentId}/cancel`)
  return data
}

/**
 * POST .../repayments/groups/{settlementGroupId}/cancel：取消组里所有待确认的，已确认的保留。
 * 一条都没取消到、且组里有已确认的 → 409 REPAYMENT_ALREADY_CONFIRMED。返回该组所有还款。
 */
export async function cancelRepaymentGroup(tripId: number, settlementGroupId: string): Promise<RepaymentView[]> {
  const { data } = await http.post<{ items: RepaymentView[] }>(`${group(tripId, settlementGroupId)}/cancel`)
  return data.items
}
