<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import { toApiError } from '@/api/errors'
import {
  EXPENSE_CATEGORIES,
  type ExpenseFields,
  type ExpensePreview,
  type SplitMode,
} from '@/api/expenses'
import type { MemberView } from '@/api/members'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { useCurrencyStore } from '@/stores/currencies'
import {
  balanceText,
  customBalance,
  hasErrors,
  NOTE_MAX,
  TITLE_MAX,
  toExpenseFields,
  validateExpenseForm,
  type ExpenseFormErrors,
  type ExpenseFormState,
  type OriginalOccurred,
} from '@/utils/expenseForm'
import { categoryLabel, memberName } from '@/utils/expenses'
import { formatMinor, fractionDigits } from '@/utils/money'
import { TIMEZONE_OPTIONS, timezoneLabel } from '@/utils/timezones'

// 新建和修改支出共用的表单。只负责收集、校验和显示预览，不发请求：
// 1. 点"预览分摊"：本地校验通过后发 preview 事件，页面去调预览接口，把结果通过 preview 属性传回来；
// 2. 预览出来后按钮变成"确认"：发 confirm 事件，带的是刚才预览的那份内容（预览后改了任何地方，预览就作废，要重新预览）。
// 页面收到后端的字段错误时，调用 showErrors 把错误挂回对应输入框。
const props = defineProps<{
  /** 初始值。只在创建表单时读一次 */
  initial: ExpenseFormState
  /** 可以选作付款人、分摊者的成员（页面决定：在籍的，加上修改时这笔里原本就有的人） */
  members: MemberView[]
  /** 成员列表全集（包含已退出的），用来显示名字 */
  allMembers: MemberView[]
  myMemberId: number
  /** 修改时：个人支出和共同支出之间不能互相切换 */
  editing?: boolean
  /** 修改时：原来的时间，没动就不传给后端 */
  original?: OriginalOccurred
  preview: ExpensePreview | null
  previewing?: boolean
  submitting?: boolean
  confirmLabel: string
}>()

const emit = defineEmits<{
  preview: [fields: ExpenseFields]
  confirm: [fields: ExpenseFields]
  /** 预览之后表单又被改了：页面应清掉预览结果 */
  invalidate: []
}>()

const form = reactive<ExpenseFormState>({
  ...props.initial,
  participants: [...props.initial.participants],
  customAmounts: { ...props.initial.customAmounts },
})
const errors = ref<ExpenseFormErrors>({})

// ---- 币别 ----
// 金额输入框一律用 Vant 的 number 类型（允许小数点）。整元币别也不用 digit：digit 会悄悄吞掉小数点，
// 用户输入 1000.5 会变成 10005；保留小数点，再由校验提示"只能填整数"。
const currencies = useCurrencyStore()
const currencyLoadFailed = ref(false)
const roundingUnit = computed(() => currencies.roundingUnitOf(form.currency))
const amountPlaceholder = computed(() => {
  const digits = roundingUnit.value === undefined ? 2 : fractionDigits(roundingUnit.value)
  return digits === 0 ? '0' : `0.${'0'.repeat(digits)}`
})
const currencyColumns = computed(() => currencies.items.map((c) => ({ text: `${c.code}　${c.name}`, value: c.code })))
const currencyText = computed(() => {
  const found = currencies.find(form.currency)
  return found ? `${found.code}　${found.name}` : form.currency
})

async function loadCurrencies() {
  currencyLoadFailed.value = false
  try {
    await currencies.ensureLoaded()
    // 没选、或者上次用的币别已经不在列表里了：换成列表第一个，免得提交按钮一直不可用
    if (!currencies.find(form.currency)) form.currency = currencies.items[0]?.code ?? ''
  } catch (error) {
    if (toApiError(error).status !== 401) currencyLoadFailed.value = true
  }
}

onMounted(loadCurrencies)

// ---- 成员 ----
function nameOf(memberId: number): string {
  const member = props.allMembers.find((m) => m.memberId === memberId)
  const name = memberName(props.allMembers, memberId)
  return member?.status === 'left' ? `${name}（已退出）` : name
}

