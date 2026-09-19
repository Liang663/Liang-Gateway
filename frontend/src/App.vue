<script setup lang="ts">
import { ref } from "vue";
import { useRoute } from "vue-router";
import { authenticated, login, logout } from "./api";
import { useTask } from "./useTask";
import Notice from "./components/Notice.vue";
const route = useRoute(),
  secret = ref(""),
  { busy, error, run } = useTask();
const nav = [
  ["/", "◈", "网关概览"],
  ["/models", "▦", "模型管理"],
  ["/keys", "⌘", "上游密钥"],
  ["/users", "◇", "用户与令牌"],
  ["/mcp", "⬡", "MCP 管理"],
  ["/logs", "≡", "日志与用量"],
  ["/playground", "▷", "在线调试"],
];
async function signIn() {
  await run(async () => {
    await login(secret.value);
    secret.value = "";
  });
}
</script>
<template>
  <div v-if="!authenticated()" class="login-page">
    <div class="login-brand">
      <div class="brand-mark">L<span>↗</span></div>
      <p class="eyebrow">LIANG GATEWAY</p>
      <h1>连接模型，<br />掌控每一次调用。</h1>
      <p>模型接入 · 额度控制 · MCP 工具</p>
      <div class="login-orbit">LG<span>统一 AI 网关</span></div>
    </div>
    <form class="login-form" @submit.prevent="signIn">
      <span class="badge green">管理控制台</span>
      <h2>欢迎回来</h2>
      <p>输入网关配置中的管理密钥以继续。</p>
      <label
        >管理密钥<input
          v-model="secret"
          type="password"
          required
          autocomplete="off"
          placeholder="X-Admin-Token"
          autofocus /></label
      ><Notice :error="error" /><button class="primary" :disabled="busy">
        {{ busy ? "正在验证…" : "进入管理后台 →" }}</button
      ><small
        >密钥仅保留在当前页面内存。刷新页面后需重新输入。生产环境请使用
        HTTPS。</small
      >
    </form>
  </div>
  <div v-else class="shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"
        ><div class="brand-mark small">L<span>↗</span></div>
        <div>Liang Gateway<small>AI INFRASTRUCTURE</small></div></RouterLink
      >
      <div class="workspace">
        <i></i>
        <div>网关工作空间<small>Management console</small></div>
        <span>⌄</span>
      </div>
      <p class="nav-label">WORKSPACE</p>
      <nav>
        <RouterLink
          v-for="item in nav"
          :key="item[0]"
          :to="item[0]!"
          :class="{ selected: route.path === item[0] }"
          ><span>{{ item[1] }}</span
          >{{ item[2] }}</RouterLink
        >
      </nav>
      <div class="sidebar-bottom">
        <span class="status-dot"></span> 服务端真实数据<small
          >Liang-Gateway / Console v1</small
        >
      </div>
    </aside>
    <div class="workspace-main">
      <header class="topbar">
        <div>
          <span class="muted">工作空间</span><span class="crumb">/</span
          >{{ route.meta.title }}
        </div>
        <div class="toolbar">
          <span class="timezone">Asia / Shanghai</span
          ><span class="avatar">管</span><button @click="logout">退出</button>
        </div>
      </header>
      <main><RouterView /></main>
      <div class="app-footer">
        Liang Gateway · 管理控制台<span
          >配置操作即时生效，请谨慎修改生产数据。</span
        >
      </div>
    </div>
  </div>
</template>
