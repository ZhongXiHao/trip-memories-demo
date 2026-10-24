import { createRouter, createWebHistory } from 'vue-router'

import { getUserId } from '@/api/session'

declare module 'vue-router' {
  interface RouteMeta {
    /** 不需要身份就能访问（选择身份页） */
    public?: boolean
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
    },
    {
      path: '/trips/new',
      name: 'trip-new',
      component: () => import('@/views/TripCreateView.vue'),
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
