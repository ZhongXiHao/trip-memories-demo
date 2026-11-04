import { describe, expect, it } from 'vitest'

import type { DebtView } from '@/api/debts'

import {
  buildRepaymentItems,
  buildRepaymentRequest,
  impliedRateText,
  paidCurrencyOptions,
  repayCandidates,
  totalPaidMinor,
  type RepayLineInput,
} from '../repaymentForm'

const units: Record<string, number> = { JPY: 100, KRW: 100, HKD: 1, TWD: 1 }
const unitOf = (code: string) => units[code]

function debt(from: number, to: number, currency: string, amountMinor = 100000, pendingMinor = 0): DebtView {
  return { fromMemberId: from, toMemberId: to, currency, amountMinor, pendingMinor }
}

function line(currency: string, clearedText: string, paidText = '', maxMinor = 1_000_000): RepayLineInput {
  return { currency, maxMinor, clearedText, paidText }
}

describe('repayCandidates', () => {
  it('lists the same payer → receiver debts in other currencies after the one it was opened from', () => {
    const entry = debt(5, 3, 'JPY')
    const all = [debt(5, 3, 'HKD'), entry, debt(5, 3, 'TWD'), debt(3, 5, 'KRW'), debt(5, 7, 'HKD')]

    expect(repayCandidates(all, entry).map((d) => d.currency)).toEqual(['JPY', 'HKD', 'TWD'])
  })

  it('leaves out debts that are already fully marked', () => {
    const entry = debt(5, 3, 'JPY')
    const all = [entry, debt(5, 3, 'HKD', 5000, 5000)]

    expect(repayCandidates(all, entry)).toEqual([entry])
  })

  it('still works when the list does not contain the entry', () => {
    const entry = debt(5, 3, 'JPY')

    expect(repayCandidates([], entry)).toEqual([entry])
  })
})

describe('paidCurrencyOptions', () => {
  it('puts the debt currencies first, then the rest of the currency list, without repeats', () => {
    expect(paidCurrencyOptions(['JPY', 'HKD'], ['HKD', 'JPY', 'KRW', 'TWD'])).toEqual(['JPY', 'HKD', 'KRW', 'TWD'])
  })

  it('falls back to the debt currencies while the list is not loaded', () => {
    expect(paidCurrencyOptions(['JPY'], [])).toEqual(['JPY'])
  })
})

