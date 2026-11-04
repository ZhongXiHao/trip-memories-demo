import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import CurrencyChips from '../CurrencyChips.vue'

function chips(options: string[], selected: string, extra: { disabled?: boolean; label?: string } = {}) {
  return mount(CurrencyChips, { props: { options, selected, ...extra } })
}

function codesOf(wrapper: ReturnType<typeof chips>) {
  return wrapper.findAll('[role="radio"]').map((b) => b.text())
}

describe('CurrencyChips', () => {
  it('shows one radio per option, in order, with the selected one checked', () => {
    const wrapper = chips(['JPY', 'HKD', 'USD'], 'HKD')
    expect(codesOf(wrapper)).toEqual(['JPY', 'HKD', 'USD'])
    const checked = wrapper.findAll('[role="radio"]').map((b) => b.attributes('aria-checked'))
    expect(checked).toEqual(['false', 'true', 'false'])
    expect(wrapper.find('.currency-chips__item--active').text()).toBe('HKD')
  })

  it('emits select with the code when another currency is tapped', async () => {
    const wrapper = chips(['JPY', 'HKD'], 'JPY')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('select')).toEqual([['HKD']])
  })

  it('does not emit when the selected currency is tapped again', async () => {
    const wrapper = chips(['JPY', 'HKD'], 'JPY')
    await wrapper.findAll('button')[0]!.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('adds the selected currency at the end when it is not one of the options', () => {
    const wrapper = chips(['JPY', 'HKD'], 'EUR')
    expect(codesOf(wrapper)).toEqual(['JPY', 'HKD', 'EUR'])
    expect(wrapper.find('[aria-checked="true"]').text()).toBe('EUR')
  })

  it('checks nothing when nothing is selected', () => {
    const wrapper = chips(['JPY'], '')
    expect(codesOf(wrapper)).toEqual(['JPY'])
    expect(wrapper.find('[aria-checked="true"]').exists()).toBe(false)
  })

  it('paints each chip with its currency color', () => {
    const wrapper = chips(['JPY', 'XYZ'], 'JPY')
    const styles = wrapper.findAll('button').map((b) => b.attributes('style'))
    expect(styles[0]).toContain('var(--tm-cur-jpy)')
    expect(styles[1]).toContain('var(--tm-cur-other)')
  })

  it('does not emit while disabled', async () => {
    const wrapper = chips(['JPY', 'HKD'], 'JPY', { disabled: true })
    expect(wrapper.findAll('button').every((b) => b.attributes('disabled') !== undefined)).toBe(true)
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('names the group for assistive technology', () => {
    expect(chips(['JPY'], 'JPY').attributes('aria-label')).toBe('币别')
    expect(chips(['JPY'], 'JPY', { label: '这笔的币别' }).attributes('aria-label')).toBe('这笔的币别')
  })
})
