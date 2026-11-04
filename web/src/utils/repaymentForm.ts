import type { DebtView } from '@/api/debts'
import type { CreateRepaymentRequest, RepaymentItemRequest } from '@/api/repayments'
import { repayableMinor } from '@/utils/debts'
import { amountProblemText, formatMinor, parseAmount } from '@/utils/money'

// 还款弹层的表单逻辑（契约「还款 · 1. 创建还款」）：一次转账可以结清同一对人、同一方向的几个币别，
// 用一个 paidCurrency 付。每一项分别填销帐金额（按 clearedCurrency 取整）和实付金额（按 paidCurrency 取整）；
// 同币别时实付 = 销帐，不另填。各项实付之和就是这次转账的总额。

/** 查币别的 roundingUnit；列表还没回来、或者没有这个币别时为 undefined */
export type RoundingUnitOf = (code: string) => number | undefined

/**
 * 能和 entry 一起结清的欠款：同一个付款方 → 收款方、还有可还额度的。entry 排第一，其余保持原来的顺序。
 * 只能用真实的一对一欠款（simplify=false），建议转账不能据此记还款。
 */
export function repayCandidates(debts: DebtView[], entry: DebtView): DebtView[] {
  const others = debts.filter(
    (d) =>
      d.fromMemberId === entry.fromMemberId &&
      d.toMemberId === entry.toMemberId &&
      d.currency !== entry.currency &&
      repayableMinor(d) > 0,
  )
  return [entry, ...others]
}

/**
 * 转账币别的候选：币别列表里的全部币别（契约只要求在币别列表里），这几笔欠款的币别排在前面。
 * 列表还没回来时只给欠款的币别。
 */
export function paidCurrencyOptions(debtCurrencies: string[], listed: string[]): string[] {
  const result: string[] = []
  for (const code of [...debtCurrencies, ...listed]) if (!result.includes(code)) result.push(code)
  return result
}

/** 表单里的一项：要结清的那笔欠款，和两个输入框里的文字 */
export interface RepayLineInput {
  currency: string
  /** 现在能还的上限（repayableMinor） */
  maxMinor: number
  clearedText: string
  /** 同币别时不用，忽略 */
  paidText: string
}

export interface RepayLineErrors {
  cleared?: string
  paid?: string
}

export type RepayItemsResult =
  | { ok: true; items: RepaymentItemRequest[] }
  | { ok: false; errors: Record<string, RepayLineErrors>; formError?: string }

/**
 * 本地先查一遍金额，规则和后端一致：都大于 0，销帐金额是 clearedCurrency 的 roundingUnit 的整数倍、
 * 不超过可还的上限，实付金额是 paidCurrency 的 roundingUnit 的整数倍；同币别时实付 = 销帐。
 * roundingUnit 不知道时按 1（到分）查，剩下的交给后端。
 */
export function buildRepaymentItems(
  lines: RepayLineInput[],
  paidCurrency: string,
  unitOf: RoundingUnitOf,
): RepayItemsResult {
  if (lines.length === 0) return { ok: false, errors: {}, formError: '至少选一笔要结清的欠款' }
  const paidUnit = unitOf(paidCurrency) ?? 1
  const items: RepaymentItemRequest[] = []
  const errors: Record<string, RepayLineErrors> = {}

  for (const line of lines) {
    const lineErrors: RepayLineErrors = {}
    const clearedUnit = unitOf(line.currency) ?? 1
    const cleared = parseAmount(line.clearedText, clearedUnit)
    if (!cleared.ok) {
      lineErrors.cleared = amountProblemText(cleared.problem, clearedUnit)
    } else if (cleared.minor > line.maxMinor) {
      lineErrors.cleared = `最多能还 ${line.currency} ${formatMinor(line.maxMinor, unitOf(line.currency))}`
    }

    let paidMinor: number | null = null
    if (line.currency === paidCurrency) {
      paidMinor = cleared.ok ? cleared.minor : null
    } else {
      const paid = parseAmount(line.paidText, paidUnit)
      if (paid.ok) paidMinor = paid.minor
      else lineErrors.paid = amountProblemText(paid.problem, paidUnit)
    }

    if (lineErrors.cleared || lineErrors.paid) {
      errors[line.currency] = lineErrors
    } else if (cleared.ok && paidMinor !== null) {
      items.push({ clearedCurrency: line.currency, clearedAmountMinor: cleared.minor, paidAmountMinor: paidMinor })
    }
  }

  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, items }
}

/**
 * 这次转账的实付总额（各项 paidAmountMinor 之和）。还有哪一项的实付金额没填好时为 null，
 * 免得显示一个不完整的数。
 */
export function totalPaidMinor(lines: RepayLineInput[], paidCurrency: string, unitOf: RoundingUnitOf): number | null {
  if (lines.length === 0) return null
  const paidUnit = unitOf(paidCurrency) ?? 1
  let total = 0
  for (const line of lines) {
    const sameCurrency = line.currency === paidCurrency
    const parsed = sameCurrency
      ? parseAmount(line.clearedText, unitOf(line.currency) ?? 1)
      : parseAmount(line.paidText, paidUnit)
    if (!parsed.ok) return null
    total += parsed.minor
  }
  return total
}

/**
 * 由 paid / cleared 推导的汇率，只作提示（契约里汇率是衍生值，后端不校验是否合理）。
 * 两边都是"最小单位 × 100"，比值和主单位的比值相同。保留 4 位有效数字，如 "1 JPY ≈ 0.052 HKD"。
 */
export function impliedRateText(
  clearedCurrency: string,
  clearedMinor: number,
  paidCurrency: string,
  paidMinor: number,
): string {
  if (clearedMinor <= 0 || paidMinor <= 0) return ''
  const rate = new Intl.NumberFormat('en-US', { maximumSignificantDigits: 4, useGrouping: false }).format(
    paidMinor / clearedMinor,
  )
  return `1 ${clearedCurrency} ≈ ${rate} ${paidCurrency}`
}

/** 组装 POST /repayments 的请求体；备注去掉首尾空格，空的不带 */
export function buildRepaymentRequest(input: {
  clientUuid: string
  fromMemberId: number
  toMemberId: number
  paidCurrency: string
  items: RepaymentItemRequest[]
  note: string
}): CreateRepaymentRequest {
  const note = input.note.trim()
  return {
    clientUuid: input.clientUuid,
    fromMemberId: input.fromMemberId,
    toMemberId: input.toMemberId,
    paidCurrency: input.paidCurrency,
    items: input.items,
    ...(note ? { note } : {}),
  }
}
