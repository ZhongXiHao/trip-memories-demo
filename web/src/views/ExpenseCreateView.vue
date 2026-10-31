<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import { createExpense, previewExpense, type ExpenseFields, type ExpensePreview } from '@/api/expenses'
import { listMembers, type MemberView } from '@/api/members'
import { getTrip, type TripView } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import ExpenseForm from '@/components/ExpenseForm.vue'
import { newExpenseFormState, serverFieldErrors, type ExpenseFormState } from '@/utils/expenseForm'
import { showError } from '@/utils/toast'
import { newClientUuid } from '@/utils/uuid'
import { nowIn } from '@/utils/zonedTime'

// 记一笔：先预览分摊，确认后再创建。
const props = defineProps<{ tripId: number }>()

const router = useRouter()
const formRef = ref<InstanceType<typeof ExpenseForm>>()

type State = 'loading' | 'ready' | 'not-found' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const trip = ref<TripView | null>(null)
const members = ref<MemberView[]>([])
const initial = ref<ExpenseFormState | null>(null)
const preview = ref<ExpensePreview | null>(null)
const previewing = ref(false)
const submitting = ref(false)

// 幂等键：这次"记一笔"只生成一次。网络断了重试时沿用，后端认出是同一笔，不会记两次
let clientUuid = newClientUuid()

const activeMembers = computed(() => members.value.filter((m) => m.status === 'active'))

// 同一个行程里上次记账用的币别（只是方便，存不下也无所谓）
const lastCurrencyKey = () => `tm.lastCurrency.${props.tripId}`

function lastCurrency(): string | null {
  try {
    return localStorage.getItem(lastCurrencyKey())
  } catch {
    return null
  }
}

function rememberCurrency(code: string) {
  try {
    localStorage.setItem(lastCurrencyKey(), code)
  } catch {
    // 存不下就下次再选
  }
}

async function load() {
  state.value = 'loading'
  try {
    const [loadedTrip, loadedMembers] = await Promise.all([getTrip(props.tripId), listMembers(props.tripId)])
    trip.value = loadedTrip
    members.value = loadedMembers
    initial.value = newExpenseFormState({
      myMemberId: loadedTrip.myMemberId,
      activeMemberIds: loadedMembers.filter((m) => m.status === 'active').map((m) => m.memberId),
      currency: lastCurrency() ?? loadedTrip.displayCurrency,
      timezone: loadedTrip.timezone,
      now: nowIn(loadedTrip.timezone),
    })
    state.value = 'ready'
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 401) return
    if (apiError.status === 404) {
      state.value = 'not-found'
    } else {
      errorMessage.value = describeError(apiError)
      state.value = 'error'
    }
  }
}

onMounted(load)

function goTrip() {
  router.push({ name: 'trip', params: { tripId: props.tripId } })
}

function goList() {
  router.push({ name: 'home' })
}

/** 预览和创建共用的失败处理：字段错误挂回表单，其余弹提示 */
async function handleFailure(error: unknown) {
  const apiError = toApiError(error)
  if (apiError.status === 401) return
  preview.value = null
  const fieldErrors = serverFieldErrors(apiError)
  if (Object.keys(fieldErrors).length > 0) {
    await formRef.value?.showErrors(fieldErrors)
    return
  }
  if (apiError.code === 'IDEMPOTENCY_KEY_REUSED') {
    // 之前那次提交其实已经记上了、而内容又被改过。换一个新的幂等键，用户确认列表后可以再提交
    clientUuid = newClientUuid()
  }
  if (apiError.status === 404) {
    state.value = 'not-found'
    return
  }
  showError(apiError)
}

async function onPreview(fields: ExpenseFields) {
  previewing.value = true
  try {
    preview.value = await previewExpense(props.tripId, fields)
  } catch (error) {
    await handleFailure(error)
  } finally {
    previewing.value = false
  }
}

async function onConfirm(fields: ExpenseFields) {
  submitting.value = true
  try {
    await createExpense(props.tripId, { ...fields, clientUuid })
    rememberCurrency(fields.currency)
    showToast({ message: '已记上', type: 'success' })
    await router.replace({ name: 'trip', params: { tripId: props.tripId } })
  } catch (error) {
    await handleFailure(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <BoardHeader title="记一笔" :subtitle="trip?.name" back-label="返回行程" narrow @back="goTrip" />

    <main class="expense-new tm-container tm-container--narrow">
      <div v-if="state === 'loading'" class="status" role="status">
        <van-loading size="20" />
        <span>正在加载…</span>
      </div>

      <div v-else-if="state === 'not-found'" class="status status--box" role="alert">
        <p class="status__text">找不到这个行程，或者你已经不在这个行程里了。</p>
        <van-button size="small" plain type="primary" class="tm-button-secondary" @click="goList">回到我的行程</van-button>
      </div>

      <div v-else-if="state === 'error'" class="status status--box" role="alert">
        <p class="status__text">{{ errorMessage }}</p>
        <van-button size="small" plain type="primary" class="tm-button-secondary" @click="load">重试</van-button>
      </div>

      <ExpenseForm
        v-else-if="trip && initial"
        ref="formRef"
        :initial="initial"
        :members="activeMembers"
        :all-members="members"
        :my-member-id="trip.myMemberId"
        :preview="preview"
        :previewing="previewing"
        :submitting="submitting"
        confirm-label="确认记账"
        @preview="onPreview"
        @confirm="onConfirm"
        @invalidate="preview = null"
      />
    </main>
  </div>
</template>

<style lang="less" scoped>
.expense-new {
  padding-block: var(--tm-space-4) calc(var(--tm-space-6) + env(safe-area-inset-bottom));
}

.status {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  padding: var(--tm-space-4);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.status--box {
  flex-direction: column;
  align-items: flex-start;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  color: var(--tm-navy);
}

.status__text {
  margin: 0;
}
</style>
