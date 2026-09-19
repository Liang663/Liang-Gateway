<script setup lang="ts">
import { computed, ref } from "vue";
import CrudPanel from "../components/CrudPanel.vue";
import Modal from "../components/Modal.vue";
import Notice from "../components/Notice.vue";
import { serverResource, toolResource } from "../resources";
import type { Server } from "../types";
import { parseJson } from "../utils";
import { admin, errorText } from "../api";
import { useTask } from "../useTask";
const selected = ref<Server>(),
  importing = ref(false),
  source = ref(""),
  document = ref<Record<string, any>>(),
  paths = ref<string[]>([]),
  chosen = ref<string[]>([]),
  toolsPanel = ref<InstanceType<typeof CrudPanel>>();
const resource = computed(() => toolResource(selected.value?.code || ""));
const { busy, error, success, run } = useTask();
function select(server: Server) {
  selected.value = server;
}
function serversLoaded(servers: Server[]) {
  if (selected.value)
    selected.value = servers.find((s) => s.code === selected.value?.code);
}
function openImport() {
  error.value = "";
  source.value = "";
  document.value = undefined;
  paths.value = [];
  chosen.value = [];
  importing.value = true;
}
function clearParsed() {
  document.value = undefined;
  paths.value = [];
  chosen.value = [];
}
function parse() {
  try {
    document.value = parseJson(source.value, "object", "OpenAPI 文档");
    const value = document.value!.paths;
    if (!value || typeof value !== "object" || Array.isArray(value))
      throw new Error("文档需要 paths 对象");
    paths.value = Object.keys(value);
    chosen.value = [];
    error.value = "";
  } catch (e) {
    clearParsed();
    error.value = errorText(e);
  }
}
async function fileInput(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) {
    error.value = "文档大小限制为 5 MB";
    return;
  }
  source.value = await file.text();
  parse();
}
async function importTools() {
  await run(async () => {
    if (!chosen.value.length) throw new Error("请至少选择一个接口路径");
    await admin(
      `/admin/mcp/servers/${encodeURIComponent(selected.value!.code)}/tools:import`,
      "POST",
      { swagger: document.value, paths: chosen.value },
    );
    importing.value = false;
    clearParsed();
    source.value = "";
    await toolsPanel.value?.refresh();
  }, "工具导入成功");
}
</script>
<template>
  <CrudPanel :resource="serverResource" @loaded="serversLoaded"
    ><template #actions="{ row }"
      ><button class="accent-text" @click="select(row)">
        {{ selected?.code === row.code ? "已选中" : "管理工具" }}</button
      ><RouterLink
        class="button-link"
        :to="{ path: '/playground', query: { server: row.path } }"
        >调试</RouterLink
      ></template
    ></CrudPanel
  >
  <Notice :success="success" />
  <CrudPanel
    v-if="selected"
    :key="selected.code"
    ref="toolsPanel"
    :resource="resource"
    compact
    ><template #toolbar
      ><button :disabled="busy" @click="openImport">
        导入 OpenAPI
      </button></template
    ></CrudPanel
  >
  <div v-else class="panel empty">
    选择一个 MCP 服务，管理 HTTP 工具与 OpenAPI 导入。
  </div>
  <Modal
    v-if="importing"
    :title="`导入 OpenAPI · ${selected?.name}`"
    :busy="busy"
    @close="
      importing = false;
      source = '';
      clearParsed();
    "
    ><form @submit.prevent="importTools">
      <p class="muted">
        选择路径后，将导入路径下支持的 GET / POST / PUT / DELETE
        操作。解析与持久化由网关完成。
      </p>
      <label
        >上传 JSON 文档<input
          type="file"
          accept=".json,application/json"
          :disabled="busy"
          @change="fileInput" /></label
      ><label
        >或粘贴 OpenAPI JSON<textarea
          v-model="source"
          rows="8"
          class="mono"
          required
          :disabled="busy"
          @input="clearParsed"
        /></label
      ><button type="button" :disabled="busy || !source" @click="parse">
        解析并选择接口
      </button>
      <fieldset v-if="document" class="model-picks">
        <legend>接口路径（{{ paths.length }}）</legend>
        <label v-for="path in paths" :key="path"
          ><input v-model="chosen" type="checkbox" :value="path" />{{
            path
          }}</label
        >
        <p v-if="!paths.length" class="muted">没有可导入的路径</p>
      </fieldset>
      <Notice :error="error" />
      <footer>
        <button
          type="button"
          :disabled="busy"
          @click="
            importing = false;
            source = '';
            clearParsed();
          "
        >
          取消</button
        ><button
          class="primary"
          :disabled="busy || !document || !chosen.length"
        >
          {{ busy ? "导入中…" : `导入 ${chosen.length} 个路径` }}
        </button>
      </footer>
    </form></Modal
  >
</template>
