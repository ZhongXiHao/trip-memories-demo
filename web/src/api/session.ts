// 当前身份（开发期是 X-User-Id）的存取。
// 把"身份存在哪、怎么带到请求里"收在 api/ 这一处：以后换成验证码登录 + token，只改这个文件和 http.ts。

const KEY = 'tm.userId'

export function getUserId(): number | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw === null) return null
    const id = Number(raw)
    return Number.isSafeInteger(id) && id > 0 ? id : null
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用；没有身份就当作未登录
    return null
  }
}

export function setUserId(id: number): void {
  try {
    localStorage.setItem(KEY, String(id))
  } catch {
    // 写不进去也不影响本次会话内的请求，只是刷新后需要重新选择
  }
}

export function clearUserId(): void {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // 同上
  }
}