const payerColumns = computed(() =>
  props.members.map((m) => ({
    text: m.memberId === props.myMemberId ? `${nameOf(m.memberId)}（我）` : nameOf(m.memberId),
    value: m.memberId,
  })),
)
const payerText = computed(() => {
  if (form.paidByMemberId === null) return ''
  const name = nameOf(form.paidByMemberId)
  return form.paidByMemberId === props.myMemberId ? `${name}（我）` : name
})

// 分摊者按成员列表的顺序发给后端（余数规则：付款人不在名单里时给名单第一位）
function setParticipants(ids: Array<number | string>) {
  const chosen = new Set(ids.map(Number))
  form.participants = props.members.map((m) => m.memberId).filter((id) => chosen.has(id))
  errors.value.splits = undefined
}

const allSelected = computed(() => props.members.every((m) => form.participants.includes(m.memberId)))

function toggleAll() {
  setParticipants(allSelected.value ? [] : props.members.map((m) => m.memberId))
}

// ---- 分摊方式 ----
const MODE_OPTIONS: Array<{ value: SplitMode; label: string }> = [
  { value: 'equal', label: '平均分' },
  { value: 'custom', label: '按金额分' },
  { value: 'none', label: '个人支出' },
]
// 修改时：个人支出只能保持个人支出，共同支出只能在平均分、按金额分之间换
const modeOptions = computed(() => {
  if (!props.editing) return MODE_OPTIONS
  return props.initial.splitMode === 'none'
    ? MODE_OPTIONS.filter((o) => o.value === 'none')
    : MODE_OPTIONS.filter((o) => o.value !== 'none')
})

function setMode(mode: SplitMode) {
  form.splitMode = mode
  // 个人支出只能自己付
  if (mode === 'none') form.paidByMemberId = props.myMemberId
  errors.value.splitMode = undefined
  errors.value.splits = undefined
  errors.value.paidByMemberId = undefined
}

const balance = computed(() => {
  if (form.splitMode !== 'custom' || roundingUnit.value === undefined) return null
  const { remainingMinor } = customBalance(form, roundingUnit.value)
  if (remainingMinor === null) return null
  return balanceText(remainingMinor, roundingUnit.value) ?? '刚好分完'
})

// ---- 时间和时区 ----
const timezoneColumns = computed(() => {
  const columns = TIMEZONE_OPTIONS.map((o) => ({ text: `${o.label}　${o.value}`, value: o.value }))
  if (form.occurredTz && !TIMEZONE_OPTIONS.some((o) => o.value === form.occurredTz)) {
    columns.unshift({ text: form.occurredTz, value: form.occurredTz })
  }
  return columns
})

// ---- 弹层 ----
const showCurrency = ref(false)
const showPayer = ref(false)
const showTimezone = ref(false)

type PickerValue = { selectedValues: Array<string | number> }

function onCurrencyConfirm({ selectedValues }: PickerValue) {
  form.currency = String(selectedValues[0] ?? '')
  errors.value.currency = undefined
  errors.value.amount = undefined
  showCurrency.value = false
}

function onPayerConfirm({ selectedValues }: PickerValue) {
  const value = selectedValues[0]
  form.paidByMemberId = value === undefined ? null : Number(value)
  errors.value.paidByMemberId = undefined
  showPayer.value = false
}

function onTimezoneConfirm({ selectedValues }: PickerValue) {
  form.occurredTz = String(selectedValues[0] ?? '')
  errors.value.occurredTz = undefined
  errors.value.occurredAt = undefined
  showTimezone.value = false
}

function clearSplitAmountError(memberId: number) {
  if (errors.value.splitAmounts) delete errors.value.splitAmounts[memberId]
  errors.value.splits = undefined
}

// ---- 预览和提交 ----
// 发去预览的那份内容；确认时原样提交，保证"看到的分摊"就是"记下的分摊"
let previewedFields: ExpenseFields | null = null

watch(
  form,
  () => {
    if (props.preview) emit('invalidate')
    previewedFields = null
  },
  { deep: true },
)

