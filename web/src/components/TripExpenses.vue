<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { describeError, toApiError } from '@/api/errors'
import { listExpenses, type ExpenseView } from '@/api/expenses'
import type { MemberView } from '@/api/members'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { categoryLabel, dayLabel, groupByLocalDate, memberName, totalsByCurrency } from '@/utils/expenses'
import { shortLocalDate, toLocalDateTime, weekdayOf } from '@/utils/zonedTime'

// 行程页里的支出列表：按当地日期分组（每笔按自己的 occurredTz），后端已按时间倒序。
// 每组的标题滚动时吸顶，右边是当天按币别分开的合计（不换算；展示币别排第一，其余按代码字母序）。
// 列表自己取数据、自己处理加载失败，不拖累页面上的其它部分。
const props = defineProps<{
  tripId: number
  /** 成员列表（包含已退出的），用来把 memberId 显示成昵称 */
  members: MemberView[]
  /** 行程的展示币别：当天合计里排第一 */
  displayCurrency?: string
}>()

type State = 'loading' | 'ready' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const expenses = ref<ExpenseView[]>([])

const groups = computed(() =>
  groupByLocalDate(expenses.value).map((group) => ({ ...group, totals: totalsByCurrency(group.items, props.displayCurrency) })),
)

async function load() {
  state.value = 'loading'
  try {
    expenses.value = await listExpenses(props.tripId)
    state.value = 'ready'
  } catch (error) {
    const apiError = toApiError(error)
    // 401 由拦截器处理；404（已不在行程里）时页面本身也会显示"找不到"
    if (apiError.status === 401) return
    errorMessage.value = describeError(apiError)
    state.value = 'error'
  }
}

onMounted(load)
watch(() => props.tripId, load)

function payerText(expense: ExpenseView): string {
  return `${memberName(props.members, expense.paidByMemberId)} 付`
}

function timeText(expense: ExpenseView): string {
  return toLocalDateTime(expense.occurredAt, expense.occurredTz).time
}

function groupTitle(date: string, dayIndex: number | null): string {
  const day = dayLabel(dayIndex)
  const when = `${shortLocalDate(date)} ${weekdayOf(date)}`
  return day ? `${day} · ${when}` : when
}

defineExpose({ reload: load })
</script>

<template>
  <section aria-labelledby="expenses-title">
    <div class="head">
      <h2 id="expenses-title" class="head__title">
        支出 <span v-if="state === 'ready'" class="head__count tm-num">{{ expenses.length }}</span>
      </h2>
      <slot name="action" />
    </div>

    <div v-if="state === 'loading'" class="status" role="status">
      <van-loading size="20" />
      <span>正在加载支出…</span>
    </div>

    <div v-else-if="state === 'error'" class="status status--box" role="alert">
      <p class="status__text">支出没加载出来：{{ errorMessage }}</p>
      <van-button size="small" plain type="primary" @click="load">重试</van-button>
    </div>

    <p v-else-if="expenses.length === 0" class="empty">还没有支出。</p>

    <div v-else class="days">
      <section v-for="group in groups" :key="group.date" class="day" :aria-label="groupTitle(group.date, group.dayIndex)">
        <div class="day__head">
          <h3 class="day__title">{{ groupTitle(group.date, group.dayIndex) }}</h3>
          <p class="day__totals" aria-label="当天合计">
            <template v-for="(total, index) in group.totals" :key="total.currency">
              <span v-if="index > 0" class="day__sep" aria-hidden="true">·</span>
              <CurrencyAmount :amount-minor="total.amountMinor" :currency="total.currency" size="sm" />
            </template>
          </p>
        </div>
        <ul class="list">
          <li v-for="expense in group.items" :key="expense.id">
            <router-link
              class="expense"
              :to="{ name: 'expense', params: { tripId, expenseId: expense.id } }"
            >
              <div class="expense__text">
                <span class="expense__title">{{ expense.title }}</span>
                <span class="expense__meta">
                  {{ payerText(expense) }} · {{ categoryLabel(expense.category) }} ·
                  <span class="tm-num">{{ timeText(expense) }}</span>
                </span>
              </div>
              <div class="expense__side">
                <CurrencyAmount :amount-minor="expense.amountMinor" :currency="expense.currency" />
                <span v-if="expense.visibility === 'private'" class="tag">个人</span>
              </div>
            </router-link>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  min-height: var(--tm-tap-min);
  margin-bottom: var(--tm-space-2);
}

.head__title {
  margin: 0;
  font-size: var(--tm-text-md);
  font-weight: 600;
}

.head__count {
  margin-left: var(--tm-space-1);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
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

.empty {
  margin: 0;
  padding: var(--tm-space-4);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.days {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-4);
}

// 日期标题吸顶：手机上页头会滚走，贴在视口顶部；PC 上顶部导航条是 sticky 的，贴在它下面。
// 只在本组范围内吸住（sticky 的范围是父元素 .day），滚到下一组时被下一组的标题顶上去。
.day__head {
  position: sticky;
  top: 0;
  // 低于顶部导航条（AppTopBar 是 10），高于列表内容
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-1) var(--tm-space-3);
  min-height: 36px;
  margin-bottom: var(--tm-space-2);
  padding-block: var(--tm-space-1);
  // 和页面底色一样的实底，盖住滚到下面的行
  background: var(--tm-board);
}

@media (min-width: @bp-desktop) {
  .day__head {
    top: var(--tm-topbar-h);
  }
}

.day__title {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  font-weight: 600;
}

.day__totals {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--tm-space-1) var(--tm-space-2);
  margin: 0 0 0 auto;
}

.day__sep {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.list {
  margin: 0;
  padding: 0;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  list-style: none;

  li + li {
    border-top: 1px solid var(--tm-line);
  }
}

.expense {
  display: flex;
  align-items: center;
  gap: var(--tm-space-3);
  min-height: 56px;
  padding: var(--tm-space-2) var(--tm-space-4);
  color: inherit;
  text-decoration: none;

  &:hover {
    background: var(--tm-board);
  }
}

.expense__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.expense__title {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expense__meta {
  overflow: hidden;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expense__side {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.tag {
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-tag);
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  line-height: 18px;
}
</style>
