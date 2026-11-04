<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import type { DebtView } from '@/api/debts'
import { describeError, toApiError } from '@/api/errors'
import type { MemberView } from '@/api/members'
import { createRepayment } from '@/api/repayments'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { useCurrencyStore } from '@/stores/currencies'
import { repayableMinor, repayRole } from '@/utils/debts'
import { memberName } from '@/utils/expenses'
import { minorToInput, parseAmount } from '@/utils/money'
import {
  buildRepaymentItems,
  buildRepaymentRequest,
  impliedRateText,
  paidCurrencyOptions,
  repayCandidates,
  totalPaidMinor,
  type RepayLineInput,
} from '@/utils/repaymentForm'
import { newClientUuid } from '@/utils/uuid'

// 从一条真实的一对一欠款记一笔还款。一次转账可以同时结清同一对人、同一方向的其他币别欠款，
// 用一个币别付（paidCurrency）：每一项填销帐金额，转账币别和它不同时再填实付金额。
// 我是付款方：标记"已还"，状态是待确认，等对方确认后才抵债；我是收款方：直接记"已收到"，马上抵债。
// 销帐金额默认是现在能还的上限（欠款减去已标记、还没确认的）。
const props = defineProps<{
  tripId: number
  /** 从哪一条欠款打开；只能是 simplify=false 的真实欠款 */
  debt: DebtView | null
  /** 一对一欠款列表（simplify=false），从里面找同一对人、同一方向的其他币别；不给时只能结清 debt 这一笔 */
  debts?: DebtView[]
  members: MemberView[]
  myMemberId: number
}>()
const show = defineModel<boolean>('show', { required: true })
const emit = defineEmits<{
  /** 记上了（包括同一个意图的重复提交） */
  created: []
  /** 失败的原因是欠款已经变了（额度不够、内容冲突、权限），外面应该刷新 */
  stale: []
}>()

const currencies = useCurrencyStore()

/** 表单里的一笔欠款：勾选了才结清 */
interface Line {
  debt: DebtView
  selected: boolean
  clearedText: string
  paidText: string
  clearedError: string
  paidError: string
}

const lines = ref<Line[]>([])
const paidCurrency = ref('')
const note = ref('')
const noteError = ref('')
const currencyError = ref('')
const formError = ref('')
const submitting = ref(false)
const showCurrencyPicker = ref(false)

// 幂等键：每次打开弹层（一次"还款"的意图）生成一次。网络断了重试时沿用，后端认出是同一笔，不会记两次
let clientUuid = newClientUuid()

const role = computed(() => (props.debt ? repayRole(props.debt, props.myMemberId) : null))
const counterpart = computed(() => {
  if (!props.debt) return ''
  const otherId = role.value === 'payer' ? props.debt.toMemberId : props.debt.fromMemberId
  return memberName(props.members, otherId)
})

const title = computed(() => (role.value === 'receiver' ? `记下 ${counterpart.value} 还你的钱` : `还给 ${counterpart.value}`))
const hint = computed(() =>
  role.value === 'receiver'
    ? '记下后直接抵销欠款，不用对方确认。'
    : `记下后是“待确认”，${counterpart.value} 确认收到后才抵销欠款。`,
)
const submitLabel = computed(() => (role.value === 'receiver' ? '确认已收到' : '标记为已还'))

const unitOf = (code: string) => currencies.roundingUnitOf(code)

/** 只有一笔可以结清时不显示勾选框，和只还一个币别时的样子一样 */
const multiple = computed(() => lines.value.length > 1)
const selectedLines = computed(() => lines.value.filter((l) => l.selected))
const lineInputs = computed<RepayLineInput[]>(() =>
  selectedLines.value.map((l) => ({
    currency: l.debt.currency,
    maxMinor: repayableMinor(l.debt),
    clearedText: l.clearedText,
    paidText: l.paidText,
  })),
)
const crossCurrency = computed(() => selectedLines.value.some((l) => l.debt.currency !== paidCurrency.value))
/** 只结清一笔、同币别时实付就是销帐金额，不用另外显示合计 */
const showTotal = computed(() => selectedLines.value.length > 1 || crossCurrency.value)
const total = computed(() => totalPaidMinor(lineInputs.value, paidCurrency.value, unitOf))

