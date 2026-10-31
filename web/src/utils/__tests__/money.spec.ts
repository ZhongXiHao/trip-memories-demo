import { describe, expect, it } from 'vitest'

import { amountProblemText, formatMinor, fractionDigits, minorToInput, parseAmount } from '../money'

describe('fractionDigits', () => {
  it('derives the decimals from the rounding unit', () => {
    expect([100, 10, 1].map(fractionDigits)).toEqual([0, 1, 2])
  })

  it('falls back to 2 decimals for a unit it does not know, so nothing gets cut off', () => {
    expect(fractionDigits(5)).toBe(2)
  })
})

describe('formatMinor', () => {
  it('shows whole-unit currencies without decimals (JPY 1200 is 120000)', () => {
    expect(formatMinor(120000, 100)).toBe('1,200')
  })

  it('shows cent currencies with 2 decimals', () => {
    expect(formatMinor(123450, 1)).toBe('1,234.50')
    expect(formatMinor(5, 1)).toBe('0.05')
    expect(formatMinor(0, 1)).toBe('0.00')
  })

  it('groups thousands', () => {
    const cases = [
      [100, 100, '1'],
      [100000, 100, '1,000'],
      [123456789, 1, '1,234,567.89'],
      [100000000000, 1, '1,000,000,000.00'],
    ] as const
    const wrong = cases
      .map(([minor, unit, expected]) => ({ minor, expected, got: formatMinor(minor, unit) }))
      .filter((c) => c.got !== c.expected)
    expect(wrong).toEqual([])
  })

  it('keeps the sign of a negative amount', () => {
    expect(formatMinor(-1250, 1)).toBe('-12.50')
  })

  it('without a rounding unit (currency list not loaded) never hides a fraction', () => {
    expect(formatMinor(120000)).toBe('1,200')
    expect(formatMinor(1250)).toBe('12.50')
  })
})

describe('minorToInput', () => {
  it('gives a plain number for the input box, without grouping', () => {
    expect(minorToInput(12345600, 100)).toBe('123456')
    expect(minorToInput(123450, 1)).toBe('1234.50')
  })

  it('round-trips through parseAmount', () => {
    const samples: Array<[number, number]> = [
      [120000, 100],
      [1, 1],
      [99999999, 1],
      [33400, 100],
    ]
    const broken = samples.filter(([minor, unit]) => {
      const parsed = parseAmount(minorToInput(minor, unit), unit)
      return !parsed.ok || parsed.minor !== minor
    })
    expect(broken).toEqual([])
  })
})

describe('parseAmount', () => {
  it('turns major units into minor × 100 without floating point errors', () => {
    expect(parseAmount('1200', 100)).toEqual({ ok: true, minor: 120000 })
    expect(parseAmount('0.1', 1)).toEqual({ ok: true, minor: 10 })
    expect(parseAmount('0.29', 1)).toEqual({ ok: true, minor: 29 })
    expect(parseAmount('1,234.56', 1)).toEqual({ ok: true, minor: 123456 })
    expect(parseAmount(' 12. ', 1)).toEqual({ ok: true, minor: 1200 })
    expect(parseAmount('.5', 1)).toEqual({ ok: true, minor: 50 })
  })

  it('rejects decimals a whole-unit currency cannot have (AMOUNT_NOT_ROUNDABLE)', () => {
    expect(parseAmount('1250.5', 100)).toEqual({ ok: false, problem: 'too-precise' })
    expect(parseAmount('1250.00', 100)).toEqual({ ok: true, minor: 125000 })
  })

  it('rejects more than two decimals for a cent currency', () => {
    expect(parseAmount('1.234', 1)).toEqual({ ok: false, problem: 'too-precise' })
    expect(parseAmount('1.230', 1)).toEqual({ ok: true, minor: 123 })
  })

  it('reports empty, garbage, zero and huge inputs', () => {
    const cases = [
      ['', 'empty'],
      ['   ', 'empty'],
      ['abc', 'invalid'],
      ['-5', 'invalid'],
      ['1.2.3', 'invalid'],
      ['.', 'invalid'],
      ['1e3', 'invalid'],
      ['0', 'not-positive'],
      ['0.00', 'not-positive'],
      ['99999999999999999', 'too-large'],
    ] as const
    const wrong = cases
      .map(([input, problem]) => ({ input, problem, got: parseAmount(input, 1) }))
      .filter((c) => c.got.ok || c.got.problem !== c.problem)
    expect(wrong).toEqual([])
  })

  it('allows zero when asked (one person can owe nothing in a custom split)', () => {
    expect(parseAmount('0', 100, { allowZero: true })).toEqual({ ok: true, minor: 0 })
  })
})

describe('amountProblemText', () => {
  it('explains the decimals rule per currency', () => {
    expect(amountProblemText('too-precise', 100)).toBe('这个币别只能填整数')
    expect(amountProblemText('too-precise', 1)).toBe('最多 2 位小数')
  })
})
