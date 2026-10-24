import { AxiosError, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, describeError } from '../errors'
import { http, setUnauthenticatedHandler } from '../http'
import { getUserId, setUserId } from '../session'

/** 假的网络层：不真的发请求，按给定的状态码和响应体回答 */
function respondWith(status: number, data: unknown): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    const response = { status, data, statusText: '', headers: {}, config }
    if (status >= 400) {
      return Promise.reject(new AxiosError('failed', 'ERR_BAD_RESPONSE', config, null, response))
    }
    return Promise.resolve(response)
  }
}

function failWith(code: string): AxiosAdapter {
  return (config) => Promise.reject(new AxiosError('failed', code, config, null))
}

describe('http', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    setUnauthenticatedHandler(null)
  })

  it('has the current user id in X-User-Id', async () => {
    setUserId(7)
    let seen: string | undefined
    http.defaults.adapter = (config) => {
      seen = String(config.headers.get('X-User-Id'))
      return respondWith(200, {})(config)
    }

    await http.get('/users/me')

    expect(seen).toBe('7')
  })

  it('sends no X-User-Id when nobody is signed in', async () => {
    let header: unknown = 'unset'
    http.defaults.adapter = (config) => {
      header = config.headers.get('X-User-Id')
      return respondWith(200, {})(config)
    }

    await http.get('/currencies')

    expect(header).toBeFalsy()
  })

  it('turns a backend error body into an ApiError with code and fields', async () => {
    http.defaults.adapter = respondWith(400, {
      code: 'VALIDATION_FAILED',
      message: 'Request validation failed',
      fields: [{ field: 'nickname', message: 'must not be blank' }],
    })

    const error = await http.post('/auth/register', {}).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.status).toBe(400)
    expect(apiError.code).toBe('VALIDATION_FAILED')
    expect(apiError.fieldMessage('nickname')).toBe('must not be blank')
    expect(apiError.fieldMessage('other')).toBeUndefined()
  })

  it('maps a 5xx without a JSON body to SERVER_ERROR', async () => {
    http.defaults.adapter = respondWith(502, '')

    const error = (await http.get('/trips').catch((e: unknown) => e)) as ApiError

    expect(error.code).toBe('SERVER_ERROR')
    expect(error.status).toBe(502)
  })

  it('maps a missing response to NETWORK_ERROR and a timeout to TIMEOUT', async () => {
    http.defaults.adapter = failWith('ERR_NETWORK')
    const network = (await http.get('/trips').catch((e: unknown) => e)) as ApiError
    expect(network.code).toBe('NETWORK_ERROR')

    http.defaults.adapter = failWith('ECONNABORTED')
    const timeout = (await http.get('/trips').catch((e: unknown) => e)) as ApiError
    expect(timeout.code).toBe('TIMEOUT')
  })

  it('clears the stored identity and calls the handler on 401', async () => {
    setUserId(9)
    const handler = vi.fn()
    setUnauthenticatedHandler(handler)
    http.defaults.adapter = respondWith(401, { code: 'UNAUTHENTICATED', message: 'Authentication required' })

    const error = (await http.get('/users/me').catch((e: unknown) => e)) as ApiError

    expect(error.code).toBe('UNAUTHENTICATED')
    expect(getUserId()).toBeNull()
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('does not call the 401 handler for other errors', async () => {
    const handler = vi.fn()
    setUnauthenticatedHandler(handler)
    http.defaults.adapter = respondWith(404, { code: 'NOT_FOUND', message: 'Resource not found' })

    await http.get('/trips/1').catch(() => undefined)

    expect(handler).not.toHaveBeenCalled()
  })
})

describe('describeError', () => {
  it('uses the first field message for validation failures', () => {
    const error = new ApiError(400, 'VALIDATION_FAILED', 'x', [{ field: 'a', message: '昵称不能为空' }])
    expect(describeError(error)).toBe('昵称不能为空')
  })

  it('has Chinese text for the codes the UI relies on', () => {
    for (const code of ['NETWORK_ERROR', 'TIMEOUT', 'UNAUTHENTICATED', 'FORBIDDEN', 'NOT_FOUND']) {
      expect(describeError(new ApiError(0, code, 'english message'))).not.toBe('english message')
    }
  })

  it('falls back to the server message, then to a generic one', () => {
    expect(describeError(new ApiError(409, 'ALREADY_MEMBER', 'User is already a member'))).toBe(
      'User is already a member',
    )
    expect(describeError(new ApiError(500, 'WHATEVER', ''))).toBe('操作失败，请重试')
  })
})
