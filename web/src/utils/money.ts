// 金额的显示和输入。后端的金额一律是整数的"最小单位 × 100"（JPY 1200 → 120000，HKD 12.5 → 1250），
// 字段名带 Minor。这里全部用整数和字符串运算，不经过浮点数（0.1 + 0.2 那类误差会让分摊对不上）。
//
// roundingUnit 来自 GET /currencies：存储单位下的取整单位。JPY、KRW 是 100（整元），其余是 1（到分）。

/** 存储单位里 1 个"主单位"是多少：契约固定为 × 100 */
const MINOR_PER_MAJOR = 100

/**
 * 按 roundingUnit 决定显示几位小数：100 → 0 位，10 → 1 位，1 → 2 位。
 * 不认识的值（不是 1 / 10 / 100）按 2 位，宁可多显示，不要把金额截掉。
 */
export function fractionDigits(roundingUnit: number): number {
  if (roundingUnit === 100) return 0
  if (roundingUnit === 10) return 1
  return 2
}

function groupThousands(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

/**
 * 显示用："120000, 100 → 1,200"、"123450, 1 → 1,234.50"。
 * 币别列表还没加载（roundingUnit 未知）时：整数就不带小数，否则显示 2 位，不会显示错金额。
 */
export function formatMinor(amountMinor: number, roundingUnit?: number): string {
  const negative = amountMinor < 0
  const abs = Math.abs(Math.trunc(amountMinor))
  const major = Math.floor(abs / MINOR_PER_MAJOR)
  const rest = abs % MINOR_PER_MAJOR
  const digits = roundingUnit === undefined ? (rest === 0 ? 0 : 2) : fractionDigits(roundingUnit)
  const fraction = String(rest).padStart(2, '0').slice(0, digits)
  const text = groupThousands(String(major)) + (digits > 0 ? `.${fraction}` : '')
  return negative ? `-${text}` : text
}

/** 填进输入框的初始值（修改时）：不加千分位，按币别的小数位，比如 "1200"、"12.50" */
export function minorToInput(amountMinor: number, roundingUnit: number): string {
  return formatMinor(amountMinor, roundingUnit).replace(/,/g, '')
}

export type AmountProblem = 'empty' | 'invalid' | 'too-precise' | 'not-positive' | 'too-large'

export type ParsedAmount = { ok: true; minor: number } | { ok: false; problem: AmountProblem }

/**
 * 输入框里的文字 → 最小单位 × 100 的整数。允许千分位逗号和首尾空格；
 * 小数位超过币别允许的（JPY 写了 1200.5）是 too-precise，对应后端的 AMOUNT_NOT_ROUNDABLE。
 * allowZero：自定义分摊里某人分 0 是合法的，总额不行。
 */
export function parseAmount(input: string, roundingUnit: number, { allowZero = false } = {}): ParsedAmount {
  const text = input.trim().replace(/,/g, '')
  if (text === '') return { ok: false, problem: 'empty' }
  const match = /^(\d*)(?:\.(\d*))?$/.exec(text)
  if (!match || (match[1] === '' && !match[2])) return { ok: false, problem: 'invalid' }
  const whole = match[1] || '0'
  const fraction = (match[2] ?? '').replace(/0+$/, '')
  if (fraction.length > 2) return { ok: false, problem: 'too-precise' }
  const minor = Number(whole) * MINOR_PER_MAJOR + Number(fraction.padEnd(2, '0'))
  if (!Number.isSafeInteger(minor)) return { ok: false, problem: 'too-large' }
  if (minor % roundingUnit !== 0) return { ok: false, problem: 'too-precise' }
  if (minor === 0 && !allowZero) return { ok: false, problem: 'not-positive' }
  return { ok: true, minor }
}

/** 给用户看的说明 */
export function amountProblemText(problem: AmountProblem, roundingUnit: number): string {
  switch (problem) {
    case 'empty':
      return '请填写金额'
    case 'invalid':
      return '金额只能是数字'
    case 'too-precise':
      return fractionDigits(roundingUnit) === 0 ? '这个币别只能填整数' : `最多 ${fractionDigits(roundingUnit)} 位小数`
    case 'not-positive':
      return '金额要大于 0'
    case 'too-large':
      return '金额太大了'
  }
}