async function scrollToFirstError() {
  await nextTick()
  document
    .querySelector('.expense-form .van-field__error-message, .expense-form .form-error')
    ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

async function requestPreview() {
  if (props.previewing || props.submitting || roundingUnit.value === undefined) return
  errors.value = validateExpenseForm(form, roundingUnit.value, props.myMemberId)
  if (hasErrors(errors.value)) {
    await scrollToFirstError()
    return
  }
  previewedFields = toExpenseFields(form, roundingUnit.value, props.original)
  emit('preview', previewedFields)
}

function confirm() {
  if (props.submitting || !props.preview || !previewedFields) return
  emit('confirm', previewedFields)
}

function onSubmit() {
  if (props.preview && previewedFields) confirm()
  else void requestPreview()
}

/** 页面发请求后收到后端的字段错误时调用 */
async function showErrors(serverErrors: ExpenseFormErrors) {
  errors.value = serverErrors
  await scrollToFirstError()
}

defineExpose({ showErrors })

const previewSplits = computed(() => (Array.isArray(props.preview?.splits) ? props.preview.splits : []))
const previewWarnings = computed(() => (Array.isArray(props.preview?.warnings) ? props.preview.warnings : []))
</script>

<template>
  <form class="expense-form" novalidate @submit.prevent="onSubmit">
    <div class="card">
      <van-field
        v-model="form.title"
        label="名称"
        placeholder="比如：拉面、出租车"
        required
        :maxlength="TITLE_MAX"
        autocomplete="off"
        enterkeyhint="next"
        :error-message="errors.title"
        @update:model-value="errors.title = undefined"
      />
      <van-field
        :model-value="currencyText"
        label="币别"
        :placeholder="currencyLoadFailed ? '币别没加载出来，点这里重试' : '选择币别'"
        readonly
        is-link
        required
        :error-message="errors.currency"
        @click="currencyLoadFailed ? loadCurrencies() : (showCurrency = true)"
        @keydown.enter.prevent="currencyLoadFailed ? loadCurrencies() : (showCurrency = true)"
      />
      <van-field
        v-model="form.amount"
        label="金额"
        type="number"
        :placeholder="amountPlaceholder"
        required
        autocomplete="off"
        class="amount-field"
        :error-message="errors.amount"
        @update:model-value="errors.amount = undefined"
      >
        <template #button>
          <span class="amount-field__code tm-num">{{ form.currency }}</span>
        </template>
      </van-field>
    </div>

    <div class="card">
      <van-field
        :model-value="payerText"
        label="付款人"
        placeholder="选择谁付的钱"
        readonly
        :is-link="form.splitMode !== 'none'"
        :disabled="form.splitMode === 'none'"
        required
        :error-message="errors.paidByMemberId"
        @click="form.splitMode !== 'none' && (showPayer = true)"
        @keydown.enter.prevent="form.splitMode !== 'none' && (showPayer = true)"
      />
      <div class="block">
        <p id="mode-label" class="block__label">分摊方式</p>
        <div class="segmented" role="radiogroup" aria-labelledby="mode-label">
          <button
            v-for="option in modeOptions"
            :key="option.value"
            type="button"
            role="radio"
            class="segmented__item"
            :class="{ 'segmented__item--active': form.splitMode === option.value }"
            :aria-checked="form.splitMode === option.value"
            @click="setMode(option.value)"
          >
            {{ option.label }}
          </button>
        </div>
        <p v-if="errors.splitMode" class="form-error">{{ errors.splitMode }}</p>
        <p v-if="form.splitMode === 'none'" class="block__hint">只算你自己的，只有你看得见，不算进任何人的欠款。</p>
      </div>
    </div>

    <section v-if="form.splitMode !== 'none'" class="card" aria-labelledby="participants-label">
      <div class="block block--head">
        <p id="participants-label" class="block__label">
          {{ form.splitMode === 'equal' ? '参与平均分的人' : '每人分摊的金额' }}
        </p>
        <button type="button" class="link-button" @click="toggleAll">{{ allSelected ? '全不选' : '全选' }}</button>
      </div>
      <van-checkbox-group :model-value="form.participants" @update:model-value="setParticipants">
        <ul class="people">
          <li v-for="member in members" :key="member.memberId" class="person">
            <van-checkbox :name="member.memberId" shape="square" class="person__check">
              {{ nameOf(member.memberId) }}<span v-if="member.memberId === myMemberId" class="tag">我</span>
            </van-checkbox>
            <van-field
              v-if="form.splitMode === 'custom' && form.participants.includes(member.memberId)"
              v-model="form.customAmounts[member.memberId]"
              type="number"
              :placeholder="amountPlaceholder"
              :aria-label="`${nameOf(member.memberId)} 分摊的金额`"
              autocomplete="off"
              input-align="right"
              class="person__amount"
              :error-message="errors.splitAmounts?.[member.memberId]"
              @update:model-value="clearSplitAmountError(member.memberId)"
            />
          </li>
        </ul>
      </van-checkbox-group>
      <p v-if="balance" class="block__hint block__hint--balance tm-num" aria-live="polite">{{ balance }}</p>
      <p v-if="errors.splits" class="form-error form-error--pad">{{ errors.splits }}</p>
    </section>

    <div class="card">
      <van-field
        v-model="form.date"
        label="日期"
        type="date"
        required
        :error-message="errors.occurredAt"
        @update:model-value="errors.occurredAt = undefined"
      />
      <van-field v-model="form.time" label="时间" type="time" required @update:model-value="errors.occurredAt = undefined" />
      <van-field
        :model-value="timezoneLabel(form.occurredTz)"
        label="时区"
        readonly
        is-link
        :error-message="errors.occurredTz"
        @click="showTimezone = true"
        @keydown.enter.prevent="showTimezone = true"
      />
    </div>

    <div class="card">
      <div class="block">
        <p id="category-label" class="block__label">分类</p>
        <div class="chips" role="radiogroup" aria-labelledby="category-label">
          <button
            v-for="category in EXPENSE_CATEGORIES"
            :key="category"
            type="button"
            role="radio"
            class="chip"
            :class="{ 'chip--active': form.category === category }"
            :aria-checked="form.category === category"
            @click="form.category = category"
          >
            {{ categoryLabel(category) }}
          </button>
        </div>
      </div>
      <van-field
        v-model="form.note"
        label="备注"
        type="textarea"
        rows="2"
        autosize
        placeholder="选填"
        :maxlength="NOTE_MAX"
        show-word-limit
        :error-message="errors.note"
        @update:model-value="errors.note = undefined"
      />
    </div>

    <section v-if="preview" class="card preview" aria-labelledby="preview-title" aria-live="polite">
      <div class="preview__head">
        <h2 id="preview-title" class="preview__title">分摊结果</h2>
        <CurrencyAmount :amount-minor="preview.amountMinor" :currency="preview.currency" />
      </div>
      <p v-if="form.splitMode === 'none'" class="block__hint">个人支出：全部算在你自己名下。</p>
      <ul v-else class="people">
        <li v-for="split in previewSplits" :key="split.memberId" class="person person--preview">
          <span class="person__name">
            {{ nameOf(split.memberId) }}
            <span v-if="typeof split.remainderMinor === 'number' && split.remainderMinor > 0" class="person__extra">
              含除不尽的零头 <span class="tm-num">{{ formatMinor(split.remainderMinor, roundingUnit) }}</span>
            </span>
          </span>
          <CurrencyAmount :amount-minor="split.amountMinor" :currency="preview.currency" size="sm" />
        </li>
      </ul>
      <ul v-if="previewWarnings.length" class="warnings">
        <li v-for="warning in previewWarnings" :key="warning.code">{{ warning.message }}</li>
      </ul>
    </section>

    <van-button
      type="primary"
      block
      native-type="submit"
      :loading="previewing || submitting"
      :disabled="currencyLoadFailed || roundingUnit === undefined"
    >
      {{ preview ? confirmLabel : '预览分摊' }}
    </van-button>
    <p v-if="!preview" class="hint">先看一眼每人分多少，确认后才会记下。</p>

    <van-popup v-model:show="showCurrency" position="bottom" round>
      <van-picker
        title="币别"
        :columns="currencyColumns"
        :model-value="form.currency ? [form.currency] : []"
        @confirm="onCurrencyConfirm"
        @cancel="showCurrency = false"
      />
    </van-popup>

    <van-popup v-model:show="showPayer" position="bottom" round>
      <van-picker
        title="付款人"
        :columns="payerColumns"
        :model-value="form.paidByMemberId === null ? [] : [form.paidByMemberId]"
        @confirm="onPayerConfirm"
        @cancel="showPayer = false"
      />
    </van-popup>

    <van-popup v-model:show="showTimezone" position="bottom" round>
      <van-picker
        title="这笔支出所在的时区"
        :columns="timezoneColumns"
        :model-value="form.occurredTz ? [form.occurredTz] : []"
        @confirm="onTimezoneConfirm"
        @cancel="showTimezone = false"
      />
    </van-popup>
  </form>
</template>

<style lang="less" scoped>
.expense-form {
  // 输入框正文不小于 16px，否则 iOS 聚焦时会自动放大页面
  --van-cell-font-size: var(--tm-text-md);
  --van-checkbox-checked-icon-color: var(--tm-primary);

  display: flex;
  flex-direction: column;
  gap: var(--tm-space-4);
}

.card {
  overflow: hidden;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
}

.amount-field__code {
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
  font-weight: 600;
}

.amount-field :deep(.van-field__control),
.person__amount :deep(.van-field__control) {
  font-family: var(--tm-font-num);
}

.block {
  padding: var(--tm-space-3) var(--tm-space-4);
  border-top: 1px solid var(--tm-line);

  &:first-child {
    border-top: 0;
  }
}

.block--head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  padding-block: var(--tm-space-1);
}

