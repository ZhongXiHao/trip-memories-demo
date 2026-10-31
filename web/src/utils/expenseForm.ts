import type { ApiError } from '@/api/errors'
import type { ExpenseCategory, ExpenseFields, ExpenseView, SplitMode, SplitRequest } from '@/api/expenses'

import { amountProblemText, formatMinor, minorToInput, parseAmount } from './money'
import { fromLocalDateTime, toLocalDateTime, type LocalDateTime } from './zonedTime'

// 长度上限和后端 ExpenseRequest 一致（按 UTF-16 字符数，所以用 .length）
export const TITLE_MAX = 128
export const NOTE_MAX = 512

export interface ExpenseFormState {
  title: string
  /** 输入框里的文字，主单位（JPY 写 1200，不是 120000） */
  amount: string
  currency: string
  paidByMemberId: number | null
  splitMode: SplitMode
  /** 平均分、按金额分时参与分摊的人（memberId），按成员列表的顺序 */
  participants: number[]
  /** 按金额分时每个人的金额（输入框文字）。换成平均分再换回来时保留 */
  customAmounts: Record<number, string>
  /** 当地日期 "YYYY-MM-DD" 和时间 "HH:mm"，按 occurredTz */
  date: string
  time: string
  occurredTz: string
  category: ExpenseCategory
  note: string
}

export type ExpenseFormField =
  | 'title'
  | 'amount'
  | 'currency'
  | 'paidByMemberId'
  | 'splitMode'
  | 'splits'
  | 'occurredAt'
  | 'occurredTz'
  | 'category'
  | 'note'

export type ExpenseFormErrors = Partial<Record<ExpenseFormField, string>> & {
  /** 按金额分时某个人的金额有错 */
  splitAmounts?: Record<number, string>
}

export function hasErrors(errors: ExpenseFormErrors): boolean {
  return Object.entries(errors).some(([key, value]) =>
    key === 'splitAmounts' ? Object.keys(value ?? {}).length > 0 : Boolean(value),
  )
}

/** 按金额分时，已经分出去多少、还差多少（总额没填好时 remaining 为 null）。只用于提示，不替代校验 */
export function customBalance(
  form: Pick<ExpenseFormState, 'amount' | 'participants' | 'customAmounts'>,
  roundingUnit: number,
): { assignedMinor: number; remainingMinor: number | null } {
  let assignedMinor = 0
  for (const memberId of form.participants) {
    const parsed = parseAmount(form.customAmounts[memberId] ?? '', roundingUnit, { allowZero: true })
    if (parsed.ok) assignedMinor += parsed.minor
  }
  const total = parseAmount(form.amount, roundingUnit)
  return { assignedMinor, remainingMinor: total.ok ? total.minor - assignedMinor : null }
}

/** "还差 300" / "多了 300" / null（刚好） */
export function balanceText(remainingMinor: number, roundingUnit: number): string | null {
  if (remainingMinor === 0) return null
  const amount = formatMinor(Math.abs(remainingMinor), roundingUnit)
  return remainingMinor > 0 ? `还差 ${amount} 没分` : `多分了 ${amount}`
}

/**
 * 提交前的本地校验，规则和后端一致；返回每个字段的错误文字，没有错误就是空对象。
 * roundingUnit 是所选币别的，未知（币别列表没加载）时调用方不应该允许提交。
 */
export function validateExpenseForm(
  form: ExpenseFormState,
  roundingUnit: number,
  myMemberId: number,
): ExpenseFormErrors {
  const errors: ExpenseFormErrors = {}
  const title = form.title.trim()
  if (!title) errors.title = '请填写这笔花在哪'
  else if (title.length > TITLE_MAX) errors.title = `最多 ${TITLE_MAX} 个字`

  if (!form.currency) errors.currency = '请选择币别'
  const total = parseAmount(form.amount, roundingUnit)
  if (!total.ok) errors.amount = amountProblemText(total.problem, roundingUnit)

  if (form.paidByMemberId === null) errors.paidByMemberId = '请选择谁付的钱'
  else if (form.splitMode === 'none' && form.paidByMemberId !== myMemberId) {
    errors.paidByMemberId = '个人支出只能由自己付款'
  }

  if (form.splitMode !== 'none') {
    if (form.participants.length === 0) {
      errors.splits = '至少选一个人分摊'
    } else if (form.splitMode === 'custom') {
      const splitAmounts: Record<number, string> = {}
      for (const memberId of form.participants) {
        const parsed = parseAmount(form.customAmounts[memberId] ?? '', roundingUnit, { allowZero: true })
        if (!parsed.ok) splitAmounts[memberId] = amountProblemText(parsed.problem, roundingUnit)
      }
      if (Object.keys(splitAmounts).length > 0) {
        errors.splitAmounts = splitAmounts
      } else if (total.ok) {
        const { remainingMinor } = customBalance(form, roundingUnit)
        const text = remainingMinor === null ? null : balanceText(remainingMinor, roundingUnit)
        if (text) errors.splits = `每人的金额加起来要等于总额：${text}`
      }
    }
  }

  if (fromLocalDateTime({ date: form.date, time: form.time }, form.occurredTz) === null) {
    errors.occurredAt = '请选择日期和时间'
  }
  if (!form.occurredTz) errors.occurredTz = '请选择时区'
  if (form.note.trim().length > NOTE_MAX) errors.note = `备注最多 ${NOTE_MAX} 个字`
  return errors
}

