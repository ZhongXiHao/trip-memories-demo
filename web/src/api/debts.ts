import { http } from './http'

/**
 * 欠款对象（契约「欠/还款 · 欠款」）：from 欠 to，只出现净额 > 0 的。金额是"最小单位 × 100"。
 * 按币别分开，不换算、不跨币别互抵。
 */
export interface DebtView {
  fromMemberId: number
  toMemberId: number
  currency: string
  /** 还欠多少（已确认的还款已经抵掉） */
  amountMinor: number
  /**
   * 同方向、同币别、已标记但对方还没确认（也没取消）的还款之和。待确认的不抵债，所以另列。
   * 可能大于 amountMinor（比如支出被删之后）。化简（simplify）的结果里恒为 0。
   */
  pendingMinor: number
}

/** 总览的查询参数，都可选 */
export interface DebtOverviewQuery {
  /** 只看和这个人有关的（他欠别人、别人欠他）；可以是已退出的成员 */
  memberId?: number
  /**
   * true：做传递化简，给出一组"建议转账"（A 欠 B、B 欠 C 合并成 A 欠 C）。只用于显示，
   * 不能据此创建还款：创建还款仍按真实的一对一欠款校验额度。和 memberId 一起给时，先化简整个行程再筛。
   */
  simplify?: boolean
}

const base = (tripId: number) => `/trips/${tripId}/debts`

/**
 * GET /trips/{tripId}/debts：行程里每两人之间、按币别分列的一对一净额，
 * 后端已按 fromMemberId、toMemberId、currency 升序。memberId 不属于这个行程时是空数组。
 */
export async function listDebts(tripId: number, query: DebtOverviewQuery = {}): Promise<DebtView[]> {
  const params: Record<string, string | number> = {}
  if (query.memberId !== undefined) params.memberId = query.memberId
  if (query.simplify) params.simplify = 'true'
  const { data } = await http.get<{ items: DebtView[] }>(base(tripId), { params })
  return data.items
}

/**
 * GET /trips/{tripId}/debts/{memberId}：当前用户和这个成员之间的欠款，按币别分列。
 * memberId 不属于这个行程 → 404；是自己 → 400 VALIDATION_FAILED（字段 memberId）。
 */
export async function getDebtsWith(tripId: number, memberId: number): Promise<DebtView[]> {
  const { data } = await http.get<{ items: DebtView[] }>(`${base(tripId)}/${memberId}`)
  return data.items
}