const currencyOptions = computed(() =>
  paidCurrencyOptions(
    lines.value.map((l) => l.debt.currency),
    currencies.items.map((c) => c.code),
  ),
)
const currencyColumns = computed(() =>
  currencyOptions.value.map((code) => {
    const name = currencies.find(code)?.name
    return { text: name ? `${code}　${name}` : code, value: code }
  }),
)
const paidCurrencyText = computed(() => {
  const name = currencies.find(paidCurrency.value)?.name
  return name ? `${paidCurrency.value}　${name}` : paidCurrency.value
})

function defaultClearedText(debt: DebtView): string {
  const unit = unitOf(debt.currency)
  return unit !== undefined ? minorToInput(repayableMinor(debt), unit) : ''
}

function fillDefaults() {
  const entry = props.debt
  noteError.value = ''
  currencyError.value = ''
  formError.value = ''
  note.value = ''
  paidCurrency.value = entry?.currency ?? ''
  lines.value = entry
    ? repayCandidates(props.debts ?? [], entry).map((debt) => ({
        debt,
        selected: debt === entry,
        clearedText: defaultClearedText(debt),
        paidText: '',
        clearedError: '',
        paidError: '',
      }))
    : []
}

/** 币别列表晚到时，补上还空着的默认销帐金额 */
function fillMissingAmounts() {
  for (const line of lines.value) if (line.clearedText === '') line.clearedText = defaultClearedText(line.debt)
}

// 每次打开都是干净的表单和新的幂等键；币别列表还没回来时，回来后再填默认金额
watch(show, async (open) => {
  if (!open) return
  clientUuid = newClientUuid()
  fillDefaults()
  if (!currencies.loaded) {
    try {
      await currencies.ensureLoaded()
      if (show.value) fillMissingAmounts()
    } catch {
      // 币别没取到也能填，提交时后端会校验
    }
  }
})

function close() {
  show.value = false
}

function clearErrors() {
  formError.value = ''
  currencyError.value = ''
  for (const line of lines.value) {
    line.clearedError = ''
    line.paidError = ''
  }
}

function onCurrencyConfirm({ selectedValues }: { selectedValues: Array<string | number> }) {
  paidCurrency.value = String(selectedValues[0] ?? paidCurrency.value)
  clearErrors()
  showCurrencyPicker.value = false
}

function clearedLabel(line: Line): string {
  return line.debt.currency === paidCurrency.value ? `金额（${line.debt.currency}）` : `销帐（${line.debt.currency}）`
}

/** 跨币别时由两个金额推导出来的汇率，只作参考 */
function rateText(line: Line): string {
  const cleared = parseAmount(line.clearedText, unitOf(line.debt.currency) ?? 1)
  const paid = parseAmount(line.paidText, unitOf(paidCurrency.value) ?? 1)
  if (!cleared.ok || !paid.ok) return ''
  return impliedRateText(line.debt.currency, cleared.minor, paidCurrency.value, paid.minor)
}

/** 本地先查一遍金额，规则和后端一致（见 buildRepaymentItems） */
function validItems() {
  clearErrors()
  const result = buildRepaymentItems(lineInputs.value, paidCurrency.value, unitOf)
  if (result.ok) return result.items
  if (result.formError) formError.value = result.formError
  for (const line of selectedLines.value) {
    const errors = result.errors[line.debt.currency]
    line.clearedError = errors?.cleared ?? ''
    line.paidError = errors?.paid ?? ''
  }
  return null
}

