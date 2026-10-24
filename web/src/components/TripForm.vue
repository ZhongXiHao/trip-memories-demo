<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'

import { toApiError } from '@/api/errors'
import { listCurrencies, type Currency } from '@/api/currencies'
import type { TripRequest } from '@/api/trips'
import { fromIsoDate, toIsoDate } from '@/utils/date'
import { TIMEZONE_OPTIONS, timezoneLabel } from '@/utils/timezones'
import {
  DESCRIPTION_MAX,
  NAME_MAX,
  toTripRequest,
  validateTripForm,
  type TripFormErrors,
  type TripFormState,
} from '@/utils/tripForm'

// 创建和修改行程共用的表单：本地校验、币别/时区/日期选择。
// 它只负责收集和校验，不发请求：校验通过后把请求体交给页面（submit 事件）。
// 页面发请求遇到后端的字段错误时，调用 showErrors 把错误挂回对应输入框。
const props = defineProps<{
  /** 初始值（修改时是行程当前的内容）。只在创建表单时读一次，之后不再跟随它变化 */
  initial?: Partial<TripFormState>
  submitLabel: string
  submitting?: boolean
}>()

const emit = defineEmits<{ submit: [request: TripRequest] }>()

// 展示币别只用来显示概数，不写进任何账目；新建时默认人民币，用户可改
const DEFAULT_CURRENCY = 'CNY'

const form = reactive<TripFormState>({
  name: '',
  description: '',
  displayCurrency: '',
  timezone: '',
  startDate: '',
  endDate: '',
  ...props.initial,
})
const errors = ref<TripFormErrors>({})

// ---- 币别 ----
const currencies = ref<Currency[]>([])
const currencyLoadFailed = ref(false)
const currencyColumns = computed(() =>
  currencies.value.map((c) => ({ text: `${c.code}　${c.name}`, value: c.code })),
)
const currencyText = computed(() => {
  const found = currencies.value.find((c) => c.code === form.displayCurrency)
  // 币别列表还没回来时，先显示代码，不要让已有的值看起来像"没选"
  return found ? `${found.code}　${found.name}` : form.displayCurrency
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
    // 401 由拦截器处理；其它错误让用户重试，没有币别列表就没法选
    if (toApiError(error).status !== 401) currencyLoadFailed.value = true
  }
}

onMounted(loadCurrencies)

// ---- 三个弹层：币别、时区、日期 ----
const showCurrency = ref(false)
const showTimezone = ref(false)
const showCalendar = ref(false)

// 当前时区不在常见列表里（比如后端里已有的别的地方）时，临时补进去，免得编辑时丢失
const timezoneColumns = computed(() => {
  const columns = TIMEZONE_OPTIONS.map((o) => ({ text: `${o.label}　${o.value}`, value: o.value }))
  const current = form.timezone
  if (current && !TIMEZONE_OPTIONS.some((o) => o.value === current)) {
    columns.unshift({ text: current, value: current })
  }
  return columns
})
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

// ---- 提交 ----
// 把第一条错误提示滚到屏幕中间，手机上不然看不到。
// Vant 只在设了 error 属性时才给输入框加 van-field--error；我们用 error-message，所以按提示元素找。
async function scrollToFirstError() {
  await nextTick()
  document
    .querySelector('.trip-form .van-field__error-message')
    ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

async function submit() {
  if (props.submitting) return
  errors.value = validateTripForm(form)
  if (Object.keys(errors.value).length > 0) {
    await scrollToFirstError()
    return
  }
  emit('submit', toTripRequest(form))
}

/** 页面发请求后收到后端的字段错误时调用 */
async function showErrors(serverErrors: TripFormErrors) {
  errors.value = serverErrors
  await scrollToFirstError()
}

defineExpose({ showErrors })
</script>

<template>
  <form class="trip-form" novalidate @submit.prevent="submit">
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
      {{ submitLabel }}
    </van-button>

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
  </form>
</template>

<style lang="less" scoped>
.trip-form {
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
