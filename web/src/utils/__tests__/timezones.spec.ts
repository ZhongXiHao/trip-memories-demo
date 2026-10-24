import { describe, expect, it } from 'vitest'

import { TIMEZONE_OPTIONS, timezoneLabel } from '../timezones'

describe('TIMEZONE_OPTIONS', () => {
  it('has no duplicates', () => {
    const values = TIMEZONE_OPTIONS.map((o) => o.value)
    expect(new Set(values).size).toBe(values.length)
  })

  it('only contains zones the runtime recognises (the backend rejects anything else)', () => {
    const unknown = TIMEZONE_OPTIONS.map((o) => o.value).filter((value) => {
      try {
        new Intl.DateTimeFormat('en', { timeZone: value })
        return false
      } catch {
        return true
      }
    })
    expect(unknown).toEqual([])
  })
})

describe('timezoneLabel', () => {
  it('shows a Chinese name next to known zones and passes unknown ones through', () => {
    expect(timezoneLabel('Asia/Tokyo')).toBe('东京 · Asia/Tokyo')
    expect(timezoneLabel('Africa/Cairo')).toBe('Africa/Cairo')
  })
})
