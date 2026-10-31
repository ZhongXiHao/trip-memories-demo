import { describe, expect, it } from 'vitest'

import { fromLocalDateTime, isKnownTimeZone, nowIn, shortLocalDate, toLocalDateTime, weekdayOf } from '../zonedTime'

describe('toLocalDateTime', () => {
  it('shows the time of the expense time zone, not of the browser', () => {
    expect(toLocalDateTime('2026-10-03T11:00:00Z', 'Asia/Tokyo')).toEqual({ date: '2026-10-03', time: '20:00' })
    expect(toLocalDateTime('2026-10-03T16:30:00Z', 'Asia/Tokyo')).toEqual({ date: '2026-10-04', time: '01:30' })
    expect(toLocalDateTime('2026-10-03T02:00:00Z', 'America/Los_Angeles')).toEqual({
      date: '2026-10-02',
      time: '19:00',
    })
  })

  it('falls back to UTC for a zone the browser does not know', () => {
    expect(isKnownTimeZone('Mars/Olympus')).toBe(false)
    expect(toLocalDateTime('2026-10-03T11:00:00Z', 'Mars/Olympus')).toEqual({ date: '2026-10-03', time: '11:00' })
  })
})

describe('fromLocalDateTime', () => {
  it('turns a local time into UTC with Z and no milliseconds', () => {
    expect(fromLocalDateTime({ date: '2026-10-03', time: '20:00' }, 'Asia/Tokyo')).toBe('2026-10-03T11:00:00Z')
    expect(fromLocalDateTime({ date: '2026-01-01', time: '00:15' }, 'UTC')).toBe('2026-01-01T00:15:00Z')
  })

  it('round-trips through toLocalDateTime across zones and seasons', () => {
    const zones = ['Asia/Tokyo', 'Europe/London', 'America/New_York', 'Australia/Sydney', 'Asia/Kolkata']
    const moments = ['2026-01-15T03:45:00Z', '2026-07-15T22:10:00Z', '2026-12-31T23:59:00Z']
    const broken = zones.flatMap((zone) =>
      moments
        .map((iso) => ({ zone, iso, back: fromLocalDateTime(toLocalDateTime(iso, zone), zone) }))
        .filter((c) => c.back !== c.iso),
    )
    expect(broken).toEqual([])
  })

  it('uses the right offset on both sides of a daylight saving change', () => {
    // 纽约 2026-03-08 凌晨 2 点进入夏令时（-05:00 → -04:00）
    expect(fromLocalDateTime({ date: '2026-03-08', time: '01:30' }, 'America/New_York')).toBe('2026-03-08T06:30:00Z')
    expect(fromLocalDateTime({ date: '2026-03-08', time: '03:30' }, 'America/New_York')).toBe('2026-03-08T07:30:00Z')
  })

  it('moves a time that does not exist (skipped by daylight saving) forward', () => {
    expect(fromLocalDateTime({ date: '2026-03-08', time: '02:30' }, 'America/New_York')).toBe('2026-03-08T07:30:00Z')
  })

  it('takes the first of two identical wall times when the clock goes back', () => {
    // 纽约 2026-11-01 凌晨 2 点回到冬令时，1:30 出现两次，取先出现的（夏令时那次）
    expect(fromLocalDateTime({ date: '2026-11-01', time: '01:30' }, 'America/New_York')).toBe('2026-11-01T05:30:00Z')
  })

  it('returns null for malformed input', () => {
    expect(fromLocalDateTime({ date: '2026/10/03', time: '20:00' }, 'UTC')).toBeNull()
    expect(fromLocalDateTime({ date: '2026-10-03', time: '8pm' }, 'UTC')).toBeNull()
  })
})

describe('nowIn', () => {
  it('gives the current local time of the zone', () => {
    expect(nowIn('Asia/Tokyo', new Date('2026-10-31T15:05:00Z'))).toEqual({ date: '2026-11-01', time: '00:05' })
  })
})

describe('weekdayOf / shortLocalDate', () => {
  it('names the weekday of a calendar date', () => {
    expect(weekdayOf('2026-10-03')).toBe('周六')
    expect(weekdayOf('bad')).toBe('')
  })

  it('drops the year only for this year', () => {
    const today = new Date(2026, 9, 31)
    expect(shortLocalDate('2026-10-03', today)).toBe('10/03')
    expect(shortLocalDate('2025-12-30', today)).toBe('2025/12/30')
  })
})