async function submit() {
  const debt = props.debt
  if (submitting.value || !debt || !role.value) return
  const items = validItems()
  if (items === null) return

  submitting.value = true
  try {
    await createRepayment(
      props.tripId,
      buildRepaymentRequest({
        clientUuid,
        fromMemberId: debt.fromMemberId,
        toMemberId: debt.toMemberId,
        paidCurrency: paidCurrency.value,
        items,
        note: note.value,
      }),
    )
    showToast({
      message: role.value === 'receiver' ? '已记为收到' : `已标记为已还，等 ${counterpart.value} 确认`,
      type: 'success',
    })
    close()
    emit('created')
  } catch (error) {
    handleFailure(error)
  } finally {
    submitting.value = false
  }
}

/**
 * 后端的金额错误只指向 items（不带下标）：只结清一笔、同币别时挂在金额框下（和以前一样），
 * 其他情况分不出是哪一项，挂在表单底部。
 */
function showAmountError(message: string) {
  const only = selectedLines.value.length === 1 ? selectedLines.value[0] : undefined
  if (only && !crossCurrency.value) only.clearedError = message
  else formError.value = message
}

function handleFailure(error: unknown) {
  const apiError = toApiError(error)
  if (apiError.status === 401) return
  // 金额、币别、备注本身的问题挂在输入框下，改了可以直接再提交（这些请求没有被记下，幂等键可以沿用）
  if (apiError.code === 'AMOUNT_NOT_ROUNDABLE') {
    showAmountError(describeError(apiError))
    return
  }
  if (apiError.code === 'VALIDATION_FAILED') {
    const itemsMessage = apiError.fieldMessage('items')
    const currencyMessage = apiError.fieldMessage('paidCurrency')
    const noteMessage = apiError.fieldMessage('note')
    if (itemsMessage || currencyMessage || noteMessage) {
      if (itemsMessage) showAmountError('金额不对，请检查后再提交')
      if (currencyMessage) currencyError.value = '这个币别不能用来转账，请换一个'
      if (noteMessage) noteError.value = '备注最多 512 个字'
      return
    }
  }
  if (apiError.code === 'IDEMPOTENCY_KEY_REUSED') {
    // 之前那次提交其实已经记上了、而内容又被改过。换一个新的幂等键，刷新后用户自己判断要不要再记
    clientUuid = newClientUuid()
  }
  // 额度不够（可能刚有人记了还款或改了支出）、内容冲突、没有权限、对方不在行程里：欠款已经和弹层里看到的不一样了，
  // 关掉弹层并刷新，再打开时按新的欠款重新填
  showToast({ message: describeError(apiError), position: 'bottom' })
  close()
  emit('stale')
}
</script>

