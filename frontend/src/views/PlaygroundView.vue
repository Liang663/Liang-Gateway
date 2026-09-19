<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, computed, watch } from "vue";
import { useRoute } from "vue-router";
import { admin, dataRequest, errorText } from "../api";
import { readSse } from "../sse";
import { mcpRequest, MCP_VERSION } from "../mcp";
import { parseJson } from "../utils";
import type { Model, Server } from "../types";
import Notice from "../components/Notice.vue";
import Modal from "../components/Modal.vue";
const route = useRoute(),
  mode = ref(route.query.server ? "mcp" : "chat"),
  token = ref(""),
  models = ref<Model[]>([]),
  servers = ref<Server[]>([]),
  model = ref(String(route.query.model || "")),
  server = ref(String(route.query.server || ""));
const prompt = ref(""),
  output = ref(""),
  usage = ref<unknown>(),
  busy = ref(false),
  loading = ref(false),
  error = ref(""),
  status = ref("尚未开始"),
  confirmed = ref(false),
  confirmCall = ref(false),
  argumentsText = ref("{}"),
  toolName = ref(""),
  tools = ref<{ name: string; description?: string; inputSchema?: unknown }[]>(
    [],
  );
let controller: AbortController | undefined;
const selectedTool = computed(() =>
  tools.value.find((t) => t.name === toolName.value),
);
watch([server, token], () => {
  tools.value = [];
  toolName.value = "";
  output.value = "";
  status.value = "尚未开始";
});
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [m, s] = await Promise.all([
      admin<Model[]>("/admin/llm/models"),
      admin<Server[]>("/admin/mcp/servers"),
    ]);
    models.value = m.filter((i) => i.enabled);
    servers.value = s.filter((i) => i.enabled);
    model.value ||= models.value[0]?.name || "";
    server.value ||= servers.value[0]?.path || "";
  } catch (e) {
    error.value = errorText(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
onBeforeUnmount(() => {
  controller?.abort();
  token.value = "";
});
function stop() {
  controller?.abort();
}
async function execute(task: () => Promise<void>) {
  if (busy.value) return;
  if (!token.value.trim()) {
    error.value = "请先输入用户访问令牌";
    return;
  }
  if (!confirmed.value) {
    error.value = "请确认真实调用提示";
    return;
  }
  busy.value = true;
  error.value = "";
  status.value = "请求中…";
  controller = new AbortController();
  try {
    await task();
    status.value = "已完成";
  } catch (e) {
    if (controller.signal.aborted)
      status.value = "已停止，计费以服务端最终记录为准";
    else {
      error.value = errorText(e);
      status.value = "调用失败";
    }
  } finally {
    busy.value = false;
    controller = undefined;
  }
}
async function chat() {
  await execute(async () => {
    if (!model.value || !prompt.value.trim())
      throw new Error("请选择模型并填写消息");
    output.value = "";
    usage.value = undefined;
    let completed = false;
    const response = await dataRequest(
      "/v1/chat/completions",
      token.value,
      {
        model: model.value,
        stream: true,
        stream_options: { include_usage: true },
        messages: [{ role: "user", content: prompt.value }],
      },
      controller?.signal,
      { Accept: "text/event-stream" },
    );
    await readSse(response, (data) => {
      if (data === "[DONE]") {
        completed = true;
        return;
      }
      const event = JSON.parse(data);
      if (event.error) throw new Error(event.error.message || "流式调用失败");
      const delta = event.choices?.[0]?.delta;
      output.value += (delta?.reasoning_content || "") + (delta?.content || "");
      if (event.usage) usage.value = event.usage;
      status.value = "正在接收…";
    });
    if (!completed)
      throw new Error("响应流在结束标记前断开，请核对日志与计费记录");
  });
}
async function discover() {
  await execute(async () => {
    if (!server.value) throw new Error("请选择 MCP 服务");
    output.value = "";
    const discovery = await mcpRequest(
      server.value,
      token.value,
      "server/discover",
      {},
      controller?.signal,
    );
    const result = await mcpRequest(
      server.value,
      token.value,
      "tools/list",
      {},
      controller?.signal,
    );
    tools.value = result.tools || [];
    toolName.value = tools.value[0]?.name || "";
    output.value = JSON.stringify({ discovery, tools: result.tools }, null, 2);
  });
}
async function callTool() {
  confirmCall.value = false;
  await execute(async () => {
    const args = parseJson(argumentsText.value, "object", "调用参数");
    output.value = "";
    if (!toolName.value) throw new Error("请先发现并选择工具");
    const result = await mcpRequest(
      server.value,
      token.value,
      "tools/call",
      { name: toolName.value, arguments: args },
      controller?.signal,
    );
    output.value = JSON.stringify(result, null, 2);
    if (result.isError) throw new Error("工具返回执行失败，详见右侧结果");
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <p class="eyebrow">PLAYGROUND</p>
      <h1>在线调试</h1>
      <p>通过网关验证真实模型调用与 MCP 工具链路。</p>
    </div>
    <span class="badge" :class="busy ? 'green' : 'gray'">{{ status }}</span>
  </div>
  <div class="tabs">
    <button
      :disabled="busy"
      :class="{ active: mode === 'chat' }"
      @click="
        mode = 'chat';
        output = '';
        error = '';
        usage = undefined;
      "
    >
      Chat 流式调用</button
    ><button
      :disabled="busy"
      :class="{ active: mode === 'mcp' }"
      @click="
        mode = 'mcp';
        output = '';
        error = '';
        usage = undefined;
      "
    >
      MCP 工具调用
    </button>
  </div>
  <Notice :error="error" :loading="loading" />
  <div class="play-grid">
    <section class="panel play-controls">
      <h2>请求配置</h2>
      <label
        >用户访问令牌<input
          v-model="token"
          :disabled="busy"
          type="password"
          autocomplete="off"
          placeholder="输入访问令牌，独立于管理密钥" /></label
      ><label class="check-line"
        ><input
          v-model="confirmed"
          :disabled="busy"
          type="checkbox"
        />我理解真实调用可能产生费用或业务操作</label
      >
      <template v-if="mode === 'chat'"
        ><form @submit.prevent="chat">
          <label
            >模型<select v-model="model" :disabled="busy" required>
              <option v-for="m in models" :key="m.code" :value="m.name">
                {{ m.name }}
              </option>
            </select></label
          ><label
            >消息<textarea
              v-model="prompt"
              :disabled="busy"
              required
              rows="8"
              placeholder="输入希望发送给模型的消息…"
            /></label
          ><button class="primary" :disabled="busy || loading || !confirmed">
            发送请求 →
          </button>
        </form></template
      >
      <template v-else
        ><label
          >MCP 服务<select v-model="server" :disabled="busy">
            <option v-for="s in servers" :key="s.code" :value="s.path">
              {{ s.name }} · /mcp/{{ s.path }}
            </option>
          </select></label
        ><button :disabled="busy || loading || !confirmed" @click="discover">
          发现服务与工具</button
        ><label
          >工具<select
            v-model="toolName"
            :disabled="busy"
            aria-label="选择 MCP 工具"
          >
            <option v-for="tool in tools" :key="tool.name" :value="tool.name">
              {{ tool.name }}
            </option>
          </select></label
        >
        <p class="muted">{{ selectedTool?.description }}</p>
        <details v-if="selectedTool?.inputSchema">
          <summary>查看输入 Schema</summary>
          <pre class="json-output">{{
            JSON.stringify(selectedTool.inputSchema, null, 2)
          }}</pre>
        </details>
        <label
          >调用参数 JSON<textarea
            v-model="argumentsText"
            class="mono"
            rows="6"
            :disabled="busy"
          /></label
        ><button
          class="primary"
          :disabled="busy || !toolName || !confirmed"
          @click="confirmCall = true"
        >
          调用工具 →
        </button>
        <p class="footnote">
          协议 {{ MCP_VERSION }} · 无状态 server/discover
        </p></template
      >
      <button v-if="busy" class="danger" @click="stop">停止请求</button
      ><button v-if="error && !busy" @click="load">重新加载配置</button>
    </section>
    <section class="panel response-panel">
      <div class="panel-heading">
        <div>
          <h2>响应结果</h2>
          <p>{{ mode === "chat" ? "SSE 增量输出" : "JSON-RPC 工具响应" }}</p>
        </div>
        <span class="status-dot" :class="{ pulsing: busy }" />
      </div>
      <pre v-if="output" class="response-output">{{ output }}</pre>
      <div v-else class="response-empty">
        <span>⌁</span>
        <h3>等待一次新的调用</h3>
        <p>填写左侧配置，结果将在这里显示。</p>
      </div>
      <pre v-if="usage" class="usage-output">{{
        JSON.stringify(usage, null, 2)
      }}</pre>
    </section>
  </div>
  <p class="footnote">
    调试凭据仅保存在当前页面。停止读取后，请以服务端最终记账为准。MCP 返回 403
    时，请检查 gateway.mcp.allowed-origins 是否包含当前页面来源。
  </p>
  <Modal
    v-if="confirmCall"
    title="确认调用 MCP 工具"
    @close="confirmCall = false"
    ><p>
      即将执行 {{ toolName }}，可能修改上游业务数据。请确认参数与目标服务。
    </p>
    <pre class="json-output">{{ argumentsText }}</pre>
    <footer>
      <button @click="confirmCall = false">取消</button
      ><button class="primary" @click="callTool">确认调用</button>
    </footer></Modal
  >
</template>
