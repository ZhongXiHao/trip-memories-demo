/**
 * 币别"线路色"：每个币别一个固定颜色，在列表里扫一眼就能分清日元和港币。
 * 颜色定义在 styles/tokens.less 的 --tm-cur-*；币别列表以后新增、这里还没配色的用 --tm-cur-other。
 * 颜色只是辅助，旁边一定要同时写币别代码（见 DESIGN.md）。
 */
const COLORED = ['jpy', 'krw', 'cny', 'hkd', 'twd', 'usd', 'eur', 'gbp', 'sgd', 'thb', 'myr', 'aud']

export function currencyColor(code: string): string {
  const key = code.toLowerCase()
  return COLORED.includes(key) ? `var(--tm-cur-${key})` : 'var(--tm-cur-other)'
}
