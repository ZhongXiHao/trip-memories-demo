import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
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

async function open(myMemberId: number, debts?: DebtView[]) {
  const wrapper = mount(RepaymentPopup, {
    props: { show: false, tripId: 1, debt, debts, members, myMemberId },
    attachTo: document.body,
  })
  await wrapper.setProps({ show: true })
  await flushPromises()
  return wrapper
}

function amountInput() {
  return document.querySelector<HTMLInputElement>('.amount input')!
}

function setInput(input: HTMLInputElement, value: string) {
  input.value = value
  input.dispatchEvent(new Event('input'))
}

async function choosePaidCurrency(wrapper: VueWrapper, code: string) {
  document.querySelector<HTMLElement>('.paid-currency')!.click()
  await flushPromises()
  wrapper.findComponent({ name: 'van-picker' }).vm.$emit('confirm', { selectedValues: [code] })
  await flushPromises()
}

async function submit() {
  document.querySelector<HTMLFormElement>('form.popup')!.dispatchEvent(new Event('submit'))
  await flushPromises()
}

describe('RepaymentPopup', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    const currencies = useCurrencyStore()
    currencies.items = [
      { code: 'HKD', name: '港币', roundingUnit: 1 },
      { code: 'JPY', name: '日元', roundingUnit: 100 },
      { code: 'TWD', name: '新台币', roundingUnit: 1 },
    ]
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

  describe('several currencies in one transfer', () => {
    // 同一方向还有 HKD 50.50；反方向的 TWD、别人之间的不算
    const hkd: DebtView = { fromMemberId: 5, toMemberId: 3, currency: 'HKD', amountMinor: 5050, pendingMinor: 0 }
    const others: DebtView[] = [
      hkd,
      debt,
      { fromMemberId: 3, toMemberId: 5, currency: 'TWD', amountMinor: 10000, pendingMinor: 0 },
      { fromMemberId: 5, toMemberId: 7, currency: 'TWD', amountMinor: 10000, pendingMinor: 0 },
    ]

    function checkboxes() {
      return Array.from(document.querySelectorAll<HTMLElement>('.line__check'))
    }

    it('lists the same-direction debts and only ticks the one it was opened from', async () => {
      await open(5, others)

      expect(checkboxes().map((c) => c.textContent?.replace(/\s+/g, ''))).toEqual(['JPY3,400', 'HKD50.50'])
      expect(checkboxes().map((c) => c.getAttribute('aria-checked'))).toEqual(['true', 'false'])
      expect(document.querySelectorAll('.amount')).toHaveLength(1)
    })

    it('clears two currencies with one transfer in one of them', async () => {
      create.mockResolvedValue({ group: { settlementGroupId: 'g', items: [] }, replayed: false })
      const wrapper = await open(5, others)
      checkboxes()[1]!.click()
      await flushPromises()
      await choosePaidCurrency(wrapper, 'HKD')
      // JPY 那一项要另填实付（HKD），HKD 那一项实付 = 销帐
      expect(document.querySelectorAll('.paid')).toHaveLength(1)
      setInput(document.querySelector<HTMLInputElement>('.paid input')!, '124.80')
      await flushPromises()

      expect(document.body.textContent).toContain('1 JPY ≈ 0.052 HKD')
      expect(document.querySelector('.total')?.textContent?.replace(/\s+/g, '')).toContain('HKD175.30')

      await submit()

      expect(create).toHaveBeenCalledTimes(1)
      expect(create.mock.calls[0]![1]).toMatchObject({
        fromMemberId: 5,
        toMemberId: 3,
        paidCurrency: 'HKD',
        items: [
          { clearedCurrency: 'JPY', clearedAmountMinor: 240000, paidAmountMinor: 12480 },
          { clearedCurrency: 'HKD', clearedAmountMinor: 5050, paidAmountMinor: 5050 },
        ],
      })
    })

    it('pays a single debt in another currency', async () => {
      create.mockResolvedValue({ group: { settlementGroupId: 'g', items: [] }, replayed: false })
      const wrapper = await open(5)
      await choosePaidCurrency(wrapper, 'TWD')
      setInput(amountInput(), '1000')
      setInput(document.querySelector<HTMLInputElement>('.paid input')!, '210')

      await submit()

      expect(create.mock.calls[0]![1]).toMatchObject({
        paidCurrency: 'TWD',
        items: [{ clearedCurrency: 'JPY', clearedAmountMinor: 100000, paidAmountMinor: 21000 }],
      })
    })

    it('checks the paid amount against the paid currency before sending', async () => {
      const wrapper = await open(5)
      await choosePaidCurrency(wrapper, 'HKD')
      setInput(document.querySelector<HTMLInputElement>('.paid input')!, '12.345')

      await submit()

      expect(create).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('最多 2 位小数')
    })

    it('needs at least one debt ticked', async () => {
      await open(5, others)
      checkboxes()[0]!.click()
      await flushPromises()

      await submit()

      expect(create).not.toHaveBeenCalled()
      expect(document.body.textContent).toContain('至少选一笔要结清的欠款')
    })

    it('shows a server amount error under the form when it cannot tell which item it is about', async () => {
      create.mockRejectedValue(new ApiError(400, 'AMOUNT_NOT_ROUNDABLE', 'x'))
      const wrapper = await open(5, others)
      checkboxes()[1]!.click()
      await flushPromises()
      // 转账币别还是 JPY：HKD 那一项要填实付（JPY）
      setInput(document.querySelector<HTMLInputElement>('.paid input')!, '980')

      await submit()

      expect(create).toHaveBeenCalledTimes(1)
      expect(document.querySelector('.popup__error')?.textContent).toBeTruthy()
      expect(wrapper.emitted('stale')).toBeUndefined()
    })
  })
})
