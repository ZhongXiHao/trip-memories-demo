<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { describeError, toApiError } from '@/api/errors'
import { listMembers, type MemberView } from '@/api/members'
import { getTrip, type TripView } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import CurrencyTag from '@/components/CurrencyTag.vue'
import { formatDateRange } from '@/utils/date'
import { roleLabel, splitMembers } from '@/utils/members'
import { timezoneLabel } from '@/utils/timezones'

// 行程详情：行程信息 + 成员。支出、欠款和还款等后端接口做好后再加。
const props = defineProps<{ tripId: number }>()

const router = useRouter()

type State = 'loading' | 'ready' | 'not-found' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const trip = ref<TripView | null>(null)
const members = ref<MemberView[]>([])

const groups = computed(() => splitMembers(members.value))

async function load() {
  state.value = 'loading'
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
    } else {
      errorMessage.value = describeError(apiError)
      state.value = 'error'
    }
  }
}

onMounted(load)
// 同一个页面组件里从一个行程切到另一个（地址栏改 id）时重新加载
watch(() => props.tripId, load)

function goList() {
  router.push({ name: 'home' })
}
</script>

<template>
  <div>
    <BoardHeader
      :title="trip?.name ?? '行程'"
      :subtitle="trip ? formatDateRange(trip.startDate, trip.endDate) : undefined"
      back-label="我的行程"
      @back="goList"
    />

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
        <van-button size="small" plain type="primary" @click="load">重试</van-button>
      </div>

      <div v-else-if="trip" class="grid">
        <section class="card info" aria-label="行程信息">
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

        <section class="members" aria-labelledby="members-title">
          <h2 id="members-title" class="section-title">
            成员 <span class="section-title__count tm-num">{{ groups.active.length }}</span>
          </h2>

          <ul class="member-list">
            <li v-for="member in groups.active" :key="member.memberId" class="member">
              <div class="member__text">
                <span class="member__name">{{ member.nickname }}</span>
                <span class="member__id tm-num">ID {{ member.userId }}</span>
              </div>
              <span v-if="member.memberId === trip.myMemberId" class="tag">我</span>
              <span v-if="member.role === 'owner'" class="tag tag--strong">{{ roleLabel(member.role) }}</span>
            </li>
          </ul>

          <details v-if="groups.left.length" class="left">
            <summary class="left__summary">已退出的成员（{{ groups.left.length }}）</summary>
            <ul class="member-list member-list--left">
              <li v-for="member in groups.left" :key="member.memberId" class="member member--left">
                <div class="member__text">
                  <span class="member__name">{{ member.nickname }}</span>
                  <span class="member__id tm-num">ID {{ member.userId }}</span>
                </div>
                <span class="tag">已退出</span>
              </li>
            </ul>
          </details>
        </section>

        <p class="soon">支出、欠款和还款马上就做。</p>
      </div>
    </main>
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
  gap: var(--tm-space-5);
}

@media (min-width: @bp-desktop) {
  .grid {
    grid-template-columns: 320px minmax(0, 1fr);
    align-items: start;
  }

  .info {
    position: sticky;
    top: calc(var(--tm-topbar-h) + var(--tm-space-4));
  }

  .soon {
    grid-column: 2;
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

.member-list {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  list-style: none;
}

.member {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  min-height: 56px;
  padding: var(--tm-space-2) var(--tm-space-4);

  & + & {
    border-top: 1px solid var(--tm-line);
  }
}

.member__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.member__name {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member__id {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
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

.tag--strong {
  border-color: var(--tm-navy);
  color: var(--tm-navy);
  font-weight: 600;
}

.left {
  margin-top: var(--tm-space-3);
}

.left__summary {
  display: flex;
  align-items: center;
  min-height: var(--tm-tap-min);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
  cursor: pointer;
}

// 已退出的成员：文字用次要色（对比度仍达标）+ "已退出"标签，不靠颜色单独表达
.member--left .member__name {
  color: var(--tm-muted);
  font-weight: 500;
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
