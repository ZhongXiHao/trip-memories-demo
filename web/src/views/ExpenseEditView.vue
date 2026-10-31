<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import {
  getExpense,
  previewExpense,
  updateExpense,
  type ExpenseFields,
  type ExpensePreview,
  type ExpenseView,
} from '@/api/expenses'
import { listMembers, type MemberView } from '@/api/members'
import { getTrip, type TripView } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import ExpenseForm from '@/components/ExpenseForm.vue'
import { useCurrencyStore } from '@/stores/currencies'
import { expenseToFormState, serverFieldErrors, type ExpenseFormState, type OriginalOccurred } from '@/utils/expenseForm'
import { canModifyExpense } from '@/utils/expenses'
import { showError } from '@/utils/toast'

// 修改支出：只有付款人或记账人能改。PUT 是整体替换，表单带着这笔现有的全部内容；
// 时间和时区没动就不传，后端保持原值。预览时带上 expenseId：这笔里原本就有、后来退出的人可以保留。
const props = defineProps<{ tripId: number; expenseId: number }>()

const router = useRouter()
const currencies = useCurrencyStore()
const formRef = ref<InstanceType<typeof ExpenseForm>>()

type State = 'loading' | 'ready' | 'forbidden' | 'not-found' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const trip = ref<TripView | null>(null)
const members = ref<MemberView[]>([])
const expense = ref<ExpenseView | null>(null)
const initial = ref<ExpenseFormState | null>(null)
const original = ref<OriginalOccurred | undefined>()
/** 重新载入后换一个，让表单按新内容重建 */
const formKey = ref(0)
const preview = ref<ExpensePreview | null>(null)
const previewing = ref(false)
const submitting = ref(false)

// 能选的人：在籍成员，加上这笔里原本就有的人（即使已经退出，也可以原样保留）
const selectableMembers = computed(() => {
  const current = expense.value
  if (!current) return []
  const kept = new Set([current.paidByMemberId, ...current.splits.map((s) => s.memberId)])
  return members.value.filter((m) => m.status === 'active' || kept.has(m.memberId))
})

async function load() {
  state.value = 'loading'
  preview.value = null
  try {
    const [loadedTrip, loadedMembers, loadedExpense] = await Promise.all([
      getTrip(props.tripId),
      listMembers(props.tripId),
      getExpense(props.tripId, props.expenseId),
      // 表单要按币别的小数位填金额，币别列表必须先到
      currencies.ensureLoaded(),
    ])
    trip.value = loadedTrip
    members.value = loadedMembers
    expense.value = loadedExpense
    if (!canModifyExpense(loadedExpense, loadedTrip.myMemberId)) {
      state.value = 'forbidden'
      return
    }
    const formState = expenseToFormState(loadedExpense, currencies.roundingUnitOf(loadedExpense.currency) ?? 1)
    initial.value = formState
    original.value = { date: formState.date, time: formState.time, occurredTz: formState.occurredTz }
    formKey.value += 1
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

function goDetail() {
  router.push({ name: 'expense', params: { tripId: props.tripId, expenseId: props.expenseId } })
}

function goTrip() {
  router.push({ name: 'trip', params: { tripId: props.tripId } })
}

async function handleFailure(error: unknown) {
  const apiError = toApiError(error)
  if (apiError.status === 401) return
  preview.value = null
  const fieldErrors = serverFieldErrors(apiError)
  if (Object.keys(fieldErrors).length > 0) {
    await formRef.value?.showErrors(fieldErrors)
  } else if (apiError.code === 'VERSION_CONFLICT') {
    // 别人刚改过：载入最新内容，让用户在新内容上再改
    showToast({ message: '这笔支出刚被别人改过，已载入最新内容，请重新修改', duration: 4000 })
    await load()
  } else if (apiError.status === 403 || apiError.status === 404) {
    // 付款人被改成了别人，或者这笔刚被删掉：回到详情页，让页面按最新情况显示
    showError(apiError)
    await router.replace({ name: 'expense', params: { tripId: props.tripId, expenseId: props.expenseId } })
  } else {
    showError(apiError)
  }
}

async function onPreview(fields: ExpenseFields) {
  previewing.value = true
  try {
    preview.value = await previewExpense(props.tripId, { ...fields, expenseId: props.expenseId })
  } catch (error) {
    await handleFailure(error)
  } finally {
    previewing.value = false
  }
}

async function onConfirm(fields: ExpenseFields) {
  if (!expense.value) return
  submitting.value = true
  try {
    await updateExpense(props.tripId, props.expenseId, { ...fields, version: expense.value.version })
    showToast({ message: '已保存', type: 'success' })
    await router.replace({ name: 'expense', params: { tripId: props.tripId, expenseId: props.expenseId } })
  } catch (error) {
    await handleFailure(error)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <BoardHeader title="修改支出" :subtitle="expense?.title" back-label="返回支出" narrow @back="goDetail" />

    <main class="expense-edit tm-container tm-container--narrow">
      <div v-if="state === 'loading'" class="status" role="status">
        <van-loading size="20" />
        <span>正在加载…</span>
      </div>

      <div v-else-if="state === 'not-found'" class="status status--box" role="alert">
        <p class="status__text">找不到这笔支出：它可能已经被删除，或者你已经不在这个行程里了。</p>
        <van-button size="small" plain type="primary" @click="goTrip">返回行程</van-button>
      </div>

      <div v-else-if="state === 'forbidden'" class="status status--box" role="alert">
        <p class="status__text">只有付款人或记账人可以修改这笔支出。</p>
        <van-button size="small" plain type="primary" @click="goDetail">返回支出</van-button>
      </div>

      <div v-else-if="state === 'error'" class="status status--box" role="alert">
        <p class="status__text">{{ errorMessage }}</p>
        <van-button size="small" plain type="primary" @click="load">重试</van-button>
      </div>

      <ExpenseForm
        v-else-if="trip && initial"
        :key="formKey"
        ref="formRef"
        :initial="initial"
        :members="selectableMembers"
        :all-members="members"
        :my-member-id="trip.myMemberId"
        editing
        :original="original"
        :preview="preview"
        :previewing="previewing"
        :submitting="submitting"
        confirm-label="确认保存"
        @preview="onPreview"
        @confirm="onConfirm"
        @invalidate="preview = null"
      />
    </main>
  </div>
</template>

<style lang="less" scoped>
.expense-edit {
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
