import { AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { beforeEach, describe, expect, it } from 'vitest'

import { http } from '../http'
import { createTrip, getTrip, listTrips, type TripRequest, type TripView } from '../trips'

const trip: TripView = {
  id: 7,
  name: '东京五日游',
  description: null,
  startDate: '2026-10-01',
  endDate: '2026-10-05',
  timezone: 'Asia/Tokyo',
  displayCurrency: 'CNY',
  myMemberId: 3,
  myRole: 'owner',
  createdAt: '2026-10-24T08:00:00.000Z',
}

const request: TripRequest = {
  name: '东京五日游',
  description: null,
  displayCurrency: 'CNY',
  timezone: 'Asia/Tokyo',
  startDate: '2026-10-01',
  endDate: '2026-10-05',
}

interface Seen {
  method?: string
  url?: string
  body?: unknown
}

function fakeServer(status: number, data: unknown, seen: Seen): AxiosAdapter {
  return (config: InternalAxiosRequestConfig) => {
    seen.method = config.method
    seen.url = config.url
    seen.body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
    return Promise.resolve({ status, data, statusText: '', headers: new AxiosHeaders(), config })
  }
}

describe('trips api', () => {
  let seen: Seen

  beforeEach(() => {
    seen = {}
  })

  it('listTrips unwraps items', async () => {
    http.defaults.adapter = fakeServer(200, { items: [trip] }, seen)

    expect(await listTrips()).toEqual([trip])
    expect(seen).toMatchObject({ method: 'get', url: '/trips' })
  })

  it('getTrip asks for the given trip', async () => {
    http.defaults.adapter = fakeServer(200, trip, seen)

    expect(await getTrip(7)).toEqual(trip)
    expect(seen).toMatchObject({ method: 'get', url: '/trips/7' })
  })

  it('createTrip posts the request body as it is', async () => {
    http.defaults.adapter = fakeServer(201, trip, seen)

    await createTrip(request)

    expect(seen).toMatchObject({ method: 'post', url: '/trips', body: request })
  })
})
