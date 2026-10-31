<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import { deleteExpense, getExpense, type ExpenseView } from '@/api/expenses'
import { listMembers, type MemberView } from '@/api/members'
import { getTrip, type TripView } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { confirmAction } from '@/utils/dialog'
import { canModifyExpense, categoryLabel, dayLabel, isMemberLeft, memberName, splitModeLabel } from '@/utils/expenses'
import { timezoneLabel } from '@/utils/timezones'
import { showError } from '@/utils/toast'
import { shortLocalDate, toLocalDateTime, weekdayOf } from '@/utils/zonedTime'

// 一笔支出的详情：金额、谁付的、怎么分的。付款人或记账人可以修改、删除（负责人也没有特权）。
const props = defineProps<{ tripId: number; expenseId: number }>()

const router = useRouter()

type State = 'loading' | 'ready' | 'not-found' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const trip = ref<TripView | null>(null)
const members = ref<MemberView[]>([])
const expense = ref<ExpenseView | null>(null)
const busy = ref(false)

const canModify = computed(() => (expense.value && trip.value ? canModifyExpense(expense.value, trip.value.myMemberId) : false))

const when = computed(() => {
  if (!expense.value) return ''
  const { date, time } = toLocalDateTime(expense.value.occurredAt, expense.value.occurredTz)
  return `${shortLocalDate(date)} ${weekdayOf(date)} ${time}`
})

// 和行程时区不同时才写出时区，免得每一笔都多一行
const zoneText = computed(() => {
  if (!expense.value || !trip.value || expense.value.occurredTz === trip.value.timezone) return null
  return timezoneLabel(expense.value.occurredTz)
})

const splits = computed(() => (Array.isArray(expense.value?.splits) ? expense.value.splits : []))

function nameOf(memberId: number): string {
  const name = memberName(members.value, memberId)
  return isMemberLeft(members.value, memberId) ? `${name}（已退出）` : name
}

async function load() {
  state.value = 'loading'
  try {
    const [loadedTrip, loadedMembers, loadedExpense] = await Promise.all([
      getTrip(props.tripId),
      listMembers(props.tripId),
      getExpense(props.tripId, props.expenseId),
    ])
    trip.value = loadedTrip
    members.value = loadedMembers
    expense.value = loadedExpense
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
watch(() => [props.tripId, props.expenseId], load)

function goTrip() {
  router.push({ name: 'trip', params: { tripId: props.tripId } })
}

async function onDelete() {
  if (!expense.value || busy.value) return
  const title = expense.value.title
  const ok = await confirmAction({
    title: `删除“${title}”？`,
    message: '删除后所有人都看不到这笔支出，它也不再算进任何人的账里。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  busy.value = true
  try {
    await deleteExpense(props.tripId, props.expenseId)
    showToast({ message: `已删除“${title}”`, type: 'success' })
    await router.replace({ name: 'trip', params: { tripId: props.tripId } })
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 401) return
    showError(apiError)
    // 404：别人刚删掉了；403：付款人刚好被改成了别人。刷新后页面和实际一致
    await load()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <BoardHeader :title="expense?.title ?? '支出'" :subtitle="trip?.name" back-label="返回行程" narrow @back="goTrip" />

    <main class="expense-detail tm-container tm-container--narrow">
      <div v-if="state === 'loading'" class="status" role="status">
        <van-loading size="20" />
        <span>正在加载…</span>
      </div>

      <div v-else-if="state === 'not-found'" class="status status--box" role="alert">
        <p class="status__text">找不到这笔支出：它可能已经被删除，或者你已经不在这个行程里了。</p>
        <van-button size="small" plain type="primary" @click="goTrip">返回行程</van-button>
      </div>

      <div v-else-if="state === 'error'" class="status status--box" role="alert">
        <p class="status__text">{{ errorMessage }}</p>
        <van-button size="small" plain type="primary" @click="load">重试</van-button>
      </div>

      <template v-else-if="expense && trip">
        <section class="card" aria-label="支出信息">
          <CurrencyAmount :amount-minor="expense.amountMinor" :currency="expense.currency" size="lg" />
          <dl class="facts">
            <div class="facts__row">
              <dt>付款人</dt>
              <dd>{{ nameOf(expense.paidByMemberId) }}</dd>
            </div>
            <div class="facts__row">
              <dt>时间</dt>
              <dd>
                <span class="tm-num">{{ when }}</span>
                <span v-if="dayLabel(expense.dayIndex)" class="facts__extra">{{ dayLabel(expense.dayIndex) }}</span>
                <span v-if="zoneText" class="facts__extra">{{ zoneText }}</span>
              </dd>
            </div>
            <div class="facts__row">
              <dt>分类</dt>
              <dd>{{ categoryLabel(expense.category) }}</dd>
            </div>
            <div class="facts__row">
              <dt>记账人</dt>
              <dd>{{ nameOf(expense.createdByMemberId) }}</dd>
            </div>
          </dl>
          <p v-if="expense.note" class="note">{{ expense.note }}</p>
        </section>

        <section aria-labelledby="splits-title">
          <h2 id="splits-title" class="section-title">
            {{ splitModeLabel(expense.splitMode) }}
            <span v-if="expense.splitMode !== 'none'" class="section-title__count tm-num">{{ splits.length }} 人</span>
          </h2>
          <p v-if="expense.visibility === 'private'" class="hint">个人支出：只有你看得见，不算进任何人的欠款。</p>
          <ul v-else class="list">
            <li v-for="split in splits" :key="split.memberId" class="split">
              <span class="split__name">
                {{ nameOf(split.memberId) }}
                <span v-if="split.memberId === trip.myMemberId" class="tag">我</span>
              </span>
              <CurrencyAmount :amount-minor="split.amountMinor" :currency="expense.currency" size="sm" />
            </li>
          </ul>
        </section>

        <div class="actions">
          <template v-if="canModify">
            <van-button plain type="danger" block :loading="busy" @click="onDelete">删除这笔支出</van-button>
          </template>
          <p v-else class="hint">只有付款人或记账人可以修改、删除这笔支出。</p>
        </div>
      </template>
    </main>
  </div>
</template>

<style lang="less" scoped>
.expense-detail {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-5);
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

.card {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-4);
  padding: var(--tm-space-4);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
}

.facts {
  display: grid;
  gap: var(--tm-space-3);
  margin: 0;
}

.facts__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--tm-space-3);
  min-height: 24px;

  dt {
    flex: none;
    color: var(--tm-muted);
    font-size: var(--tm-text-sm);
  }

  dd {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    min-width: 0;
    margin: 0;
    text-align: right;
    overflow-wrap: anywhere;
  }
}

.facts__extra {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.note {
  margin: 0;
  padding-top: var(--tm-space-3);
  border-top: 1px solid var(--tm-line);
  overflow-wrap: anywhere;
  white-space: pre-line;
}

.section-title {
  margin: 0 0 var(--tm-space-2);
  font-size: var(--tm-text-md);
  font-weight: 600;
}

.section-title__count {
  margin-left: var(--tm-space-1);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.list {
  margin: 0;
  padding: 0;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  list-style: none;
}

.split {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  min-height: var(--tm-tap-min);
  padding: var(--tm-space-2) var(--tm-space-4);

  & + & {
    border-top: 1px solid var(--tm-line);
  }
}

.split__name {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  min-width: 0;
  overflow-wrap: anywhere;
}

.tag {
  flex: none;
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-tag);
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  line-height: 20px;
}

.hint {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.actions {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
}
</style>
