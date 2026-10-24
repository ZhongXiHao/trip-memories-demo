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

/** 退出或被移出时，后端顺带给出的提醒（比如还有没结清的欠款）。只提醒、不拦截：操作照常成功。 */
export interface MemberWarning {
  code: string
  message: string
}

/** 退出、移出的响应 */
export interface MemberChange {
  member: MemberView
  warnings: MemberWarning[]
}

/** 转让负责人的响应 */
export interface OwnerTransfer {
  previousOwner: MemberView
  newOwner: MemberView
}

/**
 * POST /trips/{tripId}/members：仅负责人。请求体里是 userId（添加成员是少数几个用 userId 的地方）。
 * 新建成员返回 201；曾经退出的人重新加入返回 200，沿用原来的 memberId（rejoined 为 true）。
 * 用户不存在 → 400（字段 userId）；已经是在籍成员 → 409 ALREADY_MEMBER。
 */
export async function addMember(tripId: number, userId: number): Promise<{ member: MemberView; rejoined: boolean }> {
  const response = await http.post<MemberView>(`/trips/${tripId}/members`, { userId })
  return { member: response.data, rejoined: response.status === 200 }
}

/** POST /trips/{tripId}/members/me/quit：自己退出。负责人不能直接退出（409 OWNER_MUST_TRANSFER_FIRST）。 */
export async function quitTrip(tripId: number): Promise<MemberChange> {
  const { data } = await http.post<MemberChange>(`/trips/${tripId}/members/me/quit`)
  return data
}

/** POST /trips/{tripId}/members/{memberId}/remove：仅负责人。注意这里是 memberId，不是 userId。 */
export async function removeMember(tripId: number, memberId: number): Promise<MemberChange> {
  const { data } = await http.post<MemberChange>(`/trips/${tripId}/members/${memberId}/remove`)
  return data
}

/** POST /trips/{tripId}/owner/transfer/{memberId}：仅当前负责人，目标必须是在籍成员（memberId，不是 userId）。 */
export async function transferOwner(tripId: number, memberId: number): Promise<OwnerTransfer> {
  const { data } = await http.post<OwnerTransfer>(`/trips/${tripId}/owner/transfer/${memberId}`)
  return data
}
