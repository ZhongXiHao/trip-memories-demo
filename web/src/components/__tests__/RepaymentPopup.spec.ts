import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { DebtView } from '@/api/debts'
import { ApiError } from '@/api/errors'
import type { MemberView } from '@/api/members'
import { createRepayment } from '@/api/repayments'
import { useCurrencyStore } from '@/stores/currencies'

import RepaymentPopup from '../RepaymentPopup.vue'

vi.mock('@/api/repayments', () => ({ createRepayment: vi.fn() }))
// 提示条是另外挂在 body 上的应用，这里只关心弹层本身
vi.mock('vant', async (importOriginal) => ({ ...(await importOriginal<typeof import('vant')>()), showToast: vi.fn() }))

enableAutoUnmount(afterEach)

const create = vi.mocked(createRepayment)

const members: MemberView[] = [
  { memberId: 3, userId: 1, nickname: 'Ken', role: 'owner', status: 'active', joinedAt: '2026-09-01T08:00:00Z' },
  { memberId: 5, userId: 2, nickname: 'Amy', role: 'member', status: 'active', joinedAt: '2026-09-01T08:00:00Z' },
]

// Amy（5）欠 Ken（3）JPY 3,400，其中 1,000 已标记待确认：最多还能还 2,400
const debt: DebtView = { fromMemberId: 5, toMemberId: 3, currency: 'JPY', amountMinor: 340000, pendingMinor: 100000 }

async function open(myMemberId: number) {
  const wrapper = mount(RepaymentPopup, {
    props: { show: false, tripId: 1, debt, members, myMemberId },
    attachTo: document.body,
  })
  await wrapper.setProps({ show: true })
  await flushPromises()
  return wrapper
}

function amountInput() {
  return document.querySelector<HTMLInputElement>('.amount input')!
}

async function submit() {
  document.querySelector<HTMLFormElement>('form.popup')!.dispatchEvent(new Event('submit'))
  await flushPromises()
}

describe('RepaymentPopup', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const currencies = useCurrencyStore()
    currencies.items = [{ code: 'JPY', name: '日元', roundingUnit: 100 }]
    currencies.loaded = true
    create.mockReset()
  })

  it('fills in what can still be repaid: the debt minus what is already pending', async () => {
    await open(5)

    expect(amountInput().value).toBe('2400')
  })

  it('sends a same-currency repayment from the debt and closes on success', async () => {
    create.mockResolvedValue({ group: { settlementGroupId: 'g', items: [] }, replayed: false })
    const wrapper = await open(5)

    await submit()

    expect(create).toHaveBeenCalledTimes(1)
    const [tripId, request] = create.mock.calls[0]!
    expect(tripId).toBe(1)
    expect(request).toMatchObject({
      fromMemberId: 5,
      toMemberId: 3,
      paidCurrency: 'JPY',
      items: [{ clearedCurrency: 'JPY', clearedAmountMinor: 240000, paidAmountMinor: 240000 }],
    })
    expect(request).not.toHaveProperty('note')
    expect(wrapper.emitted('created')).toHaveLength(1)
    const shows = wrapper.emitted('update:show') ?? []
    expect(shows[shows.length - 1]).toEqual([false])
  })

  it('does not send an amount above the limit', async () => {
    await open(5)
    amountInput().value = '2500'
    amountInput().dispatchEvent(new Event('input'))

    await submit()

    expect(create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('最多能还 JPY 2,400')
  })

  it('retries with the same clientUuid after a network failure, so the server sees one intent', async () => {
    create.mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'down'))
    create.mockResolvedValueOnce({ group: { settlementGroupId: 'g', items: [] }, replayed: true })
    const wrapper = await open(5)

    await submit()
    await submit()

    expect(create).toHaveBeenCalledTimes(2)
    expect(create.mock.calls[1]![1].clientUuid).toBe(create.mock.calls[0]![1].clientUuid)
    expect(wrapper.emitted('created')).toHaveLength(1)
  })

  it('uses a new clientUuid the next time it is opened', async () => {
    create.mockResolvedValue({ group: { settlementGroupId: 'g', items: [] }, replayed: false })
    const wrapper = await open(5)
    await submit()
    await wrapper.setProps({ show: false })
    await wrapper.setProps({ show: true })
    await flushPromises()

    await submit()

    expect(create.mock.calls[1]![1].clientUuid).not.toBe(create.mock.calls[0]![1].clientUuid)
  })

  it('closes and asks for a refresh when the server says the debt is no longer that large', async () => {
    create.mockRejectedValue(new ApiError(400, 'REPAYMENT_EXCEEDS_DEBT', 'x'))
    const wrapper = await open(5)

    await submit()

    expect(wrapper.emitted('stale')).toHaveLength(1)
    expect(wrapper.emitted('created')).toBeUndefined()
  })

  it('lets the creditor record it as received', async () => {
    await open(3)

    expect(document.body.textContent).toContain('确认已收到')
    expect(document.body.textContent).toContain('Amy 欠你')
  })
})
