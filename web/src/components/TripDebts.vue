<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { listDebts, type DebtView } from '@/api/debts'
import { describeError, toApiError } from '@/api/errors'
import type { MemberView } from '@/api/members'
import CurrencyAmount from '@/components/CurrencyAmount.vue'
import { debtKey, debtSide, sortDebtsForMe } from '@/utils/debts'
import { isMemberLeft, memberName } from '@/utils/expenses'

// 行程页里的欠款总览：每两人之间、按币别分开的一对一净额（不换算、不跨币别互抵）。
// 我欠的排最前，然后是欠我的，最后是别人之间的。"你欠"用实心标签、"欠你"用描边标签，不靠颜色区分（见 DESIGN.md）。
// 待确认的还款不抵债，另外写出来。列表自己取数据、自己处理加载失败。
// 另一个视图是"建议转账"（simplify=true）：把 A 欠 B、B 欠 C 合并成 A 欠 C，按它转能让所有人结清。
// 它只用于显示：pendingMinor 恒为 0，也不能据此记还款（还款按真实的一对一欠款校验额度），所以每条都标成"建议"。
const props = defineProps<{
  tripId: number
  /** 成员列表（包含已退出的），用来把 memberId 显示成昵称 */
  members: MemberView[]
  myMemberId: number
}>()

type State = 'loading' | 'ready' | 'error'
const state = ref<State>('loading')
const errorMessage = ref('')
const debts = ref<DebtView[]>([])
/** 只看和我有关的：交给后端按 memberId 筛（建议转账也是先化简整个行程，再筛） */
const onlyMine = ref(false)

type View = 'pairs' | 'simplified'
const view = ref<View>('pairs')
const viewOptions: ReadonlyArray<{ value: View; label: string }> = [
  { value: 'pairs', label: '一对一' },
  { value: 'simplified', label: '建议转账' },
]
const simplified = computed(() => view.value === 'simplified')

const rows = computed(() => sortDebtsForMe(debts.value, props.myMemberId))

// 同时有两次加载（比如连续切换筛选）时，只用最后一次的结果
let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  state.value = 'loading'
  try {
    const items = await listDebts(props.tripId, {
      memberId: onlyMine.value ? props.myMemberId : undefined,
      simplify: simplified.value,
    })
    if (seq !== loadSeq) return
    debts.value = items
    state.value = 'ready'
  } catch (error) {
    if (seq !== loadSeq) return
    const apiError = toApiError(error)
    // 401 由拦截器处理；404（已不在行程里）时页面本身也会显示"找不到"
    if (apiError.status === 401) return
    errorMessage.value = describeError(apiError)
    state.value = 'error'
  }
}

onMounted(load)
watch([() => props.tripId, onlyMine, view], load)

function personName(memberId: number): string {
  if (memberId === props.myMemberId) return '你'
  const name = memberName(props.members, memberId)
  return isMemberLeft(props.members, memberId) ? `${name}（已退出）` : name
}

function pairText(debt: DebtView): string {
  const from = personName(debt.fromMemberId)
  const to = personName(debt.toMemberId)
  return simplified.value ? `${from} 转给 ${to}` : `${from} 欠 ${to}`
}

const emptyText = computed(() => {
  if (onlyMine.value) return simplified.value ? '没有和你有关的建议转账。' : '你和别人之间都结清了。'
  return '大家都结清了。'
})

defineExpose({ reload: load })
</script>

