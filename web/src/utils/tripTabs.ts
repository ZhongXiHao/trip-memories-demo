import type { LocationQuery, LocationQueryValue } from 'vue-router'

// 行程页（手机版式）的三个标签。当前标签写在地址的 ?tab= 里：刷新、从别的页面返回后还停在原来的标签。
// 默认的"支出"不写进地址，地址保持干净（/trips/1 就是支出）。

export type TripTab = 'expenses' | 'debts' | 'members'

export const TRIP_TABS: ReadonlyArray<{ name: TripTab; title: string }> = [
  { name: 'expenses', title: '支出' },
  { name: 'debts', title: '欠款' },
  { name: 'members', title: '成员' },
]

export const DEFAULT_TRIP_TAB: TripTab = 'expenses'

function isTripTab(value: unknown): value is TripTab {
  return TRIP_TABS.some((tab) => tab.name === value)
}

/** ?tab= 的值 → 标签。没有、不认识（手改地址、旧链接）都回到默认的"支出"；重复的参数取第一个 */
export function tabFromQuery(value: LocationQueryValue | LocationQueryValue[] | undefined): TripTab {
  const first = Array.isArray(value) ? value[0] : value
  return isTripTab(first) ? first : DEFAULT_TRIP_TAB
}

/** 切到某个标签后的 query：只改 tab，保留其它参数；默认标签不写进地址 */
export function queryWithTab(query: LocationQuery, tab: TripTab): LocationQuery {
  const next: LocationQuery = { ...query }
  if (tab === DEFAULT_TRIP_TAB) delete next.tab
  else next.tab = tab
  return next
}
