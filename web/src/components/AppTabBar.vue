<script setup lang="ts">
// 手机版式的底部导航条：左边"行程"，右边"我的"（切换身份、退出登录都在里面）。
// 只在一级页面显示（路由的 meta.tab 为 true，见 router/index.ts），详情、新建、编辑这些二级页面用页头里的返回。
// PC 上整个隐藏：PC 用顶部导航条（AppTopBar），用户入口是右上角的头像。
</script>

<template>
  <nav class="tabbar-wrap" aria-label="底部导航">
    <!-- route：按当前路由自动高亮；placeholder：在页面底部留出同样高度，内容不会被导航条盖住 -->
    <van-tabbar route fixed placeholder safe-area-inset-bottom>
      <van-tabbar-item :to="{ name: 'home' }" icon="home-o">行程</van-tabbar-item>
      <van-tabbar-item :to="{ name: 'me' }" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </nav>
</template>

<style lang="less" scoped>
@import (reference) '../styles/breakpoints.less';

@media (min-width: @bp-desktop) {
  .tabbar-wrap {
    display: none;
  }
}

// 平板宽度（480–960px）时内容是居中的 480px 窄栏，导航条也要跟着居中限宽，不要铺满整个屏幕
.tabbar-wrap :deep(.van-tabbar--fixed) {
  left: 50%;
  max-width: var(--tm-app-max-width);
  transform: translateX(-50%);
}
</style>