<template>
  <section aria-labelledby="debts-title">
    <div class="head">
      <h2 id="debts-title" class="head__title">欠款</h2>
      <van-checkbox v-model="onlyMine" shape="square" icon-size="18px" class="mine">只看和我有关</van-checkbox>
    </div>

    <div class="segmented" role="radiogroup" aria-label="欠款的显示方式">
      <button
        v-for="option in viewOptions"
        :key="option.value"
        type="button"
        role="radio"
        class="segmented__item"
        :class="{ 'segmented__item--active': view === option.value }"
        :aria-checked="view === option.value"
        @click="view = option.value"
      >
        {{ option.label }}
      </button>
    </div>
    <p v-if="simplified" class="hint">
      把几段欠款合并成更少的转账，按这些转能让所有人结清（不保证笔数最少）。这只是建议：记还款要在“一对一”里，按真实的欠款记。
    </p>

    <div v-if="state === 'loading'" class="status" role="status">
      <van-loading size="20" />
      <span>正在加载欠款…</span>
    </div>

    <div v-else-if="state === 'error'" class="status status--box" role="alert">
      <p class="status__text">欠款没加载出来：{{ errorMessage }}</p>
      <van-button size="small" plain type="primary" class="tm-button-secondary" @click="load">重试</van-button>
    </div>

    <p v-else-if="rows.length === 0" class="empty">{{ emptyText }}</p>

    <ul v-else class="list">
      <li v-for="debt in rows" :key="debtKey(debt)" class="debt">
        <div class="debt__text">
          <span class="debt__pair">
            <span v-if="simplified" class="tag tag--quiet">建议</span>
            <span v-else-if="debtSide(debt, myMemberId) === 'i-owe'" class="tag tag--solid">你欠</span>
            <span v-else-if="debtSide(debt, myMemberId) === 'owes-me'" class="tag tag--outline">欠你</span>
            <span class="debt__names">{{ pairText(debt) }}</span>
          </span>
          <span v-if="!simplified && debt.pendingMinor > 0" class="debt__pending">
            待确认
            <CurrencyAmount :amount-minor="debt.pendingMinor" :currency="debt.currency" size="sm" />
          </span>
        </div>
        <div class="debt__side">
          <CurrencyAmount :amount-minor="debt.amountMinor" :currency="debt.currency" />
        </div>
      </li>
    </ul>
  </section>
</template>

<style lang="less" scoped>
.head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-2) var(--tm-space-3);
  min-height: var(--tm-tap-min);
  margin-bottom: var(--tm-space-2);
}

.head__title {
  margin: 0;
  font-size: var(--tm-text-md);
  font-weight: 600;
}

// 复选框整行都能点，高度不小于 44px
.mine {
  min-height: var(--tm-tap-min);
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

// 选中：加粗 + 双倍描边，不用实心主色（PC 上和"记一笔"同屏，每屏只有一个实心按钮）
.segmented__item--active {
  border-color: var(--tm-navy);
  box-shadow: inset 0 0 0 1px var(--tm-navy);
  font-weight: 600;
}

.hint {
  margin: 0 0 var(--tm-space-3);
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
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

.list {
  margin: 0;
  padding: 0;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  list-style: none;
}

.debt {
  display: flex;
  align-items: center;
  gap: var(--tm-space-3);
  min-height: 56px;
  padding: var(--tm-space-2) var(--tm-space-4);

  & + & {
    border-top: 1px solid var(--tm-line);
  }
}

.debt__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.debt__pair {
  display: flex;
  align-items: center;
  gap: var(--tm-space-2);
  min-width: 0;
}

.debt__names {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.debt__pending {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--tm-space-1) var(--tm-space-2);
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.debt__side {
  display: flex;
  flex: none;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--tm-space-1);
}

// "你欠"实心、"欠你"描边：靠形状和文字区分，不用红绿
.tag {
  flex: none;
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-navy);
  border-radius: var(--tm-radius-tag);
  font-size: var(--tm-text-xs);
  font-weight: 600;
  line-height: 18px;
}

.tag--solid {
  background: var(--tm-navy);
  color: var(--tm-surface);
}

.tag--outline {
  color: var(--tm-navy);
}

// 建议转账：虚线描边、次要色，和真实欠款的"你欠 / 欠你"一眼分开
.tag--quiet {
  border-style: dashed;
  border-color: var(--tm-muted);
  color: var(--tm-muted);
}
</style>
