import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/errors'
import { getUserId, setUserId } from '@/api/session'
import * as usersApi from '@/api/users'

import { useSessionStore } from '../session'

vi.mock('@/api/users')

const ken = { id: 1, nickname: 'Ken' }
const amy = { id: 2, nickname: 'Amy' }

describe('session store', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.resetAllMocks()
    setActivePinia(createPinia())
  })

  it('signs in: remembers the id, loads the user, saves the account', async () => {
    vi.mocked(usersApi.getMe).mockResolvedValue(ken)
    const session = useSessionStore()

    await session.signInAs(1)

    expect(session.userId).toBe(1)
    expect(getUserId()).toBe(1)
    expect(session.me).toEqual(ken)
    expect(session.accounts).toEqual([ken])
  })

  it('puts the most recently used account first without duplicates', async () => {
    const session = useSessionStore()
    vi.mocked(usersApi.getMe).mockResolvedValueOnce(ken)
    await session.signInAs(1)
    vi.mocked(usersApi.getMe).mockResolvedValueOnce(amy)
    await session.signInAs(2)
    vi.mocked(usersApi.getMe).mockResolvedValueOnce(ken)
    await session.signInAs(1)

    expect(session.accounts.map((a) => a.id)).toEqual([1, 2])
  })

  it('keeps accounts across a reload', async () => {
    vi.mocked(usersApi.getMe).mockResolvedValue(ken)
    await useSessionStore().signInAs(1)

    setActivePinia(createPinia())
    const reloaded = useSessionStore()

    expect(reloaded.accounts).toEqual([ken])
    expect(reloaded.userId).toBe(1)
  })

  it('restores the previous identity when the new one is rejected', async () => {
    const session = useSessionStore()
    vi.mocked(usersApi.getMe).mockResolvedValueOnce(ken)
    await session.signInAs(1)
    vi.mocked(usersApi.getMe).mockRejectedValueOnce(new ApiError(401, 'UNAUTHENTICATED', 'nope'))

    await expect(session.signInAs(99)).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })

    expect(session.userId).toBe(1)
    expect(getUserId()).toBe(1)
    expect(session.accounts).toEqual([ken])
  })

  it('ends up signed out when the first-ever sign-in is rejected', async () => {
    vi.mocked(usersApi.getMe).mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED', 'nope'))
    const session = useSessionStore()

    await expect(session.signInAs(99)).rejects.toBeInstanceOf(ApiError)

    expect(session.userId).toBeNull()
    expect(getUserId()).toBeNull()
  })

  it('register creates the user, then signs in as it', async () => {
    vi.mocked(usersApi.registerDevUser).mockResolvedValue(amy)
    vi.mocked(usersApi.getMe).mockResolvedValue(amy)
    const session = useSessionStore()

    await session.register('Amy')

    expect(usersApi.registerDevUser).toHaveBeenCalledWith('Amy')
    expect(session.userId).toBe(2)
    expect(session.me).toEqual(amy)
  })

  it('signOut clears the identity but keeps the saved accounts', async () => {
    vi.mocked(usersApi.getMe).mockResolvedValue(ken)
    const session = useSessionStore()
    await session.signInAs(1)

    session.signOut()

    expect(session.userId).toBeNull()
    expect(session.me).toBeNull()
    expect(getUserId()).toBeNull()
    expect(session.accounts).toEqual([ken])
  })

  it('forget removes only that account from the list', async () => {
    const session = useSessionStore()
    vi.mocked(usersApi.getMe).mockResolvedValueOnce(ken)
    await session.signInAs(1)
    vi.mocked(usersApi.getMe).mockResolvedValueOnce(amy)
    await session.signInAs(2)

    session.forget(1)

    expect(session.accounts).toEqual([amy])
  })

  it('ignores a corrupted saved account list', () => {
    localStorage.setItem('tm.accounts', '{not json')
    setUserId(5)

    const session = useSessionStore()

    expect(session.accounts).toEqual([])
    expect(session.userId).toBe(5)
  })

  describe('ensureMe', () => {
    it('fetches the user once even when called from several places at the same time', async () => {
      setUserId(1)
      vi.mocked(usersApi.getMe).mockResolvedValue(ken)
      const session = useSessionStore()

      await Promise.all([session.ensureMe(), session.ensureMe(), session.ensureMe()])

      expect(usersApi.getMe).toHaveBeenCalledTimes(1)
      expect(session.me).toEqual(ken)
    })

    it('does nothing when the user is already loaded or nobody is signed in', async () => {
      const session = useSessionStore()
      await session.ensureMe()
      expect(usersApi.getMe).not.toHaveBeenCalled()

      setUserId(1)
      const signedIn = (setActivePinia(createPinia()), useSessionStore())
      vi.mocked(usersApi.getMe).mockResolvedValue(ken)
      await signedIn.ensureMe()
      await signedIn.ensureMe()
      expect(usersApi.getMe).toHaveBeenCalledTimes(1)
    })

    it('can be retried after a failure', async () => {
      setUserId(1)
      const session = useSessionStore()
      vi.mocked(usersApi.getMe).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'down'))
      await expect(session.ensureMe()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })

      vi.mocked(usersApi.getMe).mockResolvedValueOnce(ken)
      await session.ensureMe()

      expect(session.me).toEqual(ken)
    })
  })
})
