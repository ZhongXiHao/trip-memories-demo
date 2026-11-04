<script setup lang="ts">
import type { DebtView } from '@/api/debts'
import type { MemberView } from '@/api/members'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { debtKey } from '@/utils/debts'
import { memberName } from '@/utils/expenses'

// 退出、移出成功后，后端提醒还有没结清的欠款时，把这些欠款列出来（只提醒，操作已经成功了）。
// 欠款不会因为退出而消失，仍在欠款总览里。
const props = defineProps<{
  title: string
  /** 后端给的提醒文字 */
  message: string
  debts: DebtView[]
  members: MemberView[]
  myMemberId: number
}>()
const show = defineModel<boolean>('show', { required: true })
const emit = defineEmits<{ closed: [] }>()

function personName(memberId: number): string {
  return memberId === props.myMemberId ? '你' : memberName(props.members, memberId)
}
</script>

<template>
  <van-dialog
    v-model:show="show"
    :title="title"
    confirm-button-text="知道了"
    confirm-button-color="var(--tm-primary)"
    teleport="body"
    @closed="emit('closed')"
  >
    <div class="unsettled">
      <p v-if="message" class="unsettled__message">{{ message }}</p>
      <ul class="unsettled__list">
        <li v-for="debt in debts" :key="debtKey(debt)" class="unsettled__row">
          <span class="unsettled__pair">{{ personName(debt.fromMemberId) }} 欠 {{ personName(debt.toMemberId) }}</span>
          <CurrencyAmount :amount-minor="debt.amountMinor" :currency="debt.currency" size="sm" />
        </li>
      </ul>
      <p class="unsettled__hint">这些欠款不会因此消失，仍在欠款里，可以照常还款。</p>
    </div>
  </van-dialog>
</template>

<style lang="less" scoped>
.unsettled {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
  padding: var(--tm-space-3) var(--tm-space-5) var(--tm-space-4);
  font-size: var(--tm-text-sm);
}

.unsettled__message,
.unsettled__hint {
  margin: 0;
}

.unsettled__hint {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.unsettled__list {
  margin: 0;
  padding: 0;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  list-style: none;
}

.unsettled__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  min-height: 40px;
  padding: var(--tm-space-1) var(--tm-space-3);

  & + & {
    border-top: 1px solid var(--tm-line);
  }
}

.unsettled__pair {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
