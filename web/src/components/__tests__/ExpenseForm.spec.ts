import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { listCurrencies } from '@/api/currencies'
import type { MemberView } from '@/api/members'
import { newExpenseFormState } from '@/utils/expenseForm'

import ExpenseForm from '../ExpenseForm.vue'

vi.mock('@/api/currencies', () => ({ listCurrencies: vi.fn() }))

enableAutoUnmount(afterEach)

const members: MemberView[] = [
  { memberId: 3, userId: 1, nickname: 'Ken', role: 'owner', status: 'active', joinedAt: '2026-09-01T08:00:00Z' },
]

beforeEach(() => {
  setActivePinia(createPinia())
  vi.mocked(listCurrencies).mockResolvedValue([
    { code: 'HKD', name: '港币', roundingUnit: 1 },
    { code: 'JPY', name: '日元', roundingUnit: 100 },
    { code: 'USD', name: '美元', roundingUnit: 1 },
  ])
})

async function open(currency: string, currencyOptions: string[]) {
  const wrapper = mount(ExpenseForm, {
    props: {
      initial: newExpenseFormState({
        myMemberId: 3,
        activeMemberIds: [3],
        currency,
        timezone: 'Asia/Tokyo',
        now: { date: '2026-10-03', time: '12:00' },
      }),
      members,
      allMembers: members,
      myMemberId: 3,
      preview: null,
      confirmLabel: '确认记账',
      currencyOptions,
    },
  })
  await flushPromises()
  return wrapper
}

function chipCodes(wrapper: Awaited<ReturnType<typeof open>>) {
  return wrapper.findAll('.currency-chips [role="radio"]').map((b) => b.text())
}

describe('ExpenseForm currency shortcuts', () => {
  it('offers only the shortcuts that are in the currency list', async () => {
    const wrapper = await open('JPY', ['JPY', 'XYZ', 'HKD'])
    expect(chipCodes(wrapper)).toEqual(['JPY', 'HKD'])
    expect(wrapper.find('.currency-chips [aria-checked="true"]').text()).toBe('JPY')
  })

  it('switches the currency of the expense when a shortcut is tapped', async () => {
    const wrapper = await open('JPY', ['JPY', 'HKD'])
    await wrapper.findAll('.currency-chips button')[1]!.trigger('click')
    expect(wrapper.find('.amount-field__code').text()).toBe('HKD')
    expect(wrapper.find('.currency-chips [aria-checked="true"]').text()).toBe('HKD')
  })

  it('still shows the current currency as selected when it is not a shortcut', async () => {
    const wrapper = await open('USD', ['JPY', 'HKD'])
    expect(chipCodes(wrapper)).toEqual(['JPY', 'HKD', 'USD'])
    expect(wrapper.find('.currency-chips [aria-checked="true"]').text()).toBe('USD')
  })
})
