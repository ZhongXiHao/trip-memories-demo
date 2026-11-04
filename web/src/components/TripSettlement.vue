<script setup lang="ts">
import { ref } from 'vue'

import type { MemberView } from '@/api/members'
import TripDebts from '@/components/TripDebts.vue'
import TripRepayments from '@/components/TripRepayments.vue'

// 行程页的"欠款"部分：上面是欠款总览，下面是还款记录。
// 两者互相影响：记了还款，还款列表要刷新；确认或取消了还款，欠款（和待确认金额）要刷新。
defineProps<{
  tripId: number
  members: MemberView[]
  myMemberId: number
  timezone: string
}>()

const debtsRef = ref<InstanceType<typeof TripDebts>>()
const repaymentsRef = ref<InstanceType<typeof TripRepayments>>()
</script>

<template>
  <div class="settlement">
    <TripDebts
      ref="debtsRef"
      :trip-id="tripId"
      :members="members"
      :my-member-id="myMemberId"
      @repaid="repaymentsRef?.reload()"
    />
    <TripRepayments
      ref="repaymentsRef"
      :trip-id="tripId"
      :members="members"
      :my-member-id="myMemberId"
      :timezone="timezone"
      @changed="debtsRef?.reload()"
    />
  </div>
</template>

<style lang="less" scoped>
.settlement {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-5);
}
</style>
