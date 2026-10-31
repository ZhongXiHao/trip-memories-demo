<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import {
  listMembers,
  quitTrip,
  removeMember,
  transferOwner,
  type MemberChange,
  type MemberView,
} from '@/api/members'
import { deleteTrip, getTrip, type TripView } from '@/api/trips'
import AddMemberPopup from '@/components/AddMemberPopup.vue'
import BoardHeader from '@/components/BoardHeader.vue'
import CurrencyTag from '@/components/CurrencyTag.vue'
import MoreMenu from '@/components/MoreMenu.vue'
import TripExpenses from '@/components/TripExpenses.vue'
import TripMembers from '@/components/TripMembers.vue'
import { formatDateRange } from '@/utils/date'
import { confirmAction } from '@/utils/dialog'
import { roleLabel, splitMembers } from '@/utils/members'
import { tripMenuItems, type TripMenuKey } from '@/utils/menus'
import { timezoneLabel, timezoneShortLabel } from '@/utils/timezones'
import { showError } from '@/utils/toast'

// 行程详情：页头是行程摘要（日期、展示币别、时区，负责人有标签），下面是支出、欠款（占位）和成员。
// 编辑、删除、退出行程都在页头的"⋯"菜单里（危险操作不常驻页面，见 DESIGN.md「按钮」）。
const props = defineProps<{ tripId: number }>()

const router = useRouter()

type State = 'loading' | 'ready' | 'not-found' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const trip = ref<TripView | null>(null)
const members = ref<MemberView[]>([])
const busy = ref(false)
const showAdd = ref(false)

const groups = computed(() => splitMembers(members.value))
const isOwner = computed(() => trip.value?.myRole === 'owner')
const menuItems = computed(() => (trip.value ? tripMenuItems(trip.value.myRole) : []))

/** silent：操作之后刷新数据时不闪"正在加载"，页面保持原样直到新数据到达 */
async function load(silent = false) {
  if (!silent) state.value = 'loading'
  try {
    // 行程和成员一起取；不是在籍成员时两个都是 404，按"找不到"处理
    const [loadedTrip, loadedMembers] = await Promise.all([getTrip(props.tripId), listMembers(props.tripId)])
    trip.value = loadedTrip
    members.value = loadedMembers
    state.value = 'ready'
  } catch (error) {
    const apiError = toApiError(error)
    // 401 已经由 http.ts 的拦截器处理（清身份并跳去选择页）
    if (apiError.status === 401) return
    if (apiError.status === 404) {
      state.value = 'not-found'
    } else if (!silent) {
      errorMessage.value = describeError(apiError)
      state.value = 'error'
    } else {
      showError(apiError)
    }
  }
}

onMounted(() => load())
// 同一个页面组件里从一个行程切到另一个（地址栏改 id）时重新加载
watch(() => props.tripId, () => load())

function goList() {
  router.push({ name: 'home' })
}

function goNewExpense() {
  router.push({ name: 'expense-new', params: { tripId: props.tripId } })
}

function goEdit() {
  router.push({ name: 'trip-edit', params: { tripId: props.tripId } })
}

/** 退出、移出时后端可能带提醒（比如还有没结清的欠款）。只提醒，不影响操作结果。 */
function showWarnings(change: MemberChange) {
  const first = change.warnings[0]
  if (first) showToast({ message: first.message, duration: 4000 })
}

/**
 * 执行一个会改数据的操作：期间禁用按钮；失败统一提示，并刷新一遍
 * （最常见的失败是别人刚好改了成员关系，刷新后页面就和实际一致了）。
 */
async function runAction(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try {
    await action()
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 401) return
    showError(apiError)
    await load(true)
  } finally {
    busy.value = false
  }
}

async function onTransfer(member: MemberView) {
  const ok = await confirmAction({
    title: `把负责人转让给 ${member.nickname}？`,
    message: '转让后你会变成普通成员，不能再编辑、删除这个行程，也不能管理成员。',
    confirmText: '转让',
  })
  if (!ok) return
  await runAction(async () => {
    await transferOwner(props.tripId, member.memberId)
    showToast({ message: `负责人已转让给 ${member.nickname}`, type: 'success' })
    await load(true)
  })
}

async function onRemove(member: MemberView) {
  const ok = await confirmAction({
    title: `把 ${member.nickname} 移出行程？`,
    message: '他将看不到这个行程。他记过的账目会保留，之后你也可以再把他添加回来。',
    confirmText: '移出',
    danger: true,
  })
  if (!ok) return
  await runAction(async () => {
    const change = await removeMember(props.tripId, member.memberId)
    showToast({ message: `已移出 ${member.nickname}`, type: 'success' })
    showWarnings(change)
    await load(true)
  })
}

