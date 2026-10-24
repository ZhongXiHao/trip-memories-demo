import { describe, expect, it } from 'vitest'

import { ApiError } from '@/api/errors'
import type { TripView } from '@/api/trips'

import {
  DESCRIPTION_MAX,
  NAME_MAX,
  serverFieldErrors,
  toTripRequest,
  tripToFormState,
  validateTripForm,
  type TripFormState,
} from '../tripForm'

const valid: TripFormState = {
  name: '东京五日游',
  description: '',
  displayCurrency: 'CNY',
  timezone: 'Asia/Tokyo',
  startDate: '',
  endDate: '',
}

describe('validateTripForm', () => {
  it('accepts a form with only the required fields', () => {
    expect(validateTripForm(valid)).toEqual({})
  })

  it('requires name, currency and time zone', () => {
    const errors = validateTripForm({ ...valid, name: '   ', displayCurrency: '', timezone: '' })
    expect(Object.keys(errors).sort()).toEqual(['displayCurrency', 'name', 'timezone'])
  })

  it('enforces the length limits the backend enforces', () => {
    expect(validateTripForm({ ...valid, name: 'a'.repeat(NAME_MAX) })).toEqual({})
    expect(validateTripForm({ ...valid, name: 'a'.repeat(NAME_MAX + 1) }).name).toBeTruthy()
    expect(validateTripForm({ ...valid, description: 'a'.repeat(DESCRIPTION_MAX + 1) }).description).toBeTruthy()
  })

  it('counts UTF-16 characters like the backend does, so an emoji costs two', () => {
    expect(validateTripForm({ ...valid, name: '😀'.repeat(NAME_MAX / 2) })).toEqual({})
    expect(validateTripForm({ ...valid, name: '😀'.repeat(NAME_MAX / 2 + 1) }).name).toBeTruthy()
  })

  it('rejects an end date before the start date, but allows a one-day trip', () => {
    expect(validateTripForm({ ...valid, startDate: '2026-10-05', endDate: '2026-10-01' }).endDate).toBeTruthy()
    expect(validateTripForm({ ...valid, startDate: '2026-10-05', endDate: '2026-10-05' })).toEqual({})
  })
})

describe('toTripRequest', () => {
  it('trims text and turns empty optional fields into null', () => {
    expect(toTripRequest({ ...valid, name: '  东京五日游 ', description: '  ' })).toEqual({
      name: '东京五日游',
      description: null,
      displayCurrency: 'CNY',
      timezone: 'Asia/Tokyo',
      startDate: null,
      endDate: null,
    })
  })

  it('passes dates and description through', () => {
    const request = toTripRequest({ ...valid, description: ' 吃吃吃 ', startDate: '2026-10-01', endDate: '2026-10-05' })
    expect(request).toMatchObject({ description: '吃吃吃', startDate: '2026-10-01', endDate: '2026-10-05' })
  })
})

describe('tripToFormState', () => {
  const trip: TripView = {
    id: 7,
    name: '东京五日游',
    description: null,
    startDate: null,
    endDate: null,
    timezone: 'Asia/Tokyo',
    displayCurrency: 'JPY',
    myMemberId: 3,
    myRole: 'owner',
    createdAt: '2026-10-24T08:00:00.000Z',
  }

  it('turns the backend nulls into empty strings for the form', () => {
    expect(tripToFormState(trip)).toEqual({
      name: '东京五日游',
      description: '',
      displayCurrency: 'JPY',
      timezone: 'Asia/Tokyo',
      startDate: '',
      endDate: '',
    })
  })

  it('round-trips: editing nothing produces the same request the trip already has', () => {
    const full: TripView = { ...trip, description: '吃吃吃', startDate: '2026-10-01', endDate: '2026-10-05' }

    expect(toTripRequest(tripToFormState(full))).toEqual({
      name: '东京五日游',
      description: '吃吃吃',
      displayCurrency: 'JPY',
      timezone: 'Asia/Tokyo',
      startDate: '2026-10-01',
      endDate: '2026-10-05',
    })
    // PUT 是整体替换：没有日期的行程，保存后仍然没有日期（null），不是别的值
    expect(toTripRequest(tripToFormState(trip))).toMatchObject({ description: null, startDate: null, endDate: null })
  })

  it('an unchanged trip passes the same validation the backend applies', () => {
    expect(validateTripForm(tripToFormState(trip))).toEqual({})
  })
})

describe('serverFieldErrors', () => {
  it('maps backend field names back to form fields', () => {
    const error = new ApiError(400, 'VALIDATION_FAILED', 'x', [
      { field: 'displayCurrency', message: 'unsupported currency' },
      { field: 'endDate', message: 'must not be before startDate' },
    ])
    expect(Object.keys(serverFieldErrors(error)).sort()).toEqual(['displayCurrency', 'endDate'])
  })

  it('ignores fields the form does not know', () => {
    const error = new ApiError(400, 'VALIDATION_FAILED', 'x', [{ field: 'somethingNew', message: 'y' }])
    expect(serverFieldErrors(error)).toEqual({})
  })
})
