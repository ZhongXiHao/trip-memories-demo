// 日期一律用 "YYYY-MM-DD" 字符串（和后端的 LocalDate 对应），不经过 Date 的时区换算，避免差一天。

/** Date → "YYYY-MM-DD"，取的是本地日历上的年月日（日历组件选出来的 Date 就是本地的） */
export function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** "YYYY-MM-DD" → 本地时间 0 点的 Date（给日历组件用）；格式不对返回 null */
export function fromIsoDate(iso: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  if (!match) return null
  return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]))
}

function dayNumber(iso: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso)
  return match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) / 86_400_000 : null
}

/** 行程共几天（含首尾）；缺一端或顺序不对返回 null */
export function tripDays(start: string | null, end: string | null): number | null {
  if (!start || !end) return null
  const s = dayNumber(start)
  const e = dayNumber(end)
  if (s === null || e === null || e < s) return null
  return e - s + 1
}

function shortDate(iso: string, withYear: boolean): string {
  const [y, m, d] = iso.split('-')
  return withYear ? `${y}/${m}/${d}` : `${m}/${d}`
}

/**
 * 列表里显示的日期：
 *   10/01 – 10/05 · 5 天   （都在今年）
 *   2025/12/30 – 2026/01/03 · 5 天   （不在今年或跨年，带上年份）
 *   10/01 起 / 至 10/05 / 日期未定
 */
export function formatDateRange(start: string | null, end: string | null, today: Date = new Date()): string {
  if (!start && !end) return '日期未定'
  const thisYear = String(today.getFullYear())
  const withYear = [start, end].some((d) => d && d.slice(0, 4) !== thisYear)
  if (start && !end) return `${shortDate(start, withYear)} 起`
  if (!start && end) return `至 ${shortDate(end, withYear)}`
  const days = tripDays(start, end)
  const range = `${shortDate(start!, withYear)} – ${shortDate(end!, withYear)}`
  return days === null ? range : `${range} · ${days} 天`
}
