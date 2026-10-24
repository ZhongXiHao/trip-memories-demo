<script setup lang="ts">
import { ref, watch } from 'vue'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import { addMember } from '@/api/members'

// 按用户 ID 添加成员。开发期没有"搜索用户"，ID 可以在"选择身份"页看到。
const props = defineProps<{ tripId: number }>()
const show = defineModel<boolean>('show', { required: true })
const emit = defineEmits<{ added: [] }>()

const userIdText = ref('')
const error = ref('')
const submitting = ref(false)

// 每次打开都是干净的表单
watch(show, (open) => {
  if (open) {
    userIdText.value = ''
    error.value = ''
  }
})

async function submit() {
  if (submitting.value) return
  const userId = Number(userIdText.value.trim())
  if (!Number.isSafeInteger(userId) || userId <= 0) {
    error.value = '请输入大于 0 的数字'
    return
  }

  submitting.value = true
  try {
    const { member, rejoined } = await addMember(props.tripId, userId)
    showToast({
      message: rejoined ? `${member.nickname} 重新加入了行程` : `已添加 ${member.nickname}`,
      type: 'success',
    })
    show.value = false
    emit('added')
  } catch (e) {
    const apiError = toApiError(e)
    if (apiError.fieldMessage('userId')) {
      error.value = '没有这个用户，请检查 ID'
    } else if (apiError.code === 'ALREADY_MEMBER') {
      error.value = '这个人已经在行程里了'
    } else if (apiError.status !== 401) {
      showToast({ message: describeError(apiError), position: 'bottom' })
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <van-popup v-model:show="show" position="bottom" round>
    <form class="popup" novalidate @submit.prevent="submit">
      <h2 class="popup__title">添加成员</h2>
      <van-field
        v-model="userIdText"
        label="用户 ID"
        placeholder="比如 6"
        type="digit"
        inputmode="numeric"
        enterkeyhint="done"
        :error-message="error"
        @update:model-value="error = ''"
      />
      <p class="popup__hint">在“选择身份”页能看到每个账号的 ID。对方如果以前退出过，会以原来的成员身份重新加入。</p>
      <van-button type="primary" block native-type="submit" :loading="submitting">添加</van-button>
    </form>
  </van-popup>
</template>

<style lang="less" scoped>
.popup {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-3);
  padding: var(--tm-space-4) var(--tm-space-4) calc(var(--tm-space-5) + env(safe-area-inset-bottom));
}

.popup__title {
  margin: 0;
  font-size: var(--tm-text-lg);
  font-weight: 600;
}

.popup__hint {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}
</style>
