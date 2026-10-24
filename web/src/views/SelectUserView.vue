<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError, toApiError } from '@/api/errors'
import BoardHeader from '@/components/BoardHeader.vue'
import { useSessionStore, type SavedAccount } from '@/stores/session'
import { showError } from '@/utils/toast'

// 开发期的临时入口：后端没有登录，靠 X-User-Id 区分身份。
// 接入验证码登录后，这个页面会被登录页替换。
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const isDev = import.meta.env.DEV

const NICKNAME_MAX = 64

const nickname = ref('')
const nicknameError = ref('')
const idInput = ref('')
const idError = ref('')
/** 正在进行的操作，用来给对应按钮显示加载态并禁用其它操作 */
const busy = ref<'account' | 'create' | 'id' | null>(null)
const busyAccountId = ref<number | null>(null)

const accounts = computed(() => session.accounts)

/** 选完身份去哪：只接受站内路径，防止被 ?redirect= 带到别的网站 */
function nextPath(): string {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/'
}

async function chooseAccount(account: SavedAccount) {
  if (busy.value) return
  busy.value = 'account'
  busyAccountId.value = account.id
  try {
    await session.signInAs(account.id)
    await router.replace(nextPath())
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 401) {
      // 后端不认这个 id：通常是数据库重建过。留着它没有意义。
      session.forget(account.id)
      showError(new ApiError(401, 'UNAUTHENTICATED', `账号“${account.nickname}”在服务器上已不存在，已从列表移除`))
    } else {
      showError(apiError)
    }
  } finally {
    busy.value = null
    busyAccountId.value = null
  }
}

async function createAccount() {
  if (busy.value) return
  const name = nickname.value.trim()
  if (!name) {
    nicknameError.value = '请填写昵称'
    return
  }
  if (name.length > NICKNAME_MAX) {
    nicknameError.value = `昵称最多 ${NICKNAME_MAX} 个字`
    return
  }
  nicknameError.value = ''
  busy.value = 'create'
  try {
    await session.register(name)
    await router.replace(nextPath())
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.fieldMessage('nickname')) {
      nicknameError.value = `昵称不合法，最多 ${NICKNAME_MAX} 个字`
    } else if (apiError.status === 404) {
      showError(new ApiError(404, 'DEV_ONLY', '当前后端不是 dev 环境，不能创建账号'))
    } else {
      showError(apiError)
    }
  } finally {
    busy.value = null
  }
}

