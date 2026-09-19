<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { admin, query } from "../api";
import { useTask } from "../useTask";
import type { Page, CallLog, UsageRecord, Column } from "../types";
import { date, exportCsv, money, number, shanghaiOffset } from "../utils";
import DataTable from "../components/DataTable.vue";
import Notice from "../components/Notice.vue";
import Modal from "../components/Modal.vue";
const mode = ref("calls"),
  page = ref(1),
  size = ref(20),
  rows = ref<(CallLog | UsageRecord)[]>([]),
  total = ref(0),
  detail = ref<CallLog | UsageRecord>(),
  filters = ref({
    from: "",
    to: "",
    model: "",
    apikeyCode: "",
    success: "",
    userCode: "",
    tokenCode: "",
  });
const { busy, error, run } = useTask();
const columns = computed<Column[]>(() =>
  mode.value === "calls"
    ? [
        { key: "model", label: "模型" },
        { key: "llmApikeyCode", label: "上游密钥" },
        { key: "success", label: "结果" },
        { key: "firstTokenMs", label: "TTFT / ms" },
        { key: "totalDurationMs", label: "耗时 / ms" },
        { key: "createTime", label: "时间", format: date },
      ]
    : [
        { key: "model", label: "模型" },
        { key: "userCode", label: "用户" },
        { key: "tokenCode", label: "令牌" },
        { key: "totalTokens", label: "Tokens", format: number },
        { key: "amountFen", label: "金额", format: money },
        { key: "createTime", label: "时间", format: date },
      ],
);
async function load(reset = false) {
  if (reset) page.value = 1;
  await run(async () => {
    const f = filters.value;
    if (f.from && f.to && f.from >= f.to)
      throw new Error("结束时间需要晚于开始时间");
    const params = {
      page: page.value,
      pageSize: size.value,
      from: shanghaiOffset(f.from),
      to: shanghaiOffset(f.to),
      model: f.model,
      ...(mode.value === "calls"
        ? { apikeyCode: f.apikeyCode, success: f.success }
        : { userCode: f.userCode, tokenCode: f.tokenCode }),
    };
    const data = await admin<Page<CallLog | UsageRecord>>(
      `/admin/${mode.value === "calls" ? "llm/call-logs" : "usage/records"}?${query(params)}`,
    );
    rows.value = data.items;
    total.value = data.total;
    page.value = data.page;
    size.value = data.pageSize;
  });
}
function switchMode(next: string) {
  if (busy.value) return;
  mode.value = next;
  rows.value = [];
  total.value = 0;
  void load(true);
}
function exportPage() {
  exportCsv(
    rows.value.map((row) => ({ ...row })),
    `${mode.value}-page-${page.value}.csv`,
  );
}
function clear() {
  filters.value = {
    from: "",
    to: "",
    model: "",
    apikeyCode: "",
    success: "",
    userCode: "",
    tokenCode: "",
  };
  void load(true);
}
onMounted(() => load());
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">OBSERVABILITY</p>
      <h1>日志与用量</h1>
      <p>追踪上游调用表现，核对 Token 消耗与计费记录。</p>
    </div>
    <button :disabled="busy || !rows.length" @click="exportPage">
      导出当前页 CSV
    </button>
  </div>
  <div class="tabs">
    <button
      :class="{ active: mode === 'calls' }"
      :disabled="busy"
      @click="switchMode('calls')"
    >
      上游调用日志</button
    ><button
      :class="{ active: mode === 'usage' }"
      :disabled="busy"
      @click="switchMode('usage')"
    >
      用量账单
    </button>
  </div>
  <div class="panel">
    <form class="filters" @submit.prevent="load(true)">
      <label
        >开始时间（上海）<input
          v-model="filters.from"
          type="datetime-local" /></label
      ><label
        >结束时间（上海）<input
          v-model="filters.to"
          type="datetime-local" /></label
      ><label
        >模型<input
          v-model="filters.model"
          placeholder="模型名称，精确匹配" /></label
      ><template v-if="mode === 'calls'"
        ><label
          >上游密钥编码<input
            v-model="filters.apikeyCode"
            placeholder="全部上游密钥" /></label
        ><label
          >调用结果<select v-model="filters.success">
            <option value="">全部</option>
            <option value="true">成功</option>
            <option value="false">失败</option>
          </select></label
        ></template
      ><template v-else
        ><label
          >用户编码<input
            v-model="filters.userCode"
            placeholder="全部用户" /></label
        ><label
          >令牌编码<input
            v-model="filters.tokenCode"
            placeholder="全部令牌" /></label
      ></template>
      <div class="toolbar">
        <button class="primary" :disabled="busy">查询</button
        ><button type="button" :disabled="busy" @click="clear">清空</button>
      </div>
    </form>
    <p class="filter-hint">
      时间留空时查询最近 24 小时；范围包含开始时间，不包含结束时间。
    </p>
    <Notice :error="error" :loading="busy" /><DataTable
      :columns="columns"
      :rows="rows"
      :loading="busy"
      ><template #actions="{ row }"
        ><button @click="detail = row">详情</button></template
      ></DataTable
    >
    <div class="pagination">
      <span
        >共 {{ number(total) }} 条 · 第 {{ page }} /
        {{ Math.max(1, Math.ceil(total / size)) }} 页</span
      >
      <div class="toolbar">
        <select
          v-model.number="size"
          :disabled="busy"
          aria-label="每页条数"
          @change="load(true)"
        >
          <option :value="20">20 条 / 页</option>
          <option :value="50">50 条 / 页</option>
          <option :value="100">100 条 / 页</option></select
        ><button
          :disabled="busy || page === 1"
          @click="
            page--;
            load();
          "
        >
          上一页</button
        ><button
          :disabled="busy || page * size >= total"
          @click="
            page++;
            load();
          "
        >
          下一页
        </button>
      </div>
    </div>
  </div>
  <p class="footnote">
    上游日志显示实际记录的成功/失败结果。用量账单显示已入账金额，两类记录不做未经关联的拼接。
  </p>
  <Modal
    v-if="detail"
    :title="mode === 'calls' ? '上游调用详情' : '用量账单详情'"
    @close="detail = undefined"
  >
    <pre class="json-output">{{ JSON.stringify(detail, null, 2) }}</pre>
  </Modal>
</template>