.block__label {
  margin: 0;
  font-size: var(--tm-text-sm);
  font-weight: 600;
}

.block--head .block__label {
  margin: 0;
}

.block:not(.block--head) .block__label {
  margin-bottom: var(--tm-space-2);
}

.block__hint {
  margin: var(--tm-space-2) 0 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.block__hint--balance {
  margin: 0;
  padding: var(--tm-space-2) var(--tm-space-4);
  border-top: 1px solid var(--tm-line);
  color: var(--tm-navy);
  font-size: var(--tm-text-sm);
  text-align: right;
}

.segmented {
  display: flex;
  gap: var(--tm-space-2);
}

.segmented__item,
.chip {
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-3);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  color: var(--tm-navy);
  font: inherit;
  font-size: var(--tm-text-sm);
  cursor: pointer;
}

.segmented__item {
  flex: 1;
}

// 选中：实心主色 + 加粗，不只靠颜色区分
.segmented__item--active,
.chip--active {
  border-color: var(--tm-primary);
  background: var(--tm-primary);
  color: var(--tm-surface);
  font-weight: 600;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--tm-space-2);
}

.people {
  margin: 0;
  padding: 0;
  list-style: none;
}

.person {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  min-height: 52px;
  padding: 0 var(--tm-space-4);
  border-top: 1px solid var(--tm-line);
}

