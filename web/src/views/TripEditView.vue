<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import { getTrip, updateTrip, type TripRequest, type TripView } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import TripForm from '@/components/TripForm.vue'
import { showError } from '@/utils/toast'
import { serverFieldErrors, tripToFormState } from '@/utils/tripForm'

// 修改行程（仅负责人）。PUT 是整体替换：表单里清空的可选项会被清空，所以表单要带着现有的全部内容。
const props = defineProps<{ tripId: number }>()

const router = useRouter()
const form = ref<InstanceType<typeof TripForm>>()

type State = 'loading' | 'ready' | 'not-owner' | 'not-found' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const trip = ref<TripView | null>(null)
const submitting = ref(false)

async function load() {
  state.value = 'loading'
  try {
    const loaded = await getTrip(props.tripId)
    trip.value = loaded
    // 不是负责人就不给改（后端也会拒绝：403）
    state.value = loaded.myRole === 'owner' ? 'ready' : 'not-owner'
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
  router.push({ name: 'trip', params: { tripId: props.tripId } })
}

function goList() {
  router.push({ name: 'home' })
}

async function onSubmit(request: TripRequest) {
  submitting.value = true
  try {
    await updateTrip(props.tripId, request)
    showToast({ message: '已保存', type: 'success' })
    await router.replace({ name: 'trip', params: { tripId: props.tripId } })
  } catch (error) {
    const apiError = toApiError(error)
    const fieldErrors = serverFieldErrors(apiError)
    if (Object.keys(fieldErrors).length > 0) {
      await form.value?.showErrors(fieldErrors)
    } else if (apiError.status === 403 || apiError.status === 404) {
      // 刚好被转让了负责人，或者已经不在这个行程里：回到详情页，让页面按最新情况显示
      showError(apiError)
      await router.replace({ name: 'trip', params: { tripId: props.tripId } })
    } else if (apiError.status !== 401) {
      showError(apiError)
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <BoardHeader title="编辑行程" :subtitle="trip?.name" back-label="返回行程" narrow @back="goDetail" />

    <main class="edit tm-container tm-container--narrow">
      <div v-if="state === 'loading'" class="status" role="status">
        <van-loading size="20" />
        <span>正在加载…</span>
      </div>

      <div v-else-if="state === 'not-found'" class="status status--box" role="alert">
        <p class="status__text">找不到这个行程，或者你已经不在这个行程里了。</p>
        <van-button size="small" plain type="primary" @click="goList">回到我的行程</van-button>
      </div>

      <div v-else-if="state === 'not-owner'" class="status status--box" role="alert">
        <p class="status__text">只有负责人可以编辑行程。</p>
        <van-button size="small" plain type="primary" @click="goDetail">返回行程</van-button>
      </div>

      <div v-else-if="state === 'error'" class="status status--box" role="alert">
        <p class="status__text">{{ errorMessage }}</p>
        <van-button size="small" plain type="primary" @click="load">重试</van-button>
      </div>

      <TripForm
        v-else-if="trip"
        ref="form"
        :initial="tripToFormState(trip)"
        submit-label="保存修改"
        :submitting="submitting"
        @submit="onSubmit"
      />
    </main>
  </div>
</template>

<style lang="less" scoped>
.edit {
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
