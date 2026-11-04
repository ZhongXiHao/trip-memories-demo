import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'

import type { MemberView } from '@/api/members'

import TripMembers from '../TripMembers.vue'

enableAutoUnmount(afterEach)

const members: MemberView[] = [
  { memberId: 3, userId: 1, nickname: 'Ken', role: 'owner', status: 'active', joinedAt: '2026-09-01T08:00:00Z' },
  { memberId: 5, userId: 2, nickname: 'Amy', role: 'member', status: 'active', joinedAt: '2026-09-01T08:00:00Z' },
]

describe('TripMembers', () => {
  it('opens the member menu on body, outside the swipeable tab, so fixed positioning is not thrown off', async () => {
    // 模拟可左右滑动的标签：带 transform 的容器
    const tab = document.createElement('div')
    tab.style.transform = 'translateX(0)'
    document.body.appendChild(tab)
    const wrapper = mount(TripMembers, {
      props: { active: members, left: [], myMemberId: 3, isOwner: true },
      attachTo: tab,
    })

    await wrapper.get('button[aria-label="管理 Amy"]').trigger('click')
    await flushPromises()

    const sheet = document.querySelector('.van-action-sheet')
    expect(sheet).not.toBeNull()
    expect(tab.contains(sheet)).toBe(false)
    expect(sheet!.textContent).toContain('移出行程')
    tab.remove()
  })
})
