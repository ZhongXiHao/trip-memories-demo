import { showToast } from 'vant'
// 函数式组件的样式不会被自动按需引入，要手动引
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'

/** 把任意错误以提示条的形式告诉用户 */
export function showError(error: unknown): void {
  showToast({ message: describeError(toApiError(error)), position: 'bottom' })
}
