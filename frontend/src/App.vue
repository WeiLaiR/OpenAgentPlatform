<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="app-eyebrow">LangChain4j Learning Platform</p>
        <h1 class="app-title">Open Agent Platform</h1>
      </div>
      <nav class="app-nav">
        <RouterLink to="/system">系统</RouterLink>
        <RouterLink to="/chat">对话</RouterLink>
        <RouterLink to="/knowledge">知识库</RouterLink>
        <RouterLink to="/mcp">MCP</RouterLink>
      </nav>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
