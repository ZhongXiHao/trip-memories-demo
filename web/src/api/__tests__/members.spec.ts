import { AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'

import { listMembers, type MemberView } from '../members'
import { http } from '../http'

const member: MemberView = {
  memberId: 5,
  userId: 6,
  nickname: 'Eric',
  role: 'member',
  status: 'active',
  joinedAt: '2026-10-24T08:00:00.000Z',
}

/** 假的网络层：记录收到的请求，并用给定的状态码和响应体回答 */
function fakeServer(status: number, data: unknown, seen: { method?: string; url?: string }): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    seen.method = config.method
    seen.url = config.url
    return Promise.resolve({ status, data, statusText: '', headers: new AxiosHeaders(), config })
  }
}

describe('members api', () => {
  it('listMembers unwraps items', async () => {
    const seen: { method?: string; url?: string } = {}
    http.defaults.adapter = fakeServer(200, { items: [member] }, seen)

    expect(await listMembers(3)).toEqual([member])
    expect(seen).toMatchObject({ method: 'get', url: '/trips/3/members' })
  })
})
