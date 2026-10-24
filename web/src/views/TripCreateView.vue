<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import 'vant/es/toast/style'

import { describeError, toApiError } from '@/api/errors'
import { listCurrencies, type Currency } from '@/api/currencies'
import { createTrip } from '@/api/trips'
import BoardHeader from '@/components/BoardHeader.vue'
import { fromIsoDate, toIsoDate } from '@/utils/date'
import { TIMEZONE_OPTIONS, timezoneLabel } from '@/utils/timezones'
import {
  DESCRIPTION_MAX,
  NAME_MAX,
  serverFieldErrors,
  toTripRequest,
  validateTripForm,
  type TripFormErrors,
  type TripFormState,
} from '@/utils/tripForm'

const router = useRouter()

// 展示币别只用来显示概数，不写进任何账目；没选过时默认人民币，用户可改
const DEFAULT_CURRENCY = 'CNY'

const form = reactive<TripFormState>({
  name: '',
  description: '',
  displayCurrency: '',
  timezone: '',
  startDate: '',
  endDate: '',
})
const errors = ref<TripFormErrors>({})
const submitting = ref(false)

// 币别列表
const currencies = ref<Currency[]>([])
const currencyLoadFailed = ref(false)
const currencyColumns = computed(() =>
  currencies.value.map((c) => ({ text: `${c.code}　${c.name}`, value: c.code })),
)
const currencyText = computed(() => {
  const found = currencies.value.find((c) => c.code === form.displayCurrency)
  return found ? `${found.code}　${found.name}` : ''
})

async function loadCurrencies() {
  currencyLoadFailed.value = false
  try {
    currencies.value = await listCurrencies()
    if (!form.displayCurrency) {
      form.displayCurrency = currencies.value.some((c) => c.code === DEFAULT_CURRENCY)
        ? DEFAULT_CURRENCY
        : (currencies.value[0]?.code ?? '')
    }
  } catch (error) {
    // 401 由拦截器处理；其它错误让用户重试，没有币别列表就没法提交
    if (toApiError(error).status !== 401) currencyLoadFailed.value = true
  }
}

onMounted(loadCurrencies)

// 三个弹层：币别、时区、日期
const showCurrency = ref(false)
const showTimezone = ref(false)
const showCalendar = ref(false)

const timezoneColumns = TIMEZONE_OPTIONS.map((o) => ({ text: `${o.label}　${o.value}`, value: o.value }))
const timezoneText = computed(() => (form.timezone ? timezoneLabel(form.timezone) : ''))

function onCurrencyConfirm({ selectedValues }: { selectedValues: Array<string | number> }) {
  form.displayCurrency = String(selectedValues[0] ?? '')
  errors.value.displayCurrency = undefined
  showCurrency.value = false
}

function onTimezoneConfirm({ selectedValues }: { selectedValues: Array<string | number> }) {
  form.timezone = String(selectedValues[0] ?? '')
  errors.value.timezone = undefined
  showTimezone.value = false
}

// 日期：选填；日历选一个区间。一天的行程起止同一天
const today = new Date()
const minDate = new Date(today.getFullYear() - 2, 0, 1)
const maxDate = new Date(today.getFullYear() + 2, 11, 31)
const datesText = computed(() => (form.startDate && form.endDate ? `${form.startDate} 至 ${form.endDate}` : ''))
const calendarDefault = computed(() => {
  const start = fromIsoDate(form.startDate)
  const end = fromIsoDate(form.endDate)
  return start && end ? [start, end] : null
})

function onCalendarConfirm(dates: Date | Date[]) {
  const [start, end] = Array.isArray(dates) ? dates : [dates, dates]
  form.startDate = start ? toIsoDate(start) : ''
  form.endDate = end ? toIsoDate(end) : ''
  errors.value.endDate = undefined
  showCalendar.value = false
}

function clearDates() {
  form.startDate = ''
  form.endDate = ''
  errors.value.endDate = undefined
}

