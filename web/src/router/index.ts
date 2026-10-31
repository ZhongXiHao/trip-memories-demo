import { createRouter, createWebHistory } from 'vue-router'

import { getUserId } from '@/api/session'

declare module 'vue-router' {
  interface RouteMeta {
    /** 不需要身份就能访问（选择身份页） */
    public?: boolean
    /** 一级页面：手机上显示底部导航条（行程 / 我的）。详情、新建、编辑这些二级页面不显示。 */
    tab?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/select-user',
      name: 'select-user',
      component: () => import('@/views/SelectUserView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { tab: true },
    },
    {
      path: '/me',
      name: 'me',
      component: () => import('@/views/MeView.vue'),
      meta: { tab: true },
    },
    {
      path: '/trips/new',
      name: 'trip-new',
      component: () => import('@/views/TripCreateView.vue'),
    },
    {
      // 只接受数字 id；/trips/new 这类固定路径不会被它吃掉
      path: '/trips/:tripId(\\d+)',
      name: 'trip',
      component: () => import('@/views/TripDetailView.vue'),
      props: (route) => ({ tripId: Number(route.params.tripId) }),
    },
    {
      path: '/trips/:tripId(\\d+)/edit',
      name: 'trip-edit',
      component: () => import('@/views/TripEditView.vue'),
      props: (route) => ({ tripId: Number(route.params.tripId) }),
    },
    {
      path: '/trips/:tripId(\\d+)/expenses/:expenseId(\\d+)',
      name: 'expense',
      component: () => import('@/views/ExpenseDetailView.vue'),
      props: (route) => ({ tripId: Number(route.params.tripId), expenseId: Number(route.params.expenseId) }),
    },
    { path: '/:pathMatch(.*)*', redirect: { name: 'home' } },
  ],
})

// 没有身份就去选择身份页，选完回到原来要去的地方
router.beforeEach((to) => {
  if (to.meta.public || getUserId() !== null) return true
  return { name: 'select-user', query: { redirect: to.fullPath } }
})

export default router
