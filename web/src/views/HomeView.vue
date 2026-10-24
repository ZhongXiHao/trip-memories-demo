<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { toApiError, describeError } from '@/api/errors'
import { listTrips, type TripView } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import CurrencyTag from '@/components/CurrencyTag.vue'
import { useSessionStore } from '@/stores/session'
import { formatDateRange } from '@/utils/date'
import { roleLabel } from '@/utils/members'
import { timezoneLabel } from '@/utils/timezones'

// 我的行程：当前用户在籍的行程，后端已按创建时间倒序。
const session = useSessionStore()
const router = useRouter()

type State = 'loading' | 'ready' | 'error'
const state = ref<State>('loading')
const trips = ref<TripView[]>([])
const errorMessage = ref('')

const isEmpty = computed(() => state.value === 'ready' && trips.value.length === 0)

async function load() {
  state.value = 'loading'
  try {
    trips.value = await listTrips()
    state.value = 'ready'
  } catch (error) {
    const apiError = toApiError(error)
    // 401 已经由 http.ts 的拦截器处理（清身份并跳去选择页），不用在这里显示
    if (apiError.status === 401) return
    errorMessage.value = describeError(apiError)
    state.value = 'error'
  }
}

onMounted(() => {
  // 顶部导航条和这里都要用户信息，ensureMe 保证只请求一次；失败不影响列表
  session.ensureMe().catch(() => undefined)
  load()
})

function newTrip() {
  router.push({ name: 'trip-new' })
}
</script>

<template>
  <div>
    <BoardHeader title="我的行程">
      <template #action>
        <van-button size="small" class="new-trip" @click="newTrip">新建行程</van-button>
      </template>
    </BoardHeader>

    <main class="home tm-container">
      <div v-if="state === 'loading'" class="status" role="status">
        <van-loading size="20" />
        <span>正在加载行程…</span>
      </div>

      <div v-else-if="state === 'error'" class="status status--error" role="alert">
        <p class="status__text">{{ errorMessage }}</p>
        <van-button size="small" plain type="primary" @click="load">重试</van-button>
      </div>

      <p v-else-if="isEmpty" class="empty">还没有行程。点右上角的“新建行程”开始，再邀请朋友一起记账。</p>

      <ul v-else class="trips">
        <li v-for="trip in trips" :key="trip.id">
          <RouterLink :to="{ name: 'trip', params: { tripId: trip.id } }" class="trip">
            <h2 class="trip__name">{{ trip.name }}</h2>
            <p v-if="trip.description" class="trip__description">{{ trip.description }}</p>
            <p class="trip__dates tm-num">{{ formatDateRange(trip.startDate, trip.endDate) }}</p>
            <div class="trip__meta">
              <CurrencyTag :code="trip.displayCurrency" />
              <span class="trip__tz">{{ timezoneLabel(trip.timezone) }}</span>
              <span v-if="trip.myRole === 'owner'" class="trip__role">{{ roleLabel(trip.myRole) }}</span>
            </div>
          </RouterLink>
        </li>
      </ul>
    </main>
  </div>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

// 页头里的主操作：手机上页头是深色，用琥珀底 + 深蓝字（对比度 7.4:1）；
// PC 上页头变成普通标题区，琥珀只留给深色底，所以改回主色按钮。
.new-trip {
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-4);
  border-color: var(--tm-amber);
  background: var(--tm-amber);
  color: var(--tm-navy);
  font-weight: 600;
}

@media (min-width: @bp-desktop) {
  .new-trip {
    border-color: var(--tm-primary);
    background: var(--tm-primary);
    color: #fff;
  }
}

.home {
  padding-block: var(--tm-space-4);
}

.status {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  padding: var(--tm-space-4);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.status--error {
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
  border: 1px dashed var(--tm-line);
  border-radius: var(--tm-radius-card);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.trips {
  display: grid;
  gap: var(--tm-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

@media (min-width: @bp-desktop) {
  .trips {
    grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
    gap: var(--tm-space-4);
  }
}

.trip {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-1);
  height: 100%;
  padding: var(--tm-space-4);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  color: inherit;
  text-decoration: none;

  &:hover {
    border-color: var(--tm-navy);
  }

  &:active {
    background: var(--tm-board);
  }
}

.trip__name {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: var(--tm-text-lg);
  font-weight: 600;
  line-height: 1.4;
}

.trip__description {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.trip__dates {
  margin: 0;
  font-size: var(--tm-text-sm);
}

.trip__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tm-space-2) var(--tm-space-3);
  margin-top: var(--tm-space-2);
}

.trip__tz {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.trip__role {
  margin-left: auto;
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-navy);
  border-radius: var(--tm-radius-tag);
  font-size: var(--tm-text-xs);
  font-weight: 600;
  line-height: 20px;
}
</style>
