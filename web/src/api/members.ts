import { http } from './http'
import type { MemberRole } from './trips'

/** 对应后端 trip_member.status：退出不删行，只改成 left，历史账目仍能指向他 */
export type MemberStatus = 'active' | 'left'

/** 对应后端 MemberView。注意 memberId（trip_member.id）和 userId 是两个不同的编号：请求里的"人"一律用 memberId。 */
export interface MemberView {
  memberId: number
  userId: number
  nickname: string
  role: MemberRole
  status: MemberStatus
  /** UTC 的 ISO-8601（带 Z） */
  joinedAt: string
}

/** GET /trips/{tripId}/members：这个行程的全部成员，包含已退出的，按 memberId 升序 */
export async function listMembers(tripId: number): Promise<MemberView[]> {
  const { data } = await http.get<{ items: MemberView[] }>(`/trips/${tripId}/members`)
  return data.items
}
