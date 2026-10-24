<script setup lang="ts">
import { RouterView, useRoute } from 'vue-router'

import AppTabBar from '@/components/AppTabBar.vue'
import AppTopBar from '@/components/AppTopBar.vue'

const route = useRoute()
</script>

<template>
  <AppTopBar />
  <RouterView />
  <!-- 手机的底部导航条：只在一级页面显示（见 router 的 meta.tab） -->
  <AppTabBar v-if="route.meta.tab" />
</template>
