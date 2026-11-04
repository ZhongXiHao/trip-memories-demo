<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import type { MemberView } from '@/api/members'
import {
  cancelRepayment,
  cancelRepaymentGroup,
  confirmRepayment,
  confirmRepaymentGroup,
  listRepayments,
  type RepaymentListQuery,
  type RepaymentView,
} from '@/api/repayments'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { confirmAction } from '@/utils/dialog'
import { isMemberLeft, memberName } from '@/utils/expenses'
import {
  applyRepaymentUpdates,
  canCancelRepayment,
  canConfirmRepayment,
  groupActions,
  groupRepayments,
  repaymentStatusLabel,
  type RepaymentGroupRow,
} from '@/utils/repayments'
import { shortLocalDate, toLocalDateTime } from '@/utils/zonedTime'
import { showError } from '@/utils/toast'

// 行程里的还款记录（在籍成员都看得见全部）。可以按"待确认 / 已确认"筛选。
// 收款方能确认；付款方、收款方都能取消待确认的。一次转账结清了几个币别时显示在一起，可以整组确认或取消。
// 按钮只给有权限的人显示。确认、取消之后用响应里的状态更新这一行（刚取消的显示"已取消"，下次刷新时才消失）。
const props = defineProps<{
  tripId: number
  members: MemberView[]
  myMemberId: number
  /** 行程时区：记录时间按它显示 */
  timezone: string
}>()

const emit = defineEmits<{
  /** 确认或取消了还款：欠款要刷新 */
  changed: []
}>()

type Filter = 'pending' | 'confirmed' | 'all'
const filter = ref<Filter>('pending')
const filterOptions: ReadonlyArray<{ value: Filter; label: string }> = [
  { value: 'pending', label: '待确认' },
  { value: 'confirmed', label: '已确认' },
  { value: 'all', label: '全部' },
]

type State = 'loading' | 'ready' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const repayments = ref<RepaymentView[]>([])
const busy = ref(false)

const groups = computed(() => groupRepayments(repayments.value))

let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  state.value = 'loading'
  const query: RepaymentListQuery = filter.value === 'all' ? {} : { status: filter.value }
  try {
    const items = await listRepayments(props.tripId, query)
    if (seq !== loadSeq) return
    repayments.value = items
    state.value = 'ready'
  } catch (error) {
    if (seq !== loadSeq) return
    const apiError = toApiError(error)
    if (apiError.status === 401) return
    errorMessage.value = describeError(apiError)
    state.value = 'error'
  }
}

onMounted(load)
watch([() => props.tripId, filter], load)

function personName(memberId: number): string {
  if (memberId === props.myMemberId) return '你'
  const name = memberName(props.members, memberId)
  return isMemberLeft(props.members, memberId) ? `${name}（已退出）` : name
}

function pairText(repayment: RepaymentView): string {
  return `${personName(repayment.fromMemberId)} 还给 ${personName(repayment.toMemberId)}`
}

function timeText(repayment: RepaymentView): string {
  const { date, time } = toLocalDateTime(repayment.createdAt, props.timezone)
  return `${shortLocalDate(date)} ${time}`
}

function isSplitGroup(group: RepaymentGroupRow): boolean {
  return group.items.length > 1
}

/** 执行确认或取消：期间禁用按钮；成功后更新这些行并让欠款刷新；失败时提示并重新加载，页面就和实际一致了 */
async function run(action: () => Promise<RepaymentView[]>, success: string) {
  if (busy.value) return
  busy.value = true
  try {
    const updated = await action()
    repayments.value = applyRepaymentUpdates(repayments.value, updated)
    showToast({ message: success, type: 'success' })
    emit('changed')
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 401) return
    showError(apiError)
    // 已经确认了（取消不了）、已被取消（404）、没有权限：都说明列表过时了
    await load()
    emit('changed')
  } finally {
    busy.value = false
  }
}

async function onConfirm(repayment: RepaymentView) {
  const ok = await confirmAction({
    title: `确认收到 ${personName(repayment.fromMemberId)} 的还款？`,
    message: '确认后会抵销欠款。已确认的还款不能再取消或修改。',
    confirmText: '确认收到',
  })
  if (!ok) return
  await run(async () => [await confirmRepayment(props.tripId, repayment.id)], '已确认收到')
}

