<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { toApiError } from '@/api/errors'
import { createTrip, type TripRequest } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import TripForm from '@/components/TripForm.vue'
import { showError } from '@/utils/toast'
import { serverFieldErrors } from '@/utils/tripForm'

const router = useRouter()
const form = ref<InstanceType<typeof TripForm>>()
const submitting = ref(false)

async function onSubmit(request: TripRequest) {
  submitting.value = true
  try {
    const trip = await createTrip(request)
    showToast({ message: `已创建“${trip.name}”`, type: 'success' })
    await router.replace({ name: 'home' })
  } catch (error) {
    const apiError = toApiError(error)
    const fieldErrors = serverFieldErrors(apiError)
    if (Object.keys(fieldErrors).length > 0) {
      await form.value?.showErrors(fieldErrors)
    } else if (apiError.status !== 401) {
      showError(apiError)
    }
  } finally {
    submitting.value = false
  }
}

function back() {
  router.push({ name: 'home' })
}
</script>

<template>
  <div>
    <BoardHeader title="新建行程" back-label="我的行程" narrow @back="back" />

    <main class="create tm-container tm-container--narrow">
      <TripForm ref="form" submit-label="创建行程" :submitting="submitting" @submit="onSubmit" />
    </main>
  </div>
</template>

<style lang="less" scoped>
.create {
  padding-block: var(--tm-space-4) calc(var(--tm-space-6) + env(safe-area-inset-bottom));
}
</style>
