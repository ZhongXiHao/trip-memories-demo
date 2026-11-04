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
import { amountProblemText, formatMinor, minorToInput, parseAmount } from '@/utils/money'
import { newClientUuid } from '@/utils/uuid'

// 从一条真实的一对一欠款记一笔还款（同币别：实付 = 销帐）。
// 我是付款方：标记"已还"，状态是待确认，等对方确认后才抵债；我是收款方：直接记"已收到"，马上抵债。
// 金额默认是现在能还的上限（欠款减去已标记、还没确认的）。
const props = defineProps<{
  tripId: number
  /** 要还的那条欠款；只能是 simplify=false 的真实欠款 */
  debt: DebtView | null
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

const amountText = ref('')
const note = ref('')
const amountError = ref('')
const noteError = ref('')
const submitting = ref(false)

// 幂等键：每次打开弹层（一次"还款"的意图）生成一次。网络断了重试时沿用，后端认出是同一笔，不会记两次
let clientUuid = newClientUuid()

const role = computed(() => (props.debt ? repayRole(props.debt, props.myMemberId) : null))
const maxMinor = computed(() => (props.debt ? repayableMinor(props.debt) : 0))
const unit = computed(() => (props.debt ? currencies.roundingUnitOf(props.debt.currency) : undefined))
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

function fillDefaults() {
  amountError.value = ''
  noteError.value = ''
  note.value = ''
  amountText.value = unit.value !== undefined ? minorToInput(maxMinor.value, unit.value) : ''
}

// 每次打开都是干净的表单和新的幂等键；币别列表还没回来时，回来后再填默认金额
watch(show, async (open) => {
  if (!open) return
  clientUuid = newClientUuid()
  fillDefaults()
  if (unit.value === undefined) {
    try {
      await currencies.ensureLoaded()
      if (show.value && amountText.value === '') fillDefaults()
    } catch {
      // 币别没取到也能填，提交时后端会校验
    }
  }
})

function close() {
  show.value = false
}

/** 本地先查一遍金额，和后端的规则一致：大于 0、符合币别的小数位、不超过现在能还的上限 */
function validAmount(): number | null {
  const debt = props.debt
  if (!debt) return null
  const roundingUnit = unit.value ?? 1
  const parsed = parseAmount(amountText.value, roundingUnit)
  if (!parsed.ok) {
    amountError.value = amountProblemText(parsed.problem, roundingUnit)
    return null
  }
  if (parsed.minor > maxMinor.value) {
    amountError.value = `最多能还 ${debt.currency} ${formatMinor(maxMinor.value, unit.value)}`
    return null
  }
  return parsed.minor
}

async function submit() {
  const debt = props.debt
  if (submitting.value || !debt || !role.value) return
  const minor = validAmount()
  if (minor === null) return

  submitting.value = true
  try {
    const trimmedNote = note.value.trim()
    await createRepayment(props.tripId, {
      clientUuid,
      fromMemberId: debt.fromMemberId,
      toMemberId: debt.toMemberId,
      paidCurrency: debt.currency,
      items: [{ clearedCurrency: debt.currency, clearedAmountMinor: minor, paidAmountMinor: minor }],
      ...(trimmedNote ? { note: trimmedNote } : {}),
    })
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

function handleFailure(error: unknown) {
  const apiError = toApiError(error)
  if (apiError.status === 401) return
  // 金额、备注本身的问题挂在输入框下，改了可以直接再提交（这些请求没有被记下，幂等键可以沿用）
  if (apiError.code === 'AMOUNT_NOT_ROUNDABLE') {
    amountError.value = describeError(apiError)
    return
  }
  if (apiError.code === 'VALIDATION_FAILED') {
    const itemsMessage = apiError.fieldMessage('items')
    const noteMessage = apiError.fieldMessage('note')
    if (itemsMessage || noteMessage) {
      if (itemsMessage) amountError.value = '金额不对，请检查后再提交'
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

      <dl class="facts">
        <div class="facts__row">
          <dt>{{ role === 'receiver' ? `${counterpart} 欠你` : `你欠 ${counterpart}` }}</dt>
          <dd><CurrencyAmount :amount-minor="debt.amountMinor" :currency="debt.currency" size="sm" /></dd>
        </div>
        <div v-if="debt.pendingMinor > 0" class="facts__row">
          <dt>已标记、待确认</dt>
          <dd><CurrencyAmount :amount-minor="debt.pendingMinor" :currency="debt.currency" size="sm" /></dd>
        </div>
        <div class="facts__row">
          <dt>最多能还</dt>
          <dd><CurrencyAmount :amount-minor="maxMinor" :currency="debt.currency" size="sm" /></dd>
        </div>
      </dl>

      <van-field
        v-model="amountText"
        :label="`金额（${debt.currency}）`"
        type="text"
        inputmode="decimal"
        enterkeyhint="done"
        class="amount tm-num"
        :error-message="amountError"
        @update:model-value="amountError = ''"
      />
      <van-field
        v-model="note"
        label="备注"
        placeholder="选填，比如“转账”"
        maxlength="512"
        :error-message="noteError"
        @update:model-value="noteError = ''"
      />
      <p class="popup__hint">{{ hint }}只能还同一币别；少还可以，剩下的继续算欠款。</p>
      <van-button type="primary" block native-type="submit" :loading="submitting">{{ submitLabel }}</van-button>
    </form>
  </van-popup>
</template>

<style lang="less" scoped>
.popup {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
  padding: var(--tm-space-4) var(--tm-space-4) calc(var(--tm-space-5) + env(safe-area-inset-bottom));
}

.popup__title {
  margin: 0;
  font-size: var(--tm-text-lg);
  font-weight: 600;
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

.amount :deep(input) {
  font-family: var(--tm-font-num);
}

.popup__hint {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}
</style>