function splitsOf(form: ExpenseFormState, roundingUnit: number): SplitRequest[] | undefined {
  if (form.splitMode === 'none') return undefined
  if (form.splitMode === 'equal') return form.participants.map((memberId) => ({ memberId }))
  return form.participants.map((memberId) => {
    const parsed = parseAmount(form.customAmounts[memberId] ?? '', roundingUnit, { allowZero: true })
    return { memberId, amountMinor: parsed.ok ? parsed.minor : 0 }
  })
}

/** 当初载入时的时间，修改时用来判断用户有没有动过 */
export interface OriginalOccurred extends LocalDateTime {
  occurredTz: string
}

/**
 * 表单 → 请求体（预览、创建、修改共用的部分）。先 validateExpenseForm 通过再调用。
 * 修改时传 original：时间和时区都没动就不传 occurredAt、occurredTz，让后端保持原值
 * （原值精确到秒，表单只到分钟，原样传回去反而会改掉秒）。
 */
export function toExpenseFields(form: ExpenseFormState, roundingUnit: number, original?: OriginalOccurred): ExpenseFields {
  const total = parseAmount(form.amount, roundingUnit)
  const note = form.note.trim()
  const fields: ExpenseFields = {
    title: form.title.trim(),
    amountMinor: total.ok ? total.minor : 0,
    currency: form.currency,
    paidByMemberId: form.paidByMemberId ?? 0,
    splitMode: form.splitMode,
    category: form.category,
    note: note || null,
  }
  const splits = splitsOf(form, roundingUnit)
  if (splits) fields.splits = splits
  const unchanged =
    original !== undefined &&
    original.date === form.date &&
    original.time === form.time &&
    original.occurredTz === form.occurredTz
  if (!unchanged) {
    fields.occurredAt = fromLocalDateTime({ date: form.date, time: form.time }, form.occurredTz) ?? undefined
    fields.occurredTz = form.occurredTz
  }
  return fields
}

/** 新建时的初始值：自己付钱，所有在籍成员平均分，时间是行程时区的现在 */
export function newExpenseFormState(options: {
  myMemberId: number
  activeMemberIds: number[]
  currency: string
  timezone: string
  now: LocalDateTime
}): ExpenseFormState {
  return {
    title: '',
    amount: '',
    currency: options.currency,
    paidByMemberId: options.myMemberId,
    splitMode: 'equal',
    participants: [...options.activeMemberIds],
    customAmounts: {},
    date: options.now.date,
    time: options.now.time,
    occurredTz: options.timezone,
    category: 'other',
    note: '',
  }
}

/** 已有支出 → 编辑表单的初始值。平均分的每人金额也填进 customAmounts，换成按金额分时从这里起改 */
export function expenseToFormState(expense: ExpenseView, roundingUnit: number): ExpenseFormState {
  const local = toLocalDateTime(expense.occurredAt, expense.occurredTz)
  const splits = Array.isArray(expense.splits) ? expense.splits : []
  return {
    title: expense.title,
    amount: minorToInput(expense.amountMinor, roundingUnit),
    currency: expense.currency,
    paidByMemberId: expense.paidByMemberId,
    splitMode: expense.splitMode,
    participants: expense.splitMode === 'none' ? [] : splits.map((s) => s.memberId),
    customAmounts: Object.fromEntries(splits.map((s) => [s.memberId, minorToInput(s.amountMinor, roundingUnit)])),
    date: local.date,
    time: local.time,
    occurredTz: expense.occurredTz,
    category: expense.category,
    note: expense.note ?? '',
  }
}

const SERVER_FIELDS: Record<string, { field: ExpenseFormField; message: string }> = {
  title: { field: 'title', message: `名称不合法，最多 ${TITLE_MAX} 个字` },
  amountMinor: { field: 'amount', message: '金额要大于 0' },
  currency: { field: 'currency', message: '这个币别不支持，请重新选择' },
  paidByMemberId: { field: 'paidByMemberId', message: '个人支出只能由自己付款' },
  splitMode: { field: 'splitMode', message: '个人支出和共同支出之间不能互相切换' },
  splits: { field: 'splits', message: '分摊的人有误，检查是否重复或漏填金额' },
  occurredAt: { field: 'occurredAt', message: '时间不合法，请重新选择' },
  occurredTz: { field: 'occurredTz', message: '这个时区不合法，请重新选择' },
  category: { field: 'category', message: '这个分类不支持，请重新选择' },
  note: { field: 'note', message: `备注最多 ${NOTE_MAX} 个字` },
}

const SERVER_CODES: Record<string, { field: ExpenseFormField; message: string }> = {
  AMOUNT_NOT_ROUNDABLE: { field: 'amount', message: '这个币别不支持这么细的金额' },
  SPLIT_SUM_MISMATCH: { field: 'splits', message: '每人的金额加起来要等于总额' },
  MEMBER_LEFT: { field: 'splits', message: '有人已经退出行程，不能再加进这笔账，请重新选择' },
  MEMBER_NOT_IN_TRIP: { field: 'splits', message: '有人不是这个行程的成员，请重新选择' },
}

/** 把后端的错误挂回对应的表单字段；不是字段类的错误返回空对象，由页面弹提示 */
export function serverFieldErrors(error: ApiError): ExpenseFormErrors {
  const errors: ExpenseFormErrors = {}
  const byCode = SERVER_CODES[error.code]
  if (byCode) {
    errors[byCode.field] = byCode.message
    return errors
  }
  if (error.code !== 'VALIDATION_FAILED') return errors
  for (const { field } of error.fields) {
    const known = SERVER_FIELDS[field]
    if (known) errors[known.field] = known.message
  }
  return errors
}