async function onConfirmGroup(group: RepaymentGroupRow) {
  const first = group.items[0]
  if (!first) return
  const ok = await confirmAction({
    title: `确认收到 ${personName(first.fromMemberId)} 这次转账？`,
    message: `会确认这次转账里所有待确认的还款（${group.items.length} 个币别）。已确认的还款不能再取消或修改。`,
    confirmText: '全部确认',
  })
  if (!ok) return
  await run(() => confirmRepaymentGroup(props.tripId, group.settlementGroupId), '已确认收到')
}

async function onCancel(repayment: RepaymentView) {
  const ok = await confirmAction({
    title: '取消这笔还款？',
    message: '取消后它不再算作已还，欠款恢复原样。要重新记，在欠款里再记一笔。',
    confirmText: '取消还款',
    danger: true,
  })
  if (!ok) return
  await run(async () => [await cancelRepayment(props.tripId, repayment.id)], '已取消')
}

async function onCancelGroup(group: RepaymentGroupRow) {
  const ok = await confirmAction({
    title: '取消这次转账？',
    message: '会取消这次转账里所有待确认的还款，已确认的保留。',
    confirmText: '全部取消',
    danger: true,
  })
  if (!ok) return
  await run(() => cancelRepaymentGroup(props.tripId, group.settlementGroupId), '已取消')
}

// "⋯"菜单：取消是危险操作，不常驻页面（见 DESIGN.md「按钮」）
type MenuTarget = { kind: 'row'; repayment: RepaymentView } | { kind: 'group'; group: RepaymentGroupRow }
const menuTarget = ref<MenuTarget | null>(null)
const showMenu = ref(false)
const menuActions = computed(() => [
  {
    name: menuTarget.value?.kind === 'group' ? '取消这次转账' : '取消这笔还款',
    color: 'var(--tm-danger)',
  },
])

function openMenu(target: MenuTarget) {
  menuTarget.value = target
  showMenu.value = true
}

function onMenuSelect() {
  const target = menuTarget.value
  showMenu.value = false
  if (!target) return
  if (target.kind === 'row') void onCancel(target.repayment)
  else void onCancelGroup(target.group)
}

const emptyText = computed(() => {
  if (filter.value === 'pending') return '没有待确认的还款。'
  if (filter.value === 'confirmed') return '还没有已确认的还款。'
  return '还没有还款记录。'
})

defineExpose({ reload: load })
</script>

<template>
  <section aria-labelledby="repayments-title">
    <div class="head">
      <h2 id="repayments-title" class="head__title">
        还款 <span v-if="state === 'ready'" class="head__count tm-num">{{ repayments.length }}</span>
      </h2>
    </div>

    <div class="segmented" role="radiogroup" aria-label="按状态筛选还款">
      <button
        v-for="option in filterOptions"
        :key="option.value"
        type="button"
        role="radio"
        class="segmented__item"
        :class="{ 'segmented__item--active': filter === option.value }"
        :aria-checked="filter === option.value"
        @click="filter = option.value"
      >
        {{ option.label }}
      </button>
    </div>

    <div v-if="state === 'loading'" class="status" role="status">
      <van-loading size="20" />
      <span>正在加载还款…</span>
    </div>

    <div v-else-if="state === 'error'" class="status status--box" role="alert">
      <p class="status__text">还款没加载出来：{{ errorMessage }}</p>
      <van-button size="small" plain type="primary" class="tm-button-secondary" @click="load">重试</van-button>
    </div>

    <p v-else-if="groups.length === 0" class="empty">{{ emptyText }}</p>

    <div v-else class="groups">
      <section
        v-for="group in groups"
        :key="group.settlementGroupId"
        class="group"
        :aria-label="isSplitGroup(group) ? `一次转账，${group.items.length} 个币别` : undefined"
      >
        <div v-if="isSplitGroup(group)" class="group__head">
          <span class="group__title">一次转账 · {{ group.items.length }} 个币别</span>
          <div class="actions">
            <van-button
              v-if="groupActions(group, myMemberId).confirm"
              size="small"
              plain
              type="primary"
              class="tm-button-secondary"
              :disabled="busy"
              @click="onConfirmGroup(group)"
            >
              全部确认
            </van-button>
            <button
              v-if="groupActions(group, myMemberId).cancel"
              type="button"
              class="more"
              aria-label="这次转账的更多操作"
              :disabled="busy"
              @click="openMenu({ kind: 'group', group })"
            >
              <van-icon name="ellipsis" size="20" aria-hidden="true" />
            </button>
          </div>
        </div>

        <ul class="list">
          <li
            v-for="repayment in group.items"
            :key="repayment.id"
            class="repayment"
            :class="{ 'repayment--cancelled': repayment.status === 'cancelled' }"
          >
            <div class="repayment__main">
              <div class="repayment__text">
                <span class="repayment__pair">
                  <span class="tag" :class="`tag--${repayment.status}`">{{ repaymentStatusLabel(repayment.status) }}</span>
                  <span class="repayment__names">{{ pairText(repayment) }}</span>
                </span>
                <span class="repayment__meta">
                  <span class="tm-num">{{ timeText(repayment) }}</span>
                  <template v-if="repayment.note"> · {{ repayment.note }}</template>
                </span>
                <span v-if="repayment.paidCurrency !== repayment.clearedCurrency" class="repayment__paid">
                  实付
                  <CurrencyAmount :amount-minor="repayment.paidAmountMinor" :currency="repayment.paidCurrency" size="sm" />
                </span>
              </div>
              <CurrencyAmount :amount-minor="repayment.clearedAmountMinor" :currency="repayment.clearedCurrency" />
            </div>

            <div
              v-if="canConfirmRepayment(repayment, myMemberId) || canCancelRepayment(repayment, myMemberId)"
              class="actions actions--row"
            >
              <van-button
                v-if="canConfirmRepayment(repayment, myMemberId)"
                size="small"
                plain
                type="primary"
                class="tm-button-secondary"
                :disabled="busy"
                @click="onConfirm(repayment)"
              >
                确认收到
              </van-button>
              <button
                v-if="canCancelRepayment(repayment, myMemberId)"
                type="button"
                class="more"
                :aria-label="`${pairText(repayment)}的更多操作`"
                :disabled="busy"
                @click="openMenu({ kind: 'row', repayment })"
              >
                <van-icon name="ellipsis" size="20" aria-hidden="true" />
              </button>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <van-action-sheet
      v-model:show="showMenu"
      :actions="menuActions"
      cancel-text="返回"
      close-on-click-action
      teleport="body"
      @select="onMenuSelect"
    />
  </section>
