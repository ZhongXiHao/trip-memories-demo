import type { ExpenseView } from '@/api/expenses'

/** 金额旁边最多放几个币别快捷选项 */
export const CURRENCY_CHOICE_LIMIT = 3

/**
 * 这个行程里最近用过的币别，最近的在前、不重复。
 * "最近"按记账时间 createdAt 算（同一时刻按 id），不按消费时间 occurredAt：
 * 补记几天前的一笔，也算刚刚用过这个币别。
 */
export function recentCurrencies(expenses: ExpenseView[]): string[] {
  const sorted = [...expenses].sort((a, b) => {
    if (a.createdAt !== b.createdAt) return a.createdAt < b.createdAt ? 1 : -1
    return b.id - a.id
  })
  return dedupe(sorted.map((e) => e.currency))
}

/**
 * 币别快捷选项：先是行程的默认币别，再是最近用过的，去重后最多 limit 个。
 * 默认币别缺失（空字符串、undefined）时只用最近用过的。
 */
export function currencyChoices(
  defaultCurrency: string | null | undefined,
  recent: string[],
  limit: number = CURRENCY_CHOICE_LIMIT,
): string[] {
  const all = defaultCurrency ? [defaultCurrency, ...recent] : recent
  return dedupe(all).slice(0, Math.max(0, limit))
}

function dedupe(codes: string[]): string[] {
  return [...new Set(codes.filter((code) => typeof code === 'string' && code !== ''))]
}