.person__check {
  flex: 1;
  min-width: 0;
  min-height: var(--tm-tap-min);
}

.person__amount {
  flex: none;
  width: 140px;
  padding: var(--tm-space-1) 0;
}

.person--preview {
  justify-content: space-between;
  min-height: var(--tm-tap-min);
}

.person__name {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-wrap: anywhere;
}

.person__extra {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.tag {
  margin-left: var(--tm-space-2);
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-tag);
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  line-height: 20px;
}

.link-button {
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-2);
  border: 0;
  background: transparent;
  color: var(--tm-primary);
  font: inherit;
  font-size: var(--tm-text-sm);
  cursor: pointer;
}

.form-error {
  margin: var(--tm-space-2) 0 0;
  color: var(--tm-danger);
  font-size: var(--tm-text-xs);
}

.form-error--pad {
  margin: 0;
  padding: 0 var(--tm-space-4) var(--tm-space-3);
}

.preview {
  border-color: var(--tm-primary);
}

.preview__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  padding: var(--tm-space-3) var(--tm-space-4);
}

.preview__title {
  margin: 0;
  font-size: var(--tm-text-md);
  font-weight: 600;
}

.preview > .block__hint {
  margin: 0;
  padding: 0 var(--tm-space-4) var(--tm-space-3);
}

.warnings {
  margin: 0;
  padding: var(--tm-space-3) var(--tm-space-4) var(--tm-space-3) var(--tm-space-6);
  border-top: 1px solid var(--tm-line);
  font-size: var(--tm-text-sm);
}

.hint {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  text-align: center;
}
</style>
