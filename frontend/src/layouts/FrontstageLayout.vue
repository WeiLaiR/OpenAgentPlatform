<template>
  <div class="frontstage-shell">
    <main class="frontstage-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.frontstage-shell {
  min-height: 100vh;
  padding: 0;
}

.frontstage-main {
  min-height: 100vh;
  width: 100%;
}

@media (max-width: 768px) {
  .frontstage-shell {
    min-height: 100dvh;
  }
}
</style>
