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

  it('remembers a deep link so the user lands back on it after choosing an identity', async () => {
    await router.push('/trips/12')

    expect(router.currentRoute.value.name).toBe('select-user')
    expect(router.currentRoute.value.query.redirect).toBe('/trips/12')
  })
})

describe('bottom tab bar (mobile)', () => {
  const tabNames = () =>
    router
      .getRoutes()
      .filter((r) => r.meta.tab)
      .map((r) => r.name)
      .sort()

  it('only the two top-level pages (trips, me) show it', () => {
    expect(tabNames()).toEqual(['home', 'me'])
  })

  it('sub-pages (detail, create, edit) and the identity page do not show it', () => {
    const showingIt = ['trip', 'trip-new', 'trip-edit', 'select-user'].filter(
      (name) => router.getRoutes().find((r) => r.name === name)?.meta.tab,
    )
    expect(showingIt).toEqual([])
  })

  it('/me needs an identity like every other page except select-user', async () => {
    localStorage.clear()
    await router.replace('/select-user')

    await router.push('/me')

    expect(router.currentRoute.value.name).toBe('select-user')
    expect(router.currentRoute.value.query.redirect).toBe('/me')
  })

  it('/me opens for a signed-in user', async () => {
    localStorage.clear()
    setUserId(1)
    await router.replace('/')

    await router.push('/me')

    expect(router.currentRoute.value.name).toBe('me')
  })
})

describe('trip routes', () => {
  beforeEach(async () => {
    localStorage.clear()
    setUserId(1)
    await router.replace('/')
  })

  it('/trips/new is the create page, not a trip with id "new"', async () => {
    await router.push('/trips/new')

    expect(router.currentRoute.value.name).toBe('trip-new')
  })

  it('/trips/12 is the detail page and passes the id as a number', async () => {
    await router.push('/trips/12')

    const route = router.currentRoute.value
    expect(route.name).toBe('trip')
    expect(route.params.tripId).toBe('12')
    const props = router.getRoutes().find((r) => r.name === 'trip')!.props.default as (r: typeof route) => unknown
    expect(props(route)).toEqual({ tripId: 12 })
  })

  it('a non-numeric id does not match the detail page', async () => {
    await router.push('/trips/abc')

    expect(router.currentRoute.value.name).toBe('home')
  })

  it('/trips/12/edit is the edit page and passes the id as a number', async () => {
    await router.push('/trips/12/edit')

    const route = router.currentRoute.value
    expect(route.name).toBe('trip-edit')
    const props = router.getRoutes().find((r) => r.name === 'trip-edit')!.props.default as (r: typeof route) => unknown
    expect(props(route)).toEqual({ tripId: 12 })
  })

  it('the edit page also only accepts a numeric id', async () => {
    await router.push('/trips/abc/edit')

    expect(router.currentRoute.value.name).toBe('home')
  })
})