</template>

<style lang="less" scoped>
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

.segmented {
  display: flex;
  gap: var(--tm-space-2);
  margin-bottom: var(--tm-space-3);
}

.segmented__item {
  flex: 1;
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

// 选中：加粗 + 双倍描边，不用实心主色（和欠款的视图切换一样）
.segmented__item--active {
  border-color: var(--tm-navy);
  box-shadow: inset 0 0 0 1px var(--tm-navy);
  font-weight: 600;
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

.groups {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
}

.group__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-2);
  min-height: var(--tm-tap-min);
  margin-bottom: var(--tm-space-1);
}

.group__title {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  font-weight: 600;
}

.list {
  margin: 0;
  padding: 0;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  list-style: none;
}

.repayment {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-2);
  padding: var(--tm-space-3) var(--tm-space-4);

  & + & {
    border-top: 1px solid var(--tm-line);
  }
}

.repayment__main {
  display: flex;
  align-items: flex-start;
  gap: var(--tm-space-3);
}

.repayment__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.repayment__pair {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  min-width: 0;
}

.repayment__names {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repayment__meta {
  overflow: hidden;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repayment__paid {
  display: inline-flex;
  align-items: center;
  gap: var(--tm-space-2);
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

// 已取消：文字用次要色 + "已取消"标签，不靠颜色单独表达
.repayment--cancelled .repayment__names {
  color: var(--tm-muted);
  font-weight: 500;
  text-decoration: line-through;
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
}

.actions--row {
  justify-content: flex-end;
}

.more {
  display: grid;
  flex: none;
  place-items: center;
  width: var(--tm-tap-min);
  height: var(--tm-tap-min);
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--tm-muted);
  cursor: pointer;

  &:hover:not(:disabled) {
    background: var(--tm-board);
    color: var(--tm-navy);
  }

  &:disabled {
    cursor: default;
    opacity: 0.5;
  }
}

// 状态标签：已确认实心、待确认描边、已取消虚线，靠形状和文字区分
.tag {
  flex: none;
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-navy);
  border-radius: var(--tm-radius-tag);
  color: var(--tm-navy);
  font-size: var(--tm-text-xs);
  font-weight: 600;
  line-height: 18px;
}

.tag--confirmed {
  background: var(--tm-navy);
  color: var(--tm-surface);
}

.tag--cancelled {
  border-style: dashed;
  border-color: var(--tm-muted);
  color: var(--tm-muted);
}
</style>