<template>
  <!-- 挂到 body：弹层可能在可左右滑动的标签里打开，那里的 transform 会让 fixed 定位错位 -->
  <van-popup v-model:show="show" position="bottom" round teleport="body">
    <form v-if="debt" class="popup" novalidate @submit.prevent="submit">
      <h2 class="popup__title">{{ title }}</h2>

      <van-field
        :model-value="paidCurrencyText"
        label="转账币别"
        readonly
        is-link
        class="paid-currency tm-num"
        :error-message="currencyError"
        @click="showCurrencyPicker = true"
        @keydown.enter.prevent="showCurrencyPicker = true"
      />

      <p v-if="multiple" class="popup__lead">
        {{ role === 'receiver' ? `${counterpart} 欠你` : `你欠 ${counterpart}` }}这些币别，勾选这次一起结清的：
      </p>

      <div
        v-for="line in lines"
        :key="line.debt.currency"
        class="line"
        :class="{ 'line--multiple': multiple, 'line--off': multiple && !line.selected }"
      >
        <van-checkbox
          v-if="multiple"
          v-model="line.selected"
          shape="square"
          icon-size="18px"
          class="line__check"
          @update:model-value="clearErrors"
        >
          <CurrencyAmount :amount-minor="line.debt.amountMinor" :currency="line.debt.currency" size="sm" />
        </van-checkbox>

        <dl class="facts">
          <div v-if="!multiple" class="facts__row">
            <dt>{{ role === 'receiver' ? `${counterpart} 欠你` : `你欠 ${counterpart}` }}</dt>
            <dd><CurrencyAmount :amount-minor="line.debt.amountMinor" :currency="line.debt.currency" size="sm" /></dd>
          </div>
          <div v-if="line.debt.pendingMinor > 0" class="facts__row">
            <dt>已标记、待确认</dt>
            <dd><CurrencyAmount :amount-minor="line.debt.pendingMinor" :currency="line.debt.currency" size="sm" /></dd>
          </div>
          <div class="facts__row">
            <dt>最多能还</dt>
            <dd><CurrencyAmount :amount-minor="repayableMinor(line.debt)" :currency="line.debt.currency" size="sm" /></dd>
          </div>
        </dl>

        <template v-if="line.selected">
          <van-field
            v-model="line.clearedText"
            :label="clearedLabel(line)"
            type="text"
            inputmode="decimal"
            enterkeyhint="done"
            class="amount tm-num"
            :error-message="line.clearedError"
            @update:model-value="line.clearedError = ''"
          />
          <template v-if="line.debt.currency !== paidCurrency">
            <van-field
              v-model="line.paidText"
              :label="`实付（${paidCurrency}）`"
              type="text"
              inputmode="decimal"
              enterkeyhint="done"
              class="paid tm-num"
              :error-message="line.paidError"
              @update:model-value="line.paidError = ''"
            />
            <p v-if="rateText(line)" class="line__rate tm-num">{{ rateText(line) }}（按你填的金额推算，只作参考）</p>
          </template>
        </template>
      </div>

      <div v-if="showTotal" class="total">
        <span>这次转账共</span>
        <CurrencyAmount v-if="total !== null" :amount-minor="total" :currency="paidCurrency" />
        <span v-else class="total__pending">填好各项金额后显示</span>
      </div>

      <van-field
        v-model="note"
        label="备注"
        placeholder="选填，比如“转账”"
        maxlength="512"
        :error-message="noteError"
        @update:model-value="noteError = ''"
      />
      <p class="popup__hint">{{ hint }}少还可以，剩下的继续算欠款。</p>
      <p v-if="formError" class="popup__error" role="alert">{{ formError }}</p>
      <van-button type="primary" block native-type="submit" :loading="submitting">{{ submitLabel }}</van-button>
    </form>
  </van-popup>

  <van-popup v-model:show="showCurrencyPicker" position="bottom" round teleport="body">
    <van-picker
      title="转账币别"
      :columns="currencyColumns"
      :model-value="paidCurrency ? [paidCurrency] : []"
      @confirm="onCurrencyConfirm"
      @cancel="showCurrencyPicker = false"
    />
  </van-popup>
</template>

<style lang="less" scoped>
.popup {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
  max-height: 85vh;
  overflow-y: auto;
  padding: var(--tm-space-4) var(--tm-space-4) calc(var(--tm-space-5) + env(safe-area-inset-bottom));
}

.popup__title {
  margin: 0;
  font-size: var(--tm-text-lg);
  font-weight: 600;
}

.popup__lead {
  margin: 0;
  font-size: var(--tm-text-sm);
}

.line {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-2);
}

.line--multiple {
  padding: var(--tm-space-2) var(--tm-space-3);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
}

// 没勾选的只是暗一点，仍然能点
.line--off .facts {
  opacity: 0.6;
}

.line__check {
  min-height: var(--tm-tap-min);
}

.line__rate {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.facts {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-1);
  margin: 0;
}

.facts__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  font-size: var(--tm-text-sm);

  dt {
    color: var(--tm-muted);
  }

  dd {
    margin: 0;
  }
}

.amount :deep(input),
.paid :deep(input) {
  font-family: var(--tm-font-num);
}

.total {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  font-size: var(--tm-text-sm);
  font-weight: 600;
}

.total__pending {
  color: var(--tm-muted);
  font-weight: 400;
}

.popup__hint {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.popup__error {
  margin: 0;
  color: var(--tm-danger);
  font-size: var(--tm-text-sm);
}
</style>
