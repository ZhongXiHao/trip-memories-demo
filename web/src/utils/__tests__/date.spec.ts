import { describe, expect, it } from 'vitest'

import { formatDateRange, fromIsoDate, toIsoDate, tripDays } from '../date'

const today = new Date(2026, 9, 24)

describe('toIsoDate / fromIsoDate', () => {
  it('round-trips using the local calendar day (no off-by-one from time zones)', () => {
    const d = new Date(2026, 0, 5)
    expect(toIsoDate(d)).toBe('2026-01-05')
    expect(toIsoDate(fromIsoDate('2026-01-05')!)).toBe('2026-01-05')
    expect(toIsoDate(new Date(2026, 11, 31, 23, 59))).toBe('2026-12-31')
  })

  it('rejects malformed input', () => {
    expect(fromIsoDate('')).toBeNull()
    expect(fromIsoDate('2026/01/05')).toBeNull()
  })
})

describe('tripDays', () => {
  it('counts both the first and the last day', () => {
    expect(tripDays('2026-10-01', '2026-10-05')).toBe(5)
    expect(tripDays('2026-10-01', '2026-10-01')).toBe(1)
  })

  it('works across month and year boundaries and leap days', () => {
    expect(tripDays('2025-12-30', '2026-01-03')).toBe(5)
    expect(tripDays('2028-02-28', '2028-03-01')).toBe(3)
  })

  it('is null when a side is missing or the order is wrong', () => {
    expect(tripDays(null, '2026-10-05')).toBeNull()
    expect(tripDays('2026-10-05', null)).toBeNull()
    expect(tripDays('2026-10-05', '2026-10-01')).toBeNull()
  })
})

describe('formatDateRange', () => {
  it('omits the year for dates in the current year', () => {
    expect(formatDateRange('2026-10-01', '2026-10-05', today)).toBe('10/01 – 10/05 · 5 天')
  })

  it('adds the year when a date is not in the current year', () => {
    expect(formatDateRange('2025-12-30', '2026-01-03', today)).toBe('2025/12/30 – 2026/01/03 · 5 天')
    expect(formatDateRange('2027-03-01', '2027-03-02', today)).toBe('2027/03/01 – 2027/03/02 · 2 天')
  })

  it('handles open-ended and undecided trips', () => {
    expect(formatDateRange('2026-10-01', null, today)).toBe('10/01 起')
    expect(formatDateRange(null, '2026-10-05', today)).toBe('至 10/05')
    expect(formatDateRange(null, null, today)).toBe('日期未定')
  })
})