describe('buildRepaymentItems', () => {
  it('builds a same-currency item: paid equals cleared, the paid text is ignored', () => {
    expect(buildRepaymentItems([line('JPY', '2,400', 'junk')], 'JPY', unitOf)).toEqual({
      ok: true,
      items: [{ clearedCurrency: 'JPY', clearedAmountMinor: 240000, paidAmountMinor: 240000 }],
    })
  })

  it('builds several items paid in one of their currencies', () => {
    const result = buildRepaymentItems([line('JPY', '8200', '426.40'), line('HKD', '50.5')], 'HKD', unitOf)

    expect(result).toEqual({
      ok: true,
      items: [
        { clearedCurrency: 'JPY', clearedAmountMinor: 820000, paidAmountMinor: 42640 },
        { clearedCurrency: 'HKD', clearedAmountMinor: 5050, paidAmountMinor: 5050 },
      ],
    })
  })

  it('builds a cross-currency item paid in a currency none of the debts use', () => {
    expect(buildRepaymentItems([line('JPY', '1000', '210')], 'TWD', unitOf)).toEqual({
      ok: true,
      items: [{ clearedCurrency: 'JPY', clearedAmountMinor: 100000, paidAmountMinor: 21000 }],
    })
  })

  it('rounds the cleared amount by the cleared currency and the paid amount by the paid currency', () => {
    // JPY 只能整数；KRW 也是整数，所以实付 0.5 不行。HKD 销帐可以到分
    const result = buildRepaymentItems([line('JPY', '1000.5', '1'), line('HKD', '10.25', '0.5')], 'KRW', unitOf)

    expect(result).toEqual({
      ok: false,
      errors: { JPY: { cleared: '这个币别只能填整数' }, HKD: { paid: '这个币别只能填整数' } },
    })
  })

  it('lets the paid amount have cents when paid in a currency that allows them', () => {
    const result = buildRepaymentItems([line('JPY', '1000', '52.13')], 'HKD', unitOf)

    expect(result.ok).toBe(true)
  })

  it('asks for the paid amount when paying in another currency', () => {
    expect(buildRepaymentItems([line('JPY', '1000', '')], 'HKD', unitOf)).toEqual({
      ok: false,
      errors: { JPY: { paid: '请填写金额' } },
    })
  })

  it('does not let the cleared amount go above what can still be repaid', () => {
    expect(buildRepaymentItems([line('JPY', '2500', '', 240000)], 'JPY', unitOf)).toEqual({
      ok: false,
      errors: { JPY: { cleared: '最多能还 JPY 2,400' } },
    })
  })

  it('needs amounts above zero', () => {
    expect(buildRepaymentItems([line('JPY', '0', '0')], 'HKD', unitOf)).toEqual({
      ok: false,
      errors: { JPY: { cleared: '金额要大于 0', paid: '金额要大于 0' } },
    })
  })

  it('needs at least one debt to clear', () => {
    expect(buildRepaymentItems([], 'JPY', unitOf)).toEqual({
      ok: false,
      errors: {},
      formError: '至少选一笔要结清的欠款',
    })
  })
})

describe('totalPaidMinor', () => {
  it('adds up what is paid for each item, in the paid currency', () => {
    expect(totalPaidMinor([line('JPY', '8200', '426.40'), line('HKD', '50.5')], 'HKD', unitOf)).toBe(47690)
  })

  it('is the cleared amount for a single same-currency item', () => {
    expect(totalPaidMinor([line('JPY', '2400')], 'JPY', unitOf)).toBe(240000)
  })

  it('is unknown while an item is not filled in properly', () => {
    expect(totalPaidMinor([line('JPY', '8200', ''), line('HKD', '50.5')], 'HKD', unitOf)).toBeNull()
    expect(totalPaidMinor([line('JPY', '8200', '1.5')], 'KRW', unitOf)).toBeNull()
    expect(totalPaidMinor([], 'HKD', unitOf)).toBeNull()
  })
})

describe('impliedRateText', () => {
  it('derives the rate from paid / cleared, to 4 significant digits', () => {
    expect(impliedRateText('JPY', 820000, 'HKD', 42640)).toBe('1 JPY ≈ 0.052 HKD')
    expect(impliedRateText('HKD', 10000, 'JPY', 192300)).toBe('1 HKD ≈ 19.23 JPY')
  })

  it('is empty when an amount is missing', () => {
    expect(impliedRateText('JPY', 0, 'HKD', 100)).toBe('')
  })
})

describe('buildRepaymentRequest', () => {
  const items = [{ clearedCurrency: 'JPY', clearedAmountMinor: 100000, paidAmountMinor: 5200 }]

  it('puts the fields together and trims the note', () => {
    expect(
      buildRepaymentRequest({
        clientUuid: 'u',
        fromMemberId: 5,
        toMemberId: 3,
        paidCurrency: 'HKD',
        items,
        note: '  转账 ',
      }),
    ).toEqual({ clientUuid: 'u', fromMemberId: 5, toMemberId: 3, paidCurrency: 'HKD', items, note: '转账' })
  })

  it('leaves the note out when it is blank', () => {
    const request = buildRepaymentRequest({
      clientUuid: 'u',
      fromMemberId: 5,
      toMemberId: 3,
      paidCurrency: 'HKD',
      items,
      note: '   ',
    })

    expect(request).not.toHaveProperty('note')
  })
})