async function onDelete() {
  if (!trip.value) return
  const name = trip.value.name
  const ok = await confirmAction({
    title: `删除“${name}”？`,
    message: '所有成员都将看不到这个行程。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  await runAction(async () => {
    await deleteTrip(props.tripId)
    showToast({ message: `已删除“${name}”`, type: 'success' })
    await router.replace({ name: 'home' })
  })
}

function onMenu(key: TripMenuKey) {
  if (key === 'edit') goEdit()
  if (key === 'delete') void onDelete()
  if (key === 'quit') void onQuit()
}

async function onQuit() {
  const ok = await confirmAction({
    title: '退出这个行程？',
    message: '退出后你将看不到它。你记过的账目会保留，负责人之后可以再把你添加回来。',
    confirmText: '退出',
    danger: true,
  })
  if (!ok) return
  await runAction(async () => {
    const change = await quitTrip(props.tripId)
    showToast({ message: '已退出行程', type: 'success' })
    showWarnings(change)
    await router.replace({ name: 'home' })
  })
}
</script>

<template>
  <div>
    <BoardHeader :title="trip?.name ?? '行程'" back-label="我的行程" @back="goList">
      <template v-if="trip" #meta>
        <p class="summary">
          <span>{{ formatDateRange(trip.startDate, trip.endDate) }}</span>
          <span class="summary__sep" aria-hidden="true">·</span>
          <CurrencyTag :code="trip.displayCurrency" />
          <span class="summary__sep" aria-hidden="true">·</span>
          <span class="summary__zone" :title="timezoneLabel(trip.timezone)" :aria-label="`时区 ${timezoneLabel(trip.timezone)}`">
            <van-icon name="clock-o" aria-hidden="true" />{{ timezoneShortLabel(trip.timezone) }}
          </span>
          <span v-if="isOwner" class="summary__role">{{ roleLabel(trip.myRole) }}</span>
        </p>
        <p v-if="trip.description" class="summary__description">{{ trip.description }}</p>
      </template>
      <template #action>
        <MoreMenu :items="menuItems" label="行程操作" :title="trip?.name" :disabled="busy" @select="onMenu" />
      </template>
    </BoardHeader>

    <main class="detail tm-container">
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
        <van-button size="small" plain type="primary" class="tm-button-secondary" @click="load()">重试</van-button>
      </div>

      <div v-else-if="trip" class="main">
        <TripExpenses :trip-id="tripId" :members="members" :display-currency="trip.displayCurrency">
          <template #action>
            <van-button type="primary" size="small" icon="plus" class="add-expense" :disabled="busy" @click="goNewExpense">
              记一笔
            </van-button>
          </template>
        </TripExpenses>
        <p class="soon">欠款和还款马上就做。</p>
        <div class="members">
          <TripMembers
            :active="groups.active"
            :left="groups.left"
            :my-member-id="trip.myMemberId"
            :is-owner="isOwner"
            :busy="busy"
            @add="showAdd = true"
            @transfer="onTransfer"
            @remove="onRemove"
          />
          <p v-if="isOwner" class="members__note">你是负责人。想退出，要先把负责人转让给别的成员。</p>
        </div>
      </div>
    </main>

    <AddMemberPopup v-model:show="showAdd" :trip-id="tripId" @added="load(true)" />
  </div>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

.detail {
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

.main {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-5);
  // 列宽不跟着内容撑开：长的支出名称在行内省略，不会把页面撑出横向滚动
  min-width: 0;
}

.members {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
}

.members__note {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

// 页头摘要：日期 · 币别 · 时区（· 负责人标签），描述最多两行
.summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tm-space-1) var(--tm-space-2);
  margin: 0;
}

.summary__sep {
  opacity: 0.7;
}

.summary__zone {
  display: inline-flex;
  align-items: center;
  gap: var(--tm-space-1);
}

// 深色页头上的描边标签；PC 上页头是浅色，换成深色描边
.summary__role {
  padding: 0 var(--tm-space-2);
  border: 1px solid currentcolor;
  border-radius: var(--tm-radius-tag);
  font-size: var(--tm-text-xs);
  font-weight: 600;
  line-height: 20px;
}

.summary__description {
  display: -webkit-box;
  margin: var(--tm-space-2) 0 0;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow-wrap: anywhere;
}

.add-expense {
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-4);
}

.soon {
  margin: 0;
  padding: var(--tm-space-4);
  border: 1px dashed var(--tm-line);
  border-radius: var(--tm-radius-card);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}
</style>