// 把第一条错误提示滚到屏幕中间，手机上不然看不到。
// Vant 只在设了 error 属性时才给输入框加 van-field--error；我们用 error-message，所以按提示元素找。
async function scrollToFirstError() {
  await nextTick()
  document
    .querySelector('.create .van-field__error-message')
    ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

async function submit() {
  if (submitting.value) return
  errors.value = validateTripForm(form)
  if (Object.keys(errors.value).length > 0) {
    await scrollToFirstError()
    return
  }

  submitting.value = true
  try {
    const trip = await createTrip(toTripRequest(form))
    showToast({ message: `已创建“${trip.name}”`, type: 'success' })
    await router.replace({ name: 'home' })
  } catch (error) {
    const apiError = toApiError(error)
    const fieldErrors = serverFieldErrors(apiError)
    if (Object.keys(fieldErrors).length > 0) {
      errors.value = fieldErrors
      await scrollToFirstError()
    } else if (apiError.status !== 401) {
      showToast({ message: describeError(apiError), position: 'bottom' })
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
      <form class="form" novalidate @submit.prevent="submit">
        <div class="card">
          <van-field
            v-model="form.name"
            label="名称"
            placeholder="比如：东京五日游"
            required
            :maxlength="NAME_MAX"
            autocomplete="off"
            enterkeyhint="next"
            :error-message="errors.name"
            @update:model-value="errors.name = undefined"
          />
          <van-field
            v-model="form.description"
            label="备注"
            type="textarea"
            rows="2"
            autosize
            placeholder="选填"
            :maxlength="DESCRIPTION_MAX"
            show-word-limit
            :error-message="errors.description"
            @update:model-value="errors.description = undefined"
          />
        </div>

        <div class="card">
          <van-field
            :model-value="timezoneText"
            label="时区"
            placeholder="选择目的地所在的时区"
            readonly
            is-link
            required
            :error-message="errors.timezone"
            @click="showTimezone = true"
            @keydown.enter.prevent="showTimezone = true"
          />
          <van-field
            :model-value="datesText"
            label="日期"
            placeholder="选填"
            readonly
            is-link
            :error-message="errors.endDate"
            @click="showCalendar = true"
            @keydown.enter.prevent="showCalendar = true"
          />
          <div v-if="datesText" class="card__inline-action">
            <button type="button" class="link-button" @click="clearDates">清除日期</button>
          </div>
          <van-field
            :model-value="currencyText"
            label="展示币别"
            :placeholder="currencyLoadFailed ? '币别没加载出来，点这里重试' : '选择币别'"
            readonly
            is-link
            required
            :error-message="errors.displayCurrency"
            @click="currencyLoadFailed ? loadCurrencies() : (showCurrency = true)"
            @keydown.enter.prevent="currencyLoadFailed ? loadCurrencies() : (showCurrency = true)"
          />
        </div>

        <p class="hint">
          时区决定每笔支出默认的当地时间和“第几天”，选目的地所在的时区。展示币别只用来显示概数，不会写进任何账目。
        </p>

        <van-button type="primary" block native-type="submit" :loading="submitting" :disabled="currencyLoadFailed">
          创建行程
        </van-button>
      </form>
    </main>

    <van-popup v-model:show="showCurrency" position="bottom" round>
      <van-picker
        title="展示币别"
        :columns="currencyColumns"
        :model-value="form.displayCurrency ? [form.displayCurrency] : []"
        @confirm="onCurrencyConfirm"
        @cancel="showCurrency = false"
      />
    </van-popup>

    <van-popup v-model:show="showTimezone" position="bottom" round>
      <van-picker
        title="目的地时区"
        :columns="timezoneColumns"
        :model-value="form.timezone ? [form.timezone] : []"
        @confirm="onTimezoneConfirm"
        @cancel="showTimezone = false"
      />
    </van-popup>

    <van-calendar
      v-model:show="showCalendar"
      type="range"
      title="出行日期"
      :min-date="minDate"
      :max-date="maxDate"
      :default-date="calendarDefault"
      allow-same-day
      color="var(--tm-primary)"
      @confirm="onCalendarConfirm"
    />
  </div>
</template>

<style lang="less" scoped>
.create {
  padding-block: var(--tm-space-4) calc(var(--tm-space-6) + env(safe-area-inset-bottom));
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--tm-space-4);
}

.card {
  overflow: hidden;
  border: 1px solid var(--tm-line);
  border-radius: var(--tm-radius-card);
  background: var(--tm-surface);
}

.card__inline-action {
  padding: 0 var(--tm-space-4) var(--tm-space-2);
  text-align: right;
}

.link-button {
  min-height: var(--tm-tap-min);
  padding: 0 var(--tm-space-2);
  border: 0;
  background: transparent;
  color: var(--tm-primary);
  font: inherit;
  font-size: var(--tm-text-sm);
  cursor: pointer;
}

.hint {
  margin: 0;
  color: var(--tm-muted);
  font-size: var(--tm-text-xs);
}
</style>
