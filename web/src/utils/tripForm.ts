import { ApiError } from '@/api/errors'
import type { TripRequest, TripView } from '@/api/trips'

import { tripDays } from './date'

// 长度上限和后端 TripRequest 一致。后端按 UTF-16 字符数算，所以这里也用 .length，不要用 [...s].length。
export const NAME_MAX = 128
export const DESCRIPTION_MAX = 512

export interface TripFormState {
  name: string
  description: string
  displayCurrency: string
  timezone: string
  /** "YYYY-MM-DD"，没选是空字符串 */
  startDate: string
  endDate: string
}

export type TripFormErrors = Partial<Record<keyof TripFormState, string>>

/** 提交前的本地校验，返回每个字段的错误文字；没有错误就是空对象 */
export function validateTripForm(form: TripFormState): TripFormErrors {
  const errors: TripFormErrors = {}
  const name = form.name.trim()
  if (!name) errors.name = '请填写行程名称'
  else if (name.length > NAME_MAX) errors.name = `行程名称最多 ${NAME_MAX} 个字`
  if (form.description.trim().length > DESCRIPTION_MAX) errors.description = `备注最多 ${DESCRIPTION_MAX} 个字`
  if (!form.displayCurrency) errors.displayCurrency = '请选择展示币别'
  if (!form.timezone) errors.timezone = '请选择目的地时区'
  if (form.startDate && form.endDate && tripDays(form.startDate, form.endDate) === null) {
    errors.endDate = '结束日期不能早于开始日期'
  }
  return errors
}

/** 表单 → 请求体：名字和备注去掉首尾空白，没填的可选项传 null */
export function toTripRequest(form: TripFormState): TripRequest {
  const description = form.description.trim()
  return {
    name: form.name.trim(),
    description: description || null,
    displayCurrency: form.displayCurrency,
    timezone: form.timezone,
    startDate: form.startDate || null,
    endDate: form.endDate || null,
  }
}

const SERVER_FIELD_MESSAGES: Record<string, { field: keyof TripFormState; message: string }> = {
  name: { field: 'name', message: `行程名称不合法，最多 ${NAME_MAX} 个字` },
  description: { field: 'description', message: `备注最多 ${DESCRIPTION_MAX} 个字` },
  displayCurrency: { field: 'displayCurrency', message: '这个币别不支持，请重新选择' },
  timezone: { field: 'timezone', message: '这个时区不合法，请重新选择' },
  endDate: { field: 'endDate', message: '结束日期不能早于开始日期' },
  startDate: { field: 'endDate', message: '开始日期不合法' },
}

/** 把后端返回的字段错误挂回对应的表单字段（本地校验漏掉的情况，比如币别刚好被下架） */
export function serverFieldErrors(error: ApiError): TripFormErrors {
  const errors: TripFormErrors = {}
  for (const { field } of error.fields) {
    const known = SERVER_FIELD_MESSAGES[field]
    if (known) errors[known.field] = known.message
  }
  return errors
}

/** 行程 → 编辑表单的初始值：后端的 null 在表单里是空字符串 */
export function tripToFormState(trip: TripView): TripFormState {
  return {
    name: trip.name,
    description: trip.description ?? '',
    displayCurrency: trip.displayCurrency,
    timezone: trip.timezone,
    startDate: trip.startDate ?? '',
    endDate: trip.endDate ?? '',
  }
}
