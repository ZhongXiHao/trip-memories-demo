import type { DebtView } from '@/api/debts'

/** 一条欠款和"我"的关系：我欠别人、别人欠我、和我无关 */
export type DebtSide = 'i-owe' | 'owes-me' | 'others'

export function debtSide(debt: DebtView, myMemberId: number): DebtSide {
  if (debt.fromMemberId === myMemberId) return 'i-owe'
  if (debt.toMemberId === myMemberId) return 'owes-me'
  return 'others'
}

const SIDE_ORDER: Record<DebtSide, number> = { 'i-owe': 0, 'owes-me': 1, others: 2 }

/**
 * 显示顺序：我欠的排最前，然后是欠我的，最后是别人之间的；同一类里保持后端的顺序
 * （fromMemberId、toMemberId、currency 升序；Array.sort 是稳定的）。
 */
export function sortDebtsForMe(debts: DebtView[], myMemberId: number): DebtView[] {
  return [...debts].sort((a, b) => SIDE_ORDER[debtSide(a, myMemberId)] - SIDE_ORDER[debtSide(b, myMemberId)])
}

/** 列表的 key：同一对人、同一币别只有一条 */
export function debtKey(debt: DebtView): string {
  return `${debt.fromMemberId}-${debt.toMemberId}-${debt.currency}`
}

/**
 * 现在还能还多少：amountMinor 减去同方向已经标记、还没确认的（不足 0 按 0）。
 * 和后端创建还款时的额度一致，超过会是 REPAYMENT_EXCEEDS_DEBT。
 */
export function repayableMinor(debt: DebtView): number {
  return Math.max(0, debt.amountMinor - debt.pendingMinor)
}

/**
 * 我能不能从这条欠款记一笔还款、以什么身份：付款方（标记已还，等对方确认）、收款方（直接记已收到），
 * 和我无关的是 null（后端 403）。只能用于真实的一对一欠款，化简出来的"建议转账"不能据此创建还款。
 */
export function repayRole(debt: DebtView, myMemberId: number): 'payer' | 'receiver' | null {
  if (debt.fromMemberId === myMemberId) return 'payer'
  if (debt.toMemberId === myMemberId) return 'receiver'
  return null
}
