// 页头"⋯"菜单的内容：按身份和权限生成，纯函数，方便单元测试。
// 危险操作（删除、退出）只出现在菜单里，不常驻页面；选中后页面还要再用 confirmAction 确认。见 DESIGN.md「按钮」。

export interface MenuItem<K extends string = string> {
  key: K
  name: string
  /** 危险操作：菜单里用危险色 */
  danger?: boolean
}

/** Vant ActionSheet 的 actions：危险项用危险色 */
export function toSheetActions<K extends string>(items: MenuItem<K>[]): Array<{ key: K; name: string; color?: string }> {
  return items.map(({ key, name, danger }) => (danger ? { key, name, color: 'var(--tm-danger)' } : { key, name }))
}

export type ExpenseMenuKey = 'edit' | 'delete'

/** 支出详情：只有付款人或记账人能改、能删；别人没有可做的操作，菜单为空（页头不显示"⋯"） */
export function expenseMenuItems(canModify: boolean): MenuItem<ExpenseMenuKey>[] {
  if (!canModify) return []
  return [
    { key: 'edit', name: '修改支出' },
    { key: 'delete', name: '删除支出', danger: true },
  ]
}
