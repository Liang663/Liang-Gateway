<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { admin } from "../api";
import { useTask } from "../useTask";
import type { LlmStats, UsageStats, CallLog, Page } from "../types";
import { money, number, date } from "../utils";
import Notice from "../components/Notice.vue";
import DataTable from "../components/DataTable.vue";
const range = ref("24h"),
  stats = ref<LlmStats>(),
  usage = ref<UsageStats>(),
  logs = ref<CallLog[]>([]),
  updated = ref("");
const { busy, error, run } = useTask();
async function load() {
  await run(async () => {
    const [s, u, l] = await Promise.all([
      admin<LlmStats>(`/admin/llm/stats?range=${range.value}`),
      admin<UsageStats>(`/admin/usage/stats?range=${range.value}`),
      admin<Page<CallLog>>("/admin/llm/call-logs?pageSize=5"),
    ]);
    stats.value = s;
    usage.value = u;
    logs.value = l.items;
    updated.value = new Date().toLocaleTimeString("zh-CN", {
      timeZone: "Asia/Shanghai",
    });
  });
}
onMounted(load);
const points = computed(() => {
  const items = stats.value?.trend || [],
    max = Math.max(1, ...items.map((p) => p.count));
  return items
    .map(
      (p, i) =>
        `${20 + (i * 720) / Math.max(1, items.length - 1)},${200 - (p.count / max) * 168}`,
    )
    .join(" ");
});
const maxCount = computed(() =>
  Math.max(1, ...(stats.value?.models || []).map((m) => m.count)),
);
const cards = computed(() => [
  {
    label: "上游调用",
    value: stats.value ? number(stats.value.total) : "—",
    unit: "次",
    note: "已记录的上游请求",
    icon: "↗",
  },
  {
    label: "调用成功率",
    value: stats.value?.total
      ? (stats.value.successRate * 100).toFixed(2)
      : "—",
    unit: "%",
    note: "成功数 / 已记录调用数",
    icon: "✓",
  },
  {
    label: "平均首 Token 耗时",
    value:
      stats.value?.averageFirstTokenMs == null
        ? "—"
        : Math.round(stats.value.averageFirstTokenMs),
    unit: "ms",
    note: "仅统计有效 TTFT 样本",
    icon: "◷",
  },
  {
    label: "已计费金额",
    value: usage.value ? money(usage.value.amountFen) : "—",
    unit: "",
    note: usage.value
      ? `${number(usage.value.totalTokens)} Tokens`
      : "来自用量账单",
    icon: "¥",
  },
]);
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">OVERVIEW</p>
      <h1>网关概览<span class="live-pill">LIVE DATA</span></h1>
      <p>模型调用、运行表现与计费，一处掌握。</p>
    </div>
    <div class="toolbar">
      <select
        v-model="range"
        :disabled="busy"
        aria-label="统计范围"
        @change="load"
      >
        <option value="24h">最近 24 小时</option>
        <option value="7d">最近 7 天</option></select
      ><button :disabled="busy" @click="load">
        {{ busy ? "刷新中…" : "刷新数据" }}
      </button>
    </div>
  </div>
  <Notice :error="error" :loading="busy" />
  <div class="metric-grid">
    <article v-for="card in cards" :key="card.label" class="metric">
      <div class="metric-label">
        {{ card.label }}<span>{{ card.icon }}</span>
      </div>
      <strong
        >{{ card.value }}<small>{{ card.unit }}</small></strong
      >
      <p>{{ card.note }}</p>
    </article>
  </div>
  <div class="overview-grid">
    <section class="panel chart-panel">
      <div class="panel-heading">
        <div>
          <h2>调用趋势</h2>
          <p>上海时间 · 每小时调用数</p>
        </div>
        <span class="badge green">{{
          range === "24h" ? "24 HOURS" : "7 DAYS"
        }}</span>
      </div>
      <svg
        class="trend"
        viewBox="0 0 760 230"
        role="img"
        aria-label="上游调用趋势折线图"
      >
        <defs>
          <linearGradient id="chartFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#15917a" stop-opacity=".22" />
            <stop offset="1" stop-color="#15917a" stop-opacity="0" />
          </linearGradient>
        </defs>
        <path
          v-for="y in [32, 88, 144, 200]"
          :key="y"
          :d="`M20 ${y} H740`"
          stroke="#e8eeeb"
          stroke-dasharray="4 5"
        />
        <polygon
          v-if="points"
          :points="`20,200 ${points} 740,200`"
          fill="url(#chartFill)"
        />
        <polyline
          :points="points"
          fill="none"
          stroke="#19856f"
          stroke-width="3"
          stroke-linejoin="round"
        />
        <text x="20" y="225" fill="#8a9691" font-size="12">
          {{ stats?.trend[0]?.time.replace("T", " ").slice(5, 16) }}
        </text>
        <text x="740" y="225" text-anchor="end" fill="#8a9691" font-size="12">
          {{ stats?.trend.at(-1)?.time.replace("T", " ").slice(5, 16) }}
        </text>
      </svg>
      <p v-if="stats && !stats.total" class="muted">此时间范围暂无上游调用。</p>
    </section>
    <section class="panel distribution">
      <div class="panel-heading">
        <div>
          <h2>模型分布</h2>
          <p>按上游调用次数</p>
        </div>
        <span>◉</span>
      </div>
      <div v-if="!stats?.models.length" class="empty">暂无模型调用</div>
      <div
        v-for="model in stats?.models"
        :key="model.model"
        class="distribution-row"
      >
        <div>
          <span>{{ model.model }}</span
          ><strong>{{ number(model.count) }}</strong>
        </div>
        <progress :value="model.count" :max="maxCount" />
      </div>
    </section>
  </div>
  <div class="banner">
    <div>
      <span class="eyebrow">CONNECT YOUR SERVICES</span>
      <h2>让 HTTP 接口成为 AI 可调用的工具</h2>
      <p>导入 OpenAPI，配置 MCP 工具，并在调试台验证完整调用。</p>
    </div>
    <RouterLink to="/mcp" class="button-link primary"
      >管理 MCP 服务 →</RouterLink
    >
  </div>
  <section class="panel">
    <div class="panel-heading">
      <div>
        <h2>最近上游调用</h2>
        <p>最近 24 小时 · 最多 5 条</p>
      </div>
      <RouterLink to="/logs" class="button-link">查看全部 →</RouterLink>
    </div>
    <DataTable
      :rows="logs"
      :loading="busy"
      :columns="[
        { key: 'model', label: '模型' },
        { key: 'success', label: '状态' },
        { key: 'firstTokenMs', label: 'TTFT / ms' },
        { key: 'totalDurationMs', label: '总耗时 / ms' },
        { key: 'createTime', label: '调用时间', format: date },
      ]"
    />
  </section>
  <p class="footnote">
    统计基于上游调用日志及用量账单。鉴权失败、上游调用前限额拦截不计入调用成功率。{{
      updated ? `更新于 ${updated}` : ""
    }}
  </p>
</template>
