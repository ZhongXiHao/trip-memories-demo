import { showConfirmDialog } from 'vant'
// 函数式组件的样式不会被自动按需引入，要手动引
import 'vant/es/dialog/style'

interface ConfirmOptions {
  title: string
  message: string
  confirmText: string
  /** 危险操作（移出、删除）：确认按钮用危险色，和普通操作区分开 */
  danger?: boolean
}

/** 确认框：用户点确认返回 true，点取消或点遮罩返回 false */
export async function confirmAction({ title, message, confirmText, danger = false }: ConfirmOptions): Promise<boolean> {
  try {
    await showConfirmDialog({
      title,
      message,
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      confirmButtonColor: danger ? 'var(--tm-danger)' : 'var(--tm-primary)',
      closeOnClickOverlay: true,
    })
    return true
  } catch {
    return false
  }
}
