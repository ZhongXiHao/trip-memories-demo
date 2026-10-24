import axios from 'axios'

import { toApiError } from './errors'
import { clearUserId, getUserId } from './session'

/**
 * 全项目唯一的 axios 实例。所有接口函数（api/*.ts）都通过它发请求：
 * - 路径从 /api 起，开发时由 Vite 代理到后端（见 vite.config.ts），后端不用开 CORS；
 * - 自动带上当前身份；
 * - 失败统一变成 ApiError。
 */
export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
})

let unauthenticatedHandler: (() => void) | null = null

/** 收到 401（身份无效或过期）时要做的事，由应用启动时注册（清状态、跳到选择身份页） */
export function setUnauthenticatedHandler(handler: (() => void) | null): void {
  unauthenticatedHandler = handler
}

http.interceptors.request.use((config) => {
  const userId = getUserId()
  if (userId !== null) {
    config.headers.set('X-User-Id', String(userId))
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const apiError = toApiError(error)
    if (apiError.status === 401) {
      clearUserId()
      unauthenticatedHandler?.()
    }
    return Promise.reject(apiError)
  },
)
