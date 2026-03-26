import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/chat',
    },
    {
      path: '/chat',
      component: () => import('@/layouts/FrontstageLayout.vue'),
      children: [
        {
          path: '',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
        },
      ],
    },
    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
        {
          path: '',
          redirect: '/admin/system',
        },
        {
          path: 'system',
          name: 'admin-system',
          component: () => import('@/views/SystemView.vue'),
        },
        {
          path: 'knowledge',
          name: 'admin-knowledge',
          component: () => import('@/views/KnowledgeView.vue'),
        },
        {
          path: 'mcp',
          name: 'admin-mcp',
          component: () => import('@/views/McpView.vue'),
        },
      ],
    },
    {
      path: '/system',
      redirect: '/admin/system',
    },
    {
      path: '/knowledge',
      redirect: '/admin/knowledge',
    },
    {
      path: '/mcp',
      redirect: '/admin/mcp',
    },
  ],
})

export default router
