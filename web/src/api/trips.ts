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

/** GET /trips：我在籍的、未删除的行程，后端已按创建时间倒序 */
export async function listTrips(): Promise<TripView[]> {
  const { data } = await http.get<{ items: TripView[] }>('/trips')
  return data.items
}
