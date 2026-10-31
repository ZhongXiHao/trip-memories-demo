<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
import { TRIP_TABS, queryWithTab, tabFromQuery, type TripTab } from '@/utils/tripTabs'
import { showError } from '@/utils/toast'

// 行程详情：页头是行程摘要（日期、展示币别、时区，负责人有标签），下面是支出、欠款（占位）和成员。
// 编辑、删除、退出行程都在页头的"⋯"菜单里（危险操作不常驻页面，见 DESIGN.md「按钮」）。
// 主体分成三个标签：支出 / 欠款 / 成员。当前标签写在地址的 ?tab= 里，刷新、从别的页面返回后还在原来的标签。
const props = defineProps<{ tripId: number }>()

const router = useRouter()
const route = useRoute()

const activeTab = computed(() => tabFromQuery(route.query.tab))
const tabsAnchor = ref<HTMLElement>()

/** 切换标签：用 replace 改地址（不在浏览历史里堆一串标签切换），已经滚过标签条时回到新标签的开头 */
function setTab(tab: TripTab) {
  if (tab === activeTab.value) return
  void router.replace({ query: queryWithTab(route.query, tab) })
  const anchor = tabsAnchor.value
  if (!anchor) return
  const top = anchor.getBoundingClientRect().top + window.scrollY
  if (window.scrollY > top) window.scrollTo({ top })
}

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

    <main class="detail">
      <div v-if="state !== 'ready' || !trip" class="tm-container detail__state">
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
      </div>

      <template v-else>
        <!-- 标签条吸顶前所在的位置：切换标签时如果已经滚过它，就回到这里，让新标签从开头显示 -->
        <div ref="tabsAnchor" aria-hidden="true" />
        <van-tabs :active="activeTab" swipeable class="tabs" @update:active="setTab">
          <van-tab v-for="tab in TRIP_TABS" :key="tab.name" :name="tab.name" :title="tab.title">
            <div class="tm-container panel" :class="{ 'panel--with-bar': tab.name === 'expenses' }">
              <TripExpenses
                v-if="tab.name === 'expenses'"
                :trip-id="tripId"
                :members="members"
                :display-currency="trip.displayCurrency"
              />

              <p v-else-if="tab.name === 'debts'" class="soon">欠款和还款马上就做。</p>

              <div v-else class="members">
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
          </van-tab>
        </van-tabs>

        <!-- 这一屏的主按钮：固定在底部，只在支出标签显示 -->
        <div v-if="activeTab === 'expenses'" class="action-bar">
          <van-button type="primary" block icon="plus" :disabled="busy" @click="goNewExpense">记一笔</van-button>
        </div>
      </template>
    </main>

    <AddMemberPopup v-model:show="showAdd" :trip-id="tripId" @added="load(true)" />
  </div>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

.detail__state {
  padding-block: var(--tm-space-4);
}

// 标签条吸顶：手机上页头会滚走，贴在视口顶部。用 CSS sticky（不用 Vant 的 sticky 属性，不需要监听滚动）。
// 支出列表的日期标题也吸顶，要贴在标签条下面：通过 --expense-sticky-top 告诉 TripExpenses 让出标签条的高度。
.tabs {
  --expense-sticky-top: var(--tm-tabs-h);

  :deep(.van-tabs__wrap) {
    position: sticky;
    top: 0;
    // 高于列表里吸顶的日期标题（1），低于 PC 的顶部导航条（10）
    z-index: 2;
    border-bottom: 1px solid var(--tm-line);
  }

  // 左右滑动切换用的容器默认是 overflow: hidden，会让里面的 position: sticky（日期标题）失效。
  // 改成 clip：同样把左右两边的标签内容裁掉，但不形成滚动容器，sticky 仍然相对于页面生效。
  :deep(.van-tabs__content--animated),
  :deep(.van-swipe) {
    overflow: clip;
  }
}

@media (min-width: @bp-desktop) {
  .tabs {
    --expense-sticky-top: calc(var(--tm-topbar-h) + var(--tm-tabs-h));

    :deep(.van-tabs__wrap) {
      top: var(--tm-topbar-h);
    }
  }
}

// 固定在底部的"记一笔"：和手机版式的单栏一样宽（最宽 480px 居中），底部让出 iPhone 的安全区
.action-bar {
  position: fixed;
  bottom: 0;
  left: 50%;
  // 高于吸顶的标签条和日期标题，低于弹层（Vant 弹层从 2000 起）
  z-index: 3;
  width: 100%;
  max-width: var(--tm-app-max-width);
  padding: var(--tm-space-3) var(--tm-space-4) calc(var(--tm-space-3) + env(safe-area-inset-bottom));
  border-top: 1px solid var(--tm-line);
  background: var(--tm-surface);
  transform: translateX(-50%);
}

.panel {
  // 至少一屏高：内容短的标签（欠款、成员）下面的空白处也能左右滑动切换
  min-height: calc(100dvh - var(--tm-tabs-h));
  padding-block: var(--tm-space-4) calc(var(--tm-space-6) + env(safe-area-inset-bottom));
}

// 支出标签：底部留出"记一笔"那一条的高度（按钮 + 上下留白 + 安全区），最后一条不会被挡住
.panel--with-bar {
  padding-bottom: calc(var(--tm-tap-min) + var(--tm-space-3) * 2 + env(safe-area-inset-bottom) + var(--tm-space-4));
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

.soon {
  margin: 0;
  padding: var(--tm-space-4);
  border: 1px dashed var(--tm-line);
  border-radius: var(--tm-radius-card);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}
</style>
