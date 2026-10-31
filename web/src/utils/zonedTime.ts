// 支出时间：后端存 UTC（带 Z），每笔另有 occurredTz（IANA 时区）。
// 界面上显示和输入的都是"那个时区的当地时间"，和浏览器所在的时区无关
// （人在上海看东京的账，拉面还是东京时间晚上 8 点吃的）。

export interface LocalDateTime {
  /** "YYYY-MM-DD" */
  date: string
  /** "HH:mm"，24 小时制 */
  time: string
}

const formatters = new Map<string, Intl.DateTimeFormat>()

function formatterFor(timeZone: string): Intl.DateTimeFormat {
  let formatter = formatters.get(timeZone)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
    formatters.set(timeZone, formatter)
  }
  return formatter
}

/** 这个时区浏览器认不认识；不认识的按 UTC 显示，不让整页报错 */
export function isKnownTimeZone(timeZone: string): boolean {
  try {
    formatterFor(timeZone)
    return true
  } catch {
    return false
  }
}

function safeZone(timeZone: string): string {
  return isKnownTimeZone(timeZone) ? timeZone : 'UTC'
}

/** 某一时刻在这个时区的墙上时间，按 UTC 毫秒数表示（只是为了做减法） */
function wallClockMs(epochMs: number, timeZone: string): number {
  const parts = Object.fromEntries(
    formatterFor(timeZone)
      .formatToParts(new Date(epochMs))
      .map((p) => [p.type, p.value]),
  )
  return Date.UTC(
    Number(parts.year),
    Number(parts.month) - 1,
    Number(parts.day),
    Number(parts.hour),
    Number(parts.minute),
    Number(parts.second),
  )
}

/** UTC 的 ISO 时间 → 那个时区的当地日期和时间 */
export function toLocalDateTime(iso: string, timeZone: string): LocalDateTime {
  const wall = new Date(wallClockMs(Date.parse(iso), safeZone(timeZone))).toISOString()
  return { date: wall.slice(0, 10), time: wall.slice(11, 16) }
}

/**
 * 某个时区的当地日期和时间 → UTC 的 ISO 时间（带 Z，去掉毫秒）。
 * 夏令时切换造成的"不存在的时间"（比如纽约 3 月某天的 2:30）会顺延到切换之后；
 * "重复的时间"取第一次出现的那个。格式不对返回 null。
 */
export function fromLocalDateTime({ date, time }: LocalDateTime, timeZone: string): string | null {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  const t = /^(\d{2}):(\d{2})$/.exec(time)
  if (!d || !t) return null
  const wall = Date.UTC(Number(d[1]), Number(d[2]) - 1, Number(d[3]), Number(t[1]), Number(t[2]))
  if (Number.isNaN(wall)) return null
  const zone = safeZone(timeZone)
  // 先用"墙上时间当作 UTC"的偏移猜一次，再用猜出来那一刻的偏移修正一次
  const firstOffset = wallClockMs(wall, zone) - wall
  let epoch = wall - firstOffset
  const secondOffset = wallClockMs(epoch, zone) - epoch
  if (secondOffset !== firstOffset) {
    const candidate = wall - secondOffset
    // 两个偏移都对不上（不存在的时间）时取较晚的那个时刻，也就是顺延
    epoch = wallClockMs(candidate, zone) === wall ? candidate : Math.max(epoch, candidate)
  }
  return new Date(epoch).toISOString().replace(/\.\d{3}Z$/, 'Z')
}

/** 现在在这个时区是几号几点（新建支出的默认时间） */
export function nowIn(timeZone: string, now: Date = new Date()): LocalDateTime {
  return toLocalDateTime(now.toISOString(), timeZone)
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** "2026-10-03" → "周六"（按日历日期算，不经过任何时区） */
export function weekdayOf(date: string): string {
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  if (!d) return ''
  return WEEKDAYS[new Date(Date.UTC(Number(d[1]), Number(d[2]) - 1, Number(d[3]))).getUTCDay()] ?? ''
}

/** "2026-10-03" → "10/03"，不是今年的带上年份 */
export function shortLocalDate(date: string, today: Date = new Date()): string {
  const [y, m, d] = date.split('-')
  return y === String(today.getFullYear()) ? `${m}/${d}` : `${y}/${m}/${d}`
}
