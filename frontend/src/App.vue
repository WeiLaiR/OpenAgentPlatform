<template>
  <div class="app-shell">
    <header class="app-header">
      <div>
        <p class="app-eyebrow">LangChain4j Learning Platform</p>
        <h1 class="app-title">Open Agent Platform</h1>
      </div>
      <nav class="app-nav">
        <RouterLink to="/">控制台</RouterLink>
      </nav>
    </header>

    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
