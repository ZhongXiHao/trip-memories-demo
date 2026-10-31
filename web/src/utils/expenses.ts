import type { ExpenseCategory, ExpenseView, SplitMode } from '@/api/expenses'
import type { MemberView } from '@/api/members'

import { toLocalDateTime } from './zonedTime'

const CATEGORY_LABELS: Record<ExpenseCategory, string> = {
  flight: '机票',
  lodging: '住宿',
  food: '餐饮',
  transport: '交通',
  shopping: '购物',
  ticket: '门票',
  other: '其他',
}

/** 分类的中文名；后端以后加了这里还不认识的分类时，归到"其他" */
export function categoryLabel(category: string): string {
  return CATEGORY_LABELS[category as ExpenseCategory] ?? CATEGORY_LABELS.other
}

const SPLIT_MODE_LABELS: Record<SplitMode, string> = {
  equal: '平均分',
  custom: '按金额分',
  none: '个人支出',
}

export function splitModeLabel(mode: SplitMode): string {
  return SPLIT_MODE_LABELS[mode] ?? mode
}

/** 账目里的人：按 memberId 找昵称。已退出的照样找得到（成员列表包含已退出的）；找不到时显示编号 */
export function memberName(members: MemberView[], memberId: number): string {
  return members.find((m) => m.memberId === memberId)?.nickname ?? `成员 ${memberId}`
}

export function isMemberLeft(members: MemberView[], memberId: number): boolean {
  return members.find((m) => m.memberId === memberId)?.status === 'left'
}

/** 只有付款人或记账人能改、能删（负责人也没有特权），和后端的 403 规则一致 */
export function canModifyExpense(expense: ExpenseView, myMemberId: number): boolean {
  return expense.paidByMemberId === myMemberId || expense.createdByMemberId === myMemberId
}

/** 后端算的"第几天"。旧后端没有这个字段、或行程没有开始日期时是 null，就不显示 */
export function dayLabel(dayIndex: unknown): string | null {
  if (typeof dayIndex !== 'number' || !Number.isInteger(dayIndex)) return null
  return dayIndex >= 1 ? `第 ${dayIndex} 天` : '出发前'
}

export interface CurrencyTotal {
  currency: string
  amountMinor: number
}

/**
 * 按币别分开的合计，不做换算（不同币别不能直接相加）。
 * 顺序按币别在列表里第一次出现的先后（列表是时间倒序，所以最近用过的币别在前），同一天里顺序稳定。
 */
export function totalsByCurrency(expenses: ExpenseView[]): CurrencyTotal[] {
  const totals = new Map<string, number>()
  for (const { currency, amountMinor } of expenses) {
    totals.set(currency, (totals.get(currency) ?? 0) + amountMinor)
  }
  return [...totals].map(([currency, amountMinor]) => ({ currency, amountMinor }))
}

export interface ExpenseDayGroup {
  /** 当地日期 "YYYY-MM-DD"（按每笔自己的 occurredTz） */
  date: string
  /** 这一天的"第几天"，取组里第一笔的；没有则为 null */
  dayIndex: number | null
  items: ExpenseView[]
}

/**
 * 按当地日期分组，保持后端给的顺序（occurredAt 倒序）。
 * 不同时区的两笔落在同一个当地日期时也归到一组；后端的顺序在跨时区时可能让同一天出现两次，
 * 这种情况合并到第一次出现的那一组，不打乱组内顺序。
 */
export function groupByLocalDate(expenses: ExpenseView[]): ExpenseDayGroup[] {
  const groups: ExpenseDayGroup[] = []
  const byDate = new Map<string, ExpenseDayGroup>()
  for (const expense of expenses) {
    const { date } = toLocalDateTime(expense.occurredAt, expense.occurredTz)
    let group = byDate.get(date)
    if (!group) {
      const dayIndex = typeof expense.dayIndex === 'number' ? expense.dayIndex : null
      group = { date, dayIndex, items: [] }
      byDate.set(date, group)
      groups.push(group)
    }
    group.items.push(expense)
  }
  return groups
}
