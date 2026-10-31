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
import TripExpenses from '@/components/TripExpenses.vue'
import TripMembers from '@/components/TripMembers.vue'
import { formatDateRange } from '@/utils/date'
import { confirmAction } from '@/utils/dialog'
import { roleLabel, splitMembers } from '@/utils/members'
import { timezoneLabel } from '@/utils/timezones'
import { showError } from '@/utils/toast'

// 行程详情：行程信息、支出列表、成员管理。欠款和还款还是占位。
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
    <BoardHeader
      :title="trip?.name ?? '行程'"
      :subtitle="trip ? formatDateRange(trip.startDate, trip.endDate) : undefined"
      back-label="我的行程"
      @back="goList"
    >
      <template #action>
        <van-button v-if="isOwner" size="small" plain class="edit" :disabled="busy" @click="goEdit">编辑</van-button>
      </template>
    </BoardHeader>

    <main class="detail tm-container">
      <div v-if="state === 'loading'" class="status" role="status">
        <van-loading size="20" />
        <span>正在加载…</span>
      </div>

      <div v-else-if="state === 'not-found'" class="status status--box" role="alert">
        <p class="status__text">找不到这个行程，或者你已经不在这个行程里了。</p>
        <van-button size="small" plain type="primary" @click="goList">回到我的行程</van-button>
      </div>

      <div v-else-if="state === 'error'" class="status status--box" role="alert">
        <p class="status__text">{{ errorMessage }}</p>
        <van-button size="small" plain type="primary" @click="load()">重试</van-button>
      </div>

      <div v-else-if="trip" class="grid">
        <div class="side">
          <section class="card" aria-label="行程信息">
            <p v-if="trip.description" class="info__description">{{ trip.description }}</p>
            <dl class="facts">
              <div class="facts__row">
                <dt>时区</dt>
                <dd>{{ timezoneLabel(trip.timezone) }}</dd>
              </div>
              <div class="facts__row">
                <dt>展示币别</dt>
                <dd><CurrencyTag :code="trip.displayCurrency" /></dd>
              </div>
              <div class="facts__row">
                <dt>我的身份</dt>
                <dd>{{ roleLabel(trip.myRole) }}</dd>
              </div>
            </dl>
          </section>

          <div class="leave">
            <template v-if="isOwner">
              <p class="leave__note">你是负责人。想退出，要先把负责人转让给别的成员。</p>
              <van-button plain type="danger" block :disabled="busy" @click="onDelete">删除行程</van-button>
            </template>
            <van-button v-else plain type="danger" block :disabled="busy" @click="onQuit">退出行程</van-button>
          </div>
        </div>

        <div class="main">
          <TripExpenses :trip-id="tripId" :members="members" />
          <p class="soon">欠款和还款马上就做。</p>
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

// 手机：信息、成员上下排；PC：左边信息，右边成员
.grid {
  display: grid;
  // minmax(0, …)：列宽不跟着内容撑开，长的支出名称在行内省略，不会把页面撑出横向滚动
  grid-template-columns: minmax(0, 1fr);
  gap: var(--tm-space-5);
}

.side,
.main {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-5);
}

@media (min-width: @bp-desktop) {
  .grid {
    grid-template-columns: 320px minmax(0, 1fr);
    align-items: start;
  }

  .side {
    position: sticky;
    top: calc(var(--tm-topbar-h) + var(--tm-space-4));
  }
}

// 手机上"退出行程"放在最下面，不要紧挨着成员列表
@media (max-width: (@bp-desktop - 1px)) {
  .side {
    display: contents;
  }

  .leave {
    order: 3;
  }
}

.card {
  padding: var(--tm-space-4);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
}

.info__description {
  margin: 0 0 var(--tm-space-3);
  overflow-wrap: anywhere;
  white-space: pre-line;
}

.facts {
  display: grid;
  gap: var(--tm-space-3);
  margin: 0;
}

.facts__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  min-height: 24px;

  dt {
    color: var(--tm-muted);
    font-size: var(--tm-text-sm);
  }

  dd {
    margin: 0;
    text-align: right;
  }
}

.leave {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
}

.leave__note {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

// 页头里的"编辑"：手机上页头是深色（白字白边）；PC 上页头变成普通标题区，改成主色描边
.edit {
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-4);
  border-color: rgb(255 255 255 / 60%);
  background: transparent;
  color: #fff;
}

@media (min-width: @bp-desktop) {
  .edit {
    border-color: var(--tm-primary);
    color: var(--tm-primary);
  }
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
