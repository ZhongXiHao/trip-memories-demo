import { defineStore } from 'pinia'
import { ref } from 'vue'

import { clearUserId, getUserId, setUserId } from '@/api/session'
import { getMe, registerDevUser, type UserView } from '@/api/users'

/** 这台浏览器用过的账号。后端没有"列出所有用户"的接口，所以开发期自己记一份。 */
export interface SavedAccount {
  id: number
  nickname: string
}

const ACCOUNTS_KEY = 'tm.accounts'

function loadAccounts(): SavedAccount[] {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(ACCOUNTS_KEY) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (a): a is SavedAccount =>
        typeof a?.id === 'number' && typeof a?.nickname === 'string',
    )
  } catch {
    return []
  }
}

function saveAccounts(accounts: SavedAccount[]): void {
  try {
    localStorage.setItem(ACCOUNTS_KEY, JSON.stringify(accounts))
  } catch {
    // 存不下就只在本次会话里有效
  }
}

export const useSessionStore = defineStore('session', () => {
  /** 当前身份；null 表示还没选。和 api/session.ts 里的存储保持一致。 */
  const userId = ref<number | null>(getUserId())
  const me = ref<UserView | null>(null)
  const accounts = ref<SavedAccount[]>(loadAccounts())

  function remember(user: UserView): void {
    accounts.value = [
      { id: user.id, nickname: user.nickname },
      ...accounts.value.filter((a) => a.id !== user.id),
    ]
    saveAccounts(accounts.value)
  }

  /** 用这个 id 作为当前身份。先向后端确认它存在；失败则恢复原来的身份并抛出 ApiError。 */
  async function signInAs(id: number): Promise<UserView> {
    const previous = userId.value
    setUserId(id)
    userId.value = id
    try {
      const user = await getMe()
      me.value = user
      remember(user)
      return user
    } catch (error) {
      if (previous === null) {
        clearUserId()
      } else {
        setUserId(previous)
      }
      userId.value = previous
      throw error
    }
  }

  /** 创建一个开发用户并立刻用它登录 */
  async function register(nickname: string): Promise<UserView> {
    const created = await registerDevUser(nickname)
    return signInAs(created.id)
  }

  /** 已有身份时刷新一次用户信息（比如页面刷新后） */
  async function refreshMe(): Promise<void> {
    if (userId.value === null) return
    me.value = await getMe()
  }

  let pendingMe: Promise<void> | null = null

  /**
   * 有身份但还没取过用户信息时才取；多处同时调用（顶部导航条、页面）只发一次请求。
   * 已经取过就什么都不做。
   */
  function ensureMe(): Promise<void> {
    if (userId.value === null || me.value !== null) return Promise.resolve()
    pendingMe ??= refreshMe().finally(() => {
      pendingMe = null
    })
    return pendingMe
  }

  /** 只清本地状态，不通知后端（开发期没有会话） */
  function signOut(): void {
    clearUserId()
    userId.value = null
    me.value = null
  }

  /** 把一个账号从"用过的账号"列表里去掉（不会删除后端的用户） */
  function forget(id: number): void {
    accounts.value = accounts.value.filter((a) => a.id !== id)
    saveAccounts(accounts.value)
  }

  return { userId, me, accounts, signInAs, register, refreshMe, ensureMe, signOut, forget }
})
