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
