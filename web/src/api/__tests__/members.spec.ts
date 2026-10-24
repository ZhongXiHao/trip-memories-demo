import { AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it } from 'vitest'

import { addMember, listMembers, quitTrip, removeMember, transferOwner, type MemberView } from '../members'
import { http } from '../http'

const member: MemberView = {
  memberId: 5,
  userId: 6,
  nickname: 'Eric',
  role: 'member',
  status: 'active',
  joinedAt: '2026-10-24T08:00:00.000Z',
}

interface Seen {
  method?: string
  url?: string
  body?: unknown
}

/** 假的网络层：记录收到的请求，并用给定的状态码和响应体回答 */
function fakeServer(status: number, data: unknown, seen: Seen): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    seen.method = config.method
    seen.url = config.url
    seen.body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    return Promise.resolve({ status, data, statusText: '', headers: new AxiosHeaders(), config })
  }
}

describe('members api', () => {
  let seen: Seen

  beforeEach(() => {
    seen = {}
  })

  it('listMembers unwraps items', async () => {
    http.defaults.adapter = fakeServer(200, { items: [member] }, seen)

    expect(await listMembers(3)).toEqual([member])
    expect(seen).toMatchObject({ method: 'get', url: '/trips/3/members' })
  })

  it('addMember sends the userId and reports a brand-new member (201) as not rejoined', async () => {
    http.defaults.adapter = fakeServer(201, member, seen)

    const result = await addMember(3, 6)

    expect(result).toEqual({ member, rejoined: false })
    expect(seen).toMatchObject({ method: 'post', url: '/trips/3/members', body: { userId: 6 } })
  })

  it('addMember reports a returning member (200) as rejoined', async () => {
    http.defaults.adapter = fakeServer(200, member, seen)

    expect((await addMember(3, 6)).rejoined).toBe(true)
  })

  it('removeMember puts the memberId (not a userId) in the path', async () => {
    const change = { member: { ...member, status: 'left' }, warnings: [] }
    http.defaults.adapter = fakeServer(200, change, seen)

    expect(await removeMember(3, 5)).toEqual(change)
    expect(seen).toMatchObject({ method: 'post', url: '/trips/3/members/5/remove' })
  })

  it('quitTrip uses the "me" member of the trip', async () => {
    http.defaults.adapter = fakeServer(200, { member, warnings: [] }, seen)

    await quitTrip(3)

    expect(seen).toMatchObject({ method: 'post', url: '/trips/3/members/me/quit' })
  })

  it('transferOwner puts the target memberId in the path', async () => {
    const transfer = { previousOwner: { ...member, role: 'member' }, newOwner: { ...member, role: 'owner' } }
    http.defaults.adapter = fakeServer(200, transfer, seen)

    expect(await transferOwner(3, 5)).toEqual(transfer)
    expect(seen).toMatchObject({ method: 'post', url: '/trips/3/owner/transfer/5' })
  })
})
