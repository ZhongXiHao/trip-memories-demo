import { http } from './http'

/** 对应后端 trip_member.role */
export type MemberRole = 'owner' | 'member'

/** 对应后端 TripView；日期是 "YYYY-MM-DD"，createdAt 是 UTC 的 ISO-8601（带 Z） */
export interface TripView {
  id: number
  name: string
  description: string | null
  startDate: string | null
  endDate: string | null
  timezone: string
  displayCurrency: string
  /** 当前用户在这个行程里的成员 id 和角色，省得再去查成员列表 */
  myMemberId: number
  myRole: MemberRole
  createdAt: string
}

/** 对应后端 TripRequest：创建和修改共用（修改是整体替换） */
export interface TripRequest {
  name: string
  description?: string | null
  displayCurrency: string
  timezone: string
  startDate?: string | null
  endDate?: string | null
}

/** GET /trips/{tripId}：仅在籍成员；不是成员、已退出或行程已删除都是 404（不泄露行程是否存在） */
export async function getTrip(tripId: number): Promise<TripView> {
  const { data } = await http.get<TripView>(`/trips/${tripId}`)
  return data
}

/** GET /trips：我在籍的、未删除的行程，后端已按创建时间倒序 */
export async function listTrips(): Promise<TripView[]> {
  const { data } = await http.get<{ items: TripView[] }>('/trips')
  return data.items
}

/** POST /trips：创建者自动成为 Owner。校验失败是 400 VALIDATION_FAILED，fields 指向字段名。 */
export async function createTrip(request: TripRequest): Promise<TripView> {
  const { data } = await http.post<TripView>('/trips', request)
  return data
}

/**
 * PUT /trips/{tripId}：仅负责人，整体替换（没传的可选字段会被清空，不是保留旧值）。
 * 校验规则和创建一样；不是负责人 → 403，不是成员 → 404。
 */
export async function updateTrip(tripId: number, request: TripRequest): Promise<TripView> {
  const { data } = await http.put<TripView>(`/trips/${tripId}`, request)
  return data
}

/** POST /trips/{tripId}/delete：仅负责人，软删除，成功返回 204。之后这个行程对所有成员都是 404。 */
export async function deleteTrip(tripId: number): Promise<void> {
  await http.post(`/trips/${tripId}/delete`)
}
