/**
 * 创建行程时能选的时区（IANA）。选"目的地所在的时区"：每笔支出默认用它，"第几天"也按它换算。
 * 后端会校验是不是合法的 IANA 时区，这里只放常见目的地；需要别的以后再加。
 */
export interface TimezoneOption {
  value: string
  label: string
}

export const TIMEZONE_OPTIONS: TimezoneOption[] = [
  { value: 'Asia/Tokyo', label: '东京' },
  { value: 'Asia/Seoul', label: '首尔' },
  { value: 'Asia/Shanghai', label: '北京 / 上海' },
  { value: 'Asia/Hong_Kong', label: '香港' },
  { value: 'Asia/Taipei', label: '台北' },
  { value: 'Asia/Singapore', label: '新加坡' },
  { value: 'Asia/Bangkok', label: '曼谷' },
  { value: 'Asia/Kuala_Lumpur', label: '吉隆坡' },
  { value: 'Asia/Dubai', label: '迪拜' },
  { value: 'Europe/London', label: '伦敦' },
  { value: 'Europe/Paris', label: '巴黎 / 柏林 / 罗马' },
  { value: 'America/New_York', label: '纽约' },
  { value: 'America/Los_Angeles', label: '洛杉矶' },
  { value: 'Australia/Sydney', label: '悉尼' },
  { value: 'Pacific/Auckland', label: '奥克兰' },
  { value: 'UTC', label: '协调世界时' },
]

/** 列表里显示用：已知的显示"东京 · Asia/Tokyo"，不认识的原样显示 */
export function timezoneLabel(value: string): string {
  const found = TIMEZONE_OPTIONS.find((o) => o.value === value)
  return found ? `${found.label} · ${found.value}` : value
}

/** 一行摘要里用的短名：已知的只显示中文名（"东京"），不认识的原样显示 */
export function timezoneShortLabel(value: string): string {
  return TIMEZONE_OPTIONS.find((o) => o.value === value)?.label ?? value
}
