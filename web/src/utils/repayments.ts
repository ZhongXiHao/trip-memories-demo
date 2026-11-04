import type { RepaymentStatus, RepaymentView } from '@/api/repayments'

const STATUS_LABELS: Record<RepaymentStatus, string> = {
  pending: '待确认',
  confirmed: '已确认',
  cancelled: '已取消',
}

export function repaymentStatusLabel(status: RepaymentStatus): string {
  return STATUS_LABELS[status] ?? status
}

/** 只有收款方能确认，而且只对待确认的有意义（和后端的 403 规则一致） */
export function canConfirmRepayment(repayment: RepaymentView, myMemberId: number): boolean {
  return repayment.status === 'pending' && repayment.toMemberId === myMemberId
}

/** 付款方、收款方都能取消，只适用于待确认的 */
export function canCancelRepayment(repayment: RepaymentView, myMemberId: number): boolean {
  return (
    repayment.status === 'pending' && (repayment.fromMemberId === myMemberId || repayment.toMemberId === myMemberId)
  )
}

/** 一次转账（一个 settlementGroupId）在列表里的样子 */
export interface RepaymentGroupRow {
  settlementGroupId: string
  items: RepaymentView[]
}

/**
 * 按 settlementGroupId 把列表分组：一次转账结清了几个币别时，显示在一起，可以整组确认或取消。
 * 组的顺序按它在列表里第一次出现的位置（后端已按 createdAt 倒序），组内保持原顺序。
 */
export function groupRepayments(repayments: RepaymentView[]): RepaymentGroupRow[] {
  const groups = new Map<string, RepaymentView[]>()
  for (const repayment of repayments) {
    const items = groups.get(repayment.settlementGroupId)
    if (items) items.push(repayment)
    else groups.set(repayment.settlementGroupId, [repayment])
  }
  return Array.from(groups, ([settlementGroupId, items]) => ({ settlementGroupId, items }))
}

/**
 * 整组的操作：只在组里有不止一条时提供（只有一条时和单条操作一样）。
 * 确认：组里每条没取消的都是给我的，并且还有待确认的；取消：我是这组的付款方或收款方，并且还有待确认的。
 */
export function groupActions(group: RepaymentGroupRow, myMemberId: number): { confirm: boolean; cancel: boolean } {
  const live = group.items.filter((r) => r.status !== 'cancelled')
  if (live.length < 2) return { confirm: false, cancel: false }
  const hasPending = live.some((r) => r.status === 'pending')
  return {
    confirm: hasPending && live.every((r) => r.toMemberId === myMemberId),
    cancel: hasPending && live.every((r) => r.fromMemberId === myMemberId || r.toMemberId === myMemberId),
  }
}

/**
 * 确认、取消之后用响应更新列表里的行：按 id 替换，响应里有、列表里没有的（比如之前就取消了的）不加进来。
 * 刚取消的行留在列表里显示"已取消"，下次刷新时才消失，免得用户看不到操作的结果。
 */
export function applyRepaymentUpdates(list: RepaymentView[], updates: RepaymentView[]): RepaymentView[] {
  const byId = new Map(updates.map((r) => [r.id, r]))
  return list.map((r) => byId.get(r.id) ?? r)
}
