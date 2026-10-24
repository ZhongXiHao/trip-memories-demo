import { beforeEach, describe, expect, it } from 'vitest'

import { setUserId } from '@/api/session'

import router from '..'

describe('router guard', () => {
  beforeEach(async () => {
    localStorage.clear()
    await router.replace('/select-user')
  })

  it('sends an anonymous visitor to select-user and remembers where they wanted to go', async () => {
    await router.push('/')

    expect(router.currentRoute.value.name).toBe('select-user')
    expect(router.currentRoute.value.query.redirect).toBe('/')
  })

  it('lets a signed-in user through', async () => {
    setUserId(1)

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('lets anyone open the select-user page', async () => {
    await router.push('/select-user')

    expect(router.currentRoute.value.name).toBe('select-user')
  })

  it('sends unknown paths to home (and from there to select-user when anonymous)', async () => {
    await router.push('/no/such/page')

    expect(router.currentRoute.value.name).toBe('select-user')
  })
})
