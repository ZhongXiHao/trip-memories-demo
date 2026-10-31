import { onBeforeUnmount, ref, type Ref } from 'vue'

/** PC 版式的断点，和 styles/breakpoints.less 的 @bp-desktop 保持一致 */
export const BP_DESKTOP = 960

/**
 * 当前是不是 PC 版式（≥ 960px），窗口大小变化时跟着变。
 * 只在两套版式的"结构"不同时用（比如行程页手机是标签页、PC 是两栏），只是样式不同的用 CSS 媒体查询。
 * 这样同一个列表不会在两套结构里各渲染一份、各请求一次。
 */
export function useIsDesktop(): Ref<boolean> {
  const query = typeof window.matchMedia === 'function' ? window.matchMedia(`(min-width: ${BP_DESKTOP}px)`) : null
  const isDesktop = ref(query?.matches ?? false)
  const update = (event: MediaQueryListEvent) => {
    isDesktop.value = event.matches
  }
  query?.addEventListener('change', update)
  onBeforeUnmount(() => query?.removeEventListener('change', update))
  return isDesktop
}