async function useExistingId() {
  if (busy.value) return
  const id = Number(idInput.value.trim())
  if (!Number.isSafeInteger(id) || id <= 0) {
    idError.value = '请输入大于 0 的数字'
    return
  }
  idError.value = ''
  busy.value = 'id'
  try {
    await session.signInAs(id)
    await router.replace(nextPath())
  } catch (error) {
    const apiError = toApiError(error)
    if (apiError.status === 401) {
      idError.value = '服务器上没有这个账号'
    } else {
      showError(apiError)
    }
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div class="select-user">
    <BoardHeader title="选择身份" subtitle="开发期没有登录，先选一个账号，或创建新的。" narrow />

    <main class="select-user__main tm-container tm-container--narrow">
      <section aria-labelledby="saved-title">
        <h2 id="saved-title" class="section-title">这台设备用过的账号</h2>

        <ul v-if="accounts.length" class="accounts" :class="{ 'accounts--has-current': session.userId !== null }">
          <li v-for="account in accounts" :key="account.id">
            <button
              type="button"
              class="account"
              :disabled="busy !== null"
              :aria-current="account.id === session.userId ? 'true' : undefined"
              @click="chooseAccount(account)"
            >
              <span class="account__badge" aria-hidden="true">{{ [...account.nickname][0] }}</span>
              <span class="account__text">
                <span class="account__name">{{ account.nickname }}</span>
                <span class="account__id tm-num">ID {{ account.id }}</span>
              </span>
              <van-loading v-if="busyAccountId === account.id" size="20" />
              <span v-else-if="account.id === session.userId" class="account__current">当前</span>
              <van-icon v-else name="arrow" class="account__chevron" aria-hidden="true" />
            </button>
          </li>
        </ul>
        <p v-else class="empty">还没有账号。在下面创建一个就能开始。</p>
      </section>

      <template v-if="isDev">
        <section aria-labelledby="create-title">
          <h2 id="create-title" class="section-title">创建新账号</h2>
          <form class="card" @submit.prevent="createAccount">
            <van-field
              v-model="nickname"
              label="昵称"
              placeholder="最多 64 个字"
              maxlength="64"
              autocomplete="off"
              enterkeyhint="done"
              :error-message="nicknameError"
              @update:model-value="nicknameError = ''"
            />
            <div class="card__action">
              <van-button type="primary" block native-type="submit" :loading="busy === 'create'" :disabled="busy !== null && busy !== 'create'">
                创建并使用
              </van-button>
            </div>
          </form>
        </section>

        <section aria-labelledby="id-title">
          <h2 id="id-title" class="section-title">用已有账号的 ID</h2>
          <form class="card" @submit.prevent="useExistingId">
            <van-field
              v-model="idInput"
              label="账号 ID"
              placeholder="比如 1"
              type="digit"
              inputmode="numeric"
              enterkeyhint="go"
              :error-message="idError"
              @update:model-value="idError = ''"
            />
            <div class="card__action">
              <van-button plain type="primary" block native-type="submit" :loading="busy === 'id'" :disabled="busy !== null && busy !== 'id'">
                使用这个账号
              </van-button>
            </div>
          </form>
        </section>
      </template>
      <p v-else class="empty">当前环境不支持开发登录。</p>

      <p class="note">这个页面只在开发期使用，接入验证码登录后会被替换。</p>
    </main>
  </div>
</template>

<style lang="less" scoped>
.select-user__main {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-5);
  // 左右留白由 .tm-container 管，这里只设上下
  padding-block: var(--tm-space-5) calc(var(--tm-space-6) + env(safe-area-inset-bottom));
}

.section-title {
  margin: 0 0 var(--tm-space-2);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
  font-weight: 600;
}

.accounts {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.account {
  display: flex;
  align-items: center;
  gap: var(--tm-space-3);
  width: 100%;
  min-height: 56px;
  padding: var(--tm-space-2) var(--tm-space-3);
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;

  &:active:not(:disabled) {
    background: var(--tm-board);
  }

  &:disabled {
    cursor: default;
    opacity: 0.6;
  }

  // 当前账号：深色描边 + 右侧"当前"标签，不再叠加第三个信号
  &[aria-current='true'] {
    border-color: var(--tm-navy);
  }
}

// 已经有当前账号时，其余账号退到背景里：去掉白色卡片底，名字、首字母改用次要文字色。
// 不能直接用 opacity：它会把文字对比度拉到 4.5:1 以下。次要文字色在页面底色上是 5.07:1，仍然达标。
// 一个账号都没选过时（第一次进来）不淡化，它们就是主选项。
.accounts--has-current .account:not([aria-current='true']) {
  background: transparent;
  border-color: var(--tm-line);

  &:active:not(:disabled) {
    background: var(--tm-surface);
  }

  .account__badge {
    background: transparent;
    border: 1px solid var(--tm-line);
    color: var(--tm-muted);
  }

  .account__name {
    color: var(--tm-muted);
    font-weight: 500;
  }
}

.account__chevron {
  flex: none;
  color: var(--tm-muted);
}

.account__badge {
  display: grid;
  flex: none;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: var(--tm-radius-tag);
  background: var(--tm-navy);
  color: var(--tm-amber);
  font-size: var(--tm-text-lg);
  font-weight: 600;
}

.account__text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.account__name {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account__id {
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}

.account__current {
  flex: none;
  padding: 0 var(--tm-space-2);
  border: 1px solid var(--tm-navy);
  border-radius: var(--tm-radius-tag);
  font-size: var(--tm-text-xs);
  font-weight: 600;
  line-height: 20px;
}

.card {
  overflow: hidden;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
}

.card__action {
  padding: var(--tm-space-3) var(--tm-space-4) var(--tm-space-4);
}

.empty {
  margin: 0;
  padding: var(--tm-space-4);
  border: 1px dashed var(--tm-line);
  border-radius: var(--tm-radius-card);
  color: var(--tm-muted);
  font-size: var(--tm-text-sm);
}

.note {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}
</style>
