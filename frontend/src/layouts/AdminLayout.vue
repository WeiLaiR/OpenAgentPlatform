<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const routeTitle = computed(() => {
  if (route.path.includes('/knowledge')) {
    return '知识库管理'
  }
  if (route.path.includes('/mcp')) {
    return 'MCP 管理'
  }
  return '系统状态'
})

const routeDescription = computed(() => {
  if (route.path.includes('/knowledge')) {
    return '维护知识库、文件与检索链路。'
  }
  if (route.path.includes('/mcp')) {
    return '维护 MCP Server 与工具快照。'
  }
  return '查看当前运行状态与依赖健康度。'
})
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-sidebar">
      <RouterLink class="sidebar-brand" to="/chat">
        <p class="sidebar-eyebrow">后台管理区</p>
        <strong>Open Agent Platform</strong>
      </RouterLink>

      <nav class="admin-nav">
        <RouterLink to="/admin/system">系统</RouterLink>
        <RouterLink to="/admin/knowledge">知识库</RouterLink>
        <RouterLink to="/admin/mcp">MCP</RouterLink>
      </nav>

      <RouterLink class="chat-entry" to="/chat">返回聊天前台</RouterLink>
    </aside>

    <main class="admin-main">
      <header class="admin-header">
        <div>
          <p class="admin-kicker">Admin Workspace</p>
          <h1>{{ routeTitle }}</h1>
          <p class="admin-description">{{ routeDescription }}</p>
        </div>
      </header>

      <section class="admin-content">
        <RouterView />
      </section>
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-height: 100vh;
}

.admin-sidebar {
  display: grid;
  align-content: start;
  gap: 24px;
  padding: 28px 20px;
  border-right: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.58);
  backdrop-filter: blur(14px);
}

.sidebar-brand {
  display: grid;
  gap: 6px;
  padding: 18px;
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(255, 246, 230, 0.96), rgba(235, 244, 255, 0.92)),
    rgba(255, 255, 255, 0.82);
}

.sidebar-eyebrow,
.admin-kicker {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #8c5a17;
}

.sidebar-brand strong {
  font-size: 22px;
  color: #0f172a;
  line-height: 1.3;
}

.admin-nav {
  display: grid;
  gap: 8px;
}

.admin-nav a,
.chat-entry {
  padding: 12px 14px;
  border-radius: 16px;
  color: #516172;
  font-weight: 600;
  transition:
    background-color 160ms ease,
    color 160ms ease,
    transform 160ms ease;
}

.admin-nav a.router-link-active,
.admin-nav a:hover,
.chat-entry:hover {
  transform: translateY(-1px);
  background: rgba(31, 78, 121, 0.1);
  color: #1f4e79;
}

.chat-entry {
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.68);
  text-align: center;
}

.admin-main {
  display: grid;
  align-content: start;
  gap: 20px;
  padding: 28px;
}

.admin-header {
  padding: 0 4px;
}

.admin-header h1 {
  margin: 8px 0;
  font-size: clamp(30px, 4vw, 42px);
  color: #0f172a;
}

.admin-description {
  margin: 0;
  color: #64748b;
  line-height: 1.7;
}

.admin-content {
  max-width: 1320px;
}

@media (max-width: 960px) {
  .admin-shell {
    grid-template-columns: 1fr;
  }

  .admin-sidebar {
    border-right: 0;
    border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  }

  .admin-main {
    padding: 20px 16px;
  }
}
</style>
