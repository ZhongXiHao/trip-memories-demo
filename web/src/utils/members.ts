import type { MemberView } from '@/api/members'
import type { MemberRole } from '@/api/trips'

/**
 * 成员分成"在籍"和"已退出"两组。
 * 在籍的负责人排第一，其余按加入顺序（memberId）；已退出的也按 memberId。
 */
export function splitMembers(members: MemberView[]): { active: MemberView[]; left: MemberView[] } {
  const byId = (a: MemberView, b: MemberView) => a.memberId - b.memberId
  const active = members.filter((m) => m.status === 'active').sort(byId)
  // 负责人排最前，其余保持 memberId 顺序（Array.sort 是稳定的）
  active.sort((a, b) => Number(b.role === 'owner') - Number(a.role === 'owner'))
  const left = members.filter((m) => m.status === 'left').sort(byId)
  return { active, left }
}

/**
 * 角色在界面上的叫法。后端叫 owner，界面叫"负责人"而不是"创建者"：
 * 负责人可以转让，转让之后新的负责人并不是创建者。
 */
export function roleLabel(role: MemberRole): string {
  return role === 'owner' ? '负责人' : '成员'
}
