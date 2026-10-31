<script setup lang="ts">
import { ref } from 'vue'

import type { MemberView } from '@/api/members'
import { roleLabel } from '@/utils/members'

// 成员列表。只负责显示和把"用户点了什么"报给页面；请求和确认框都在页面里。
// 负责人可以对其他在籍成员做"设为负责人""移出行程"，也可以添加成员。
const props = defineProps<{
  active: MemberView[]
  left: MemberView[]
  myMemberId: number
  isOwner: boolean
  /** 有操作在进行时，禁用所有按钮 */
  busy?: boolean
}>()

const emit = defineEmits<{
  add: []
  transfer: [member: MemberView]
  remove: [member: MemberView]
}>()

// 点某一行的"⋯"，弹出这个成员能做的操作
const target = ref<MemberView | null>(null)
const showMenu = ref(false)

function openMenu(member: MemberView) {
  target.value = member
  showMenu.value = true
}

const menuActions = [
  { name: '设为负责人', key: 'transfer' },
  { name: '移出行程', key: 'remove', color: 'var(--tm-danger)' },
]

function onSelect(action: { key?: string }) {
  const member = target.value
  showMenu.value = false
  if (!member) return
  if (action.key === 'transfer') emit('transfer', member)
  if (action.key === 'remove') emit('remove', member)
}

// 能被管理的是"别人"：负责人不能移出或转让给自己
function manageable(member: MemberView): boolean {
  return props.isOwner && member.memberId !== props.myMemberId
}
</script>

<template>
  <section aria-labelledby="members-title">
    <div class="head">
      <h2 id="members-title" class="head__title">
        成员 <span class="head__count tm-num">{{ active.length }}</span>
      </h2>
      <van-button v-if="isOwner" size="small" plain type="primary" class="tm-button-secondary" :disabled="busy" @click="emit('add')">
        添加成员
      </van-button>
    </div>

    <ul class="list">
      <li v-for="member in active" :key="member.memberId" class="member">
        <div class="member__text">
          <span class="member__name">{{ member.nickname }}</span>
          <span class="member__id tm-num">ID {{ member.userId }}</span>
        </div>
        <span v-if="member.memberId === myMemberId" class="tag">我</span>
        <span v-if="member.role === 'owner'" class="tag tag--strong">{{ roleLabel(member.role) }}</span>
        <button
          v-if="manageable(member)"
          type="button"
          class="member__more"
          :aria-label="`管理 ${member.nickname}`"
          :disabled="busy"
          @click="openMenu(member)"
        >
          <van-icon name="ellipsis" size="20" aria-hidden="true" />
        </button>
      </li>
    </ul>

    <details v-if="left.length" class="left">
      <summary class="left__summary">已退出的成员（{{ left.length }}）</summary>
      <ul class="list list--left">
        <li v-for="member in left" :key="member.memberId" class="member member--left">
          <div class="member__text">
            <span class="member__name">{{ member.nickname }}</span>
            <span class="member__id tm-num">ID {{ member.userId }}</span>
          </div>
          <span class="tag">已退出</span>
        </li>
      </ul>
    </details>

    <van-action-sheet
      v-model:show="showMenu"
      :title="target?.nickname"
      :actions="menuActions"
      cancel-text="取消"
      close-on-click-action
      @select="onSelect"
    />
  </section>
</template>

<style lang="less" scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--tm-space-3);
  margin-bottom: var(--tm-space-2);
}

.head__title {
  margin: 0;
  font-size: var(--tm-text-md);
  font-weight: 600;
}

.head__count {
  margin-left: var(--tm-space-1);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.list {
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

.member__more {
  display: grid;
  flex: none;
  place-items: center;
  width: var(--tm-tap-min);
  height: var(--tm-tap-min);
  margin-right: calc(var(--tm-space-2) * -1);
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--tm-muted);
  cursor: pointer;

  &:hover:not(:disabled) {
    background: var(--tm-board);
    color: var(--tm-navy);
  }

  &:disabled {
    cursor: default;
    opacity: 0.5;
  }
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
</style>
