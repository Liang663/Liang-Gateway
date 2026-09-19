<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { admin } from "../api";
import type { Resource } from "../resources";
import { useTask } from "../useTask";
import DataTable from "./DataTable.vue";
import Modal from "./Modal.vue";
import FormFields from "./FormFields.vue";
import Notice from "./Notice.vue";
const props = defineProps<{ resource: Resource; compact?: boolean }>();
const emit = defineEmits<{ loaded: [rows: any[]] }>();
const rows = ref<any[]>([]),
  search = ref(""),
  editor = ref(false),
  editing = ref(""),
  form = ref<Record<string, any>>({}),
  removeTarget = ref<any>(null);
const { busy, error, success, run } = useTask();
const filtered = computed(() =>
  rows.value.filter((r) =>
    [r.name, r.code, r.path, r.provider, r.baseUrl]
      .filter(Boolean)
      .join(" ")
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
async function load() {
  rows.value = await admin<any[]>(props.resource.path);
  emit("loaded", rows.value);
}
function refresh() {
  return run(load);
}
watch(
  () => props.resource.path,
  () => {
    rows.value = [];
    search.value = "";
    void refresh();
  },
  { immediate: true },
);
function edit(row?: any) {
  error.value = "";
  success.value = "";
  editing.value = row?.code || "";
  const source = row
    ? props.resource.decode?.(row) || row
    : props.resource.defaults;
  form.value = Object.fromEntries(
    props.resource.fields.map((f) => [
      f.key,
      source[f.key] ?? props.resource.defaults[f.key] ?? "",
    ]),
  );
  editor.value = true;
}
async function save() {
  await run(async () => {
    const body =
      props.resource.encode?.(form.value, !!editing.value) ?? form.value;
    await admin(
      props.resource.path +
        (editing.value ? `/${encodeURIComponent(editing.value)}` : ""),
      editing.value ? "PUT" : "POST",
      body,
    );
    editor.value = false;
    form.value = {};
    await load();
  }, "保存成功");
}
async function toggle(row: any) {
  await run(async () => {
    const body =
      props.resource.path === "/admin/users"
        ? { name: row.name, authority: row.authority, enabled: !row.enabled }
        : { enabled: !row.enabled };
    await admin(
      `${props.resource.path}/${encodeURIComponent(row.code)}`,
      "PUT",
      body,
    );
    await load();
  }, "状态已更新");
}
async function remove() {
  await run(async () => {
    await admin(
      `${props.resource.path}/${encodeURIComponent(removeTarget.value.code)}`,
      "DELETE",
    );
    removeTarget.value = null;
    await load();
  }, "已删除");
}
defineExpose({ refresh });
</script>
<template>
  <section :class="compact ? 'subsection' : 'page-section'">
    <div class="page-heading">
      <div>
        <p v-if="!compact" class="eyebrow">CONFIGURATION</p>
        <h1 v-if="!compact">{{ resource.title }}</h1>
        <h2 v-else>{{ resource.title }}</h2>
        <p>{{ resource.description }}</p>
      </div>
      <div class="toolbar">
        <button :disabled="busy" @click="refresh">刷新</button
        ><button class="primary" :disabled="busy" @click="edit()">
          ＋ 新建</button
        ><slot name="toolbar" />
      </div>
    </div>
    <Notice
      :error="editor || removeTarget ? '' : error"
      :success="success"
      :loading="busy && !editor && !removeTarget"
    />
    <div class="panel">
      <div class="panel-toolbar">
        <input
          v-model="search"
          class="search"
          placeholder="搜索名称或编码…"
          aria-label="搜索名称或编码"
        /><span class="muted">{{ filtered.length }} 条记录</span>
      </div>
      <DataTable :columns="resource.columns" :rows="filtered" :loading="busy"
        ><template #actions="{ row }"
          ><slot name="actions" :row="row" /><button
            :disabled="busy"
            @click="edit(row)"
          >
            编辑</button
          ><button :disabled="busy" @click="toggle(row)">
            {{ row.enabled ? "停用" : "启用" }}</button
          ><button
            v-if="resource.deletable"
            :disabled="busy"
            class="danger-text"
            @click="removeTarget = row"
          >
            删除
          </button></template
        ></DataTable
      >
    </div>
    <Modal
      v-if="editor"
      :title="`${editing ? '编辑' : '新建'} · ${resource.title}`"
      :busy="busy"
      @close="
        editor = false;
        form = {};
      "
      ><form @submit.prevent="save">
        <FormFields :fields="resource.fields" :model="form" /><Notice
          :error="error"
        />
        <footer>
          <button
            type="button"
            :disabled="busy"
            @click="
              editor = false;
              form = {};
            "
          >
            取消</button
          ><button class="primary" :disabled="busy">
            {{ busy ? "保存中…" : "保存" }}
          </button>
        </footer>
      </form></Modal
    >
    <Modal
      v-if="removeTarget"
      title="确认删除"
      :busy="busy"
      @close="removeTarget = null"
      ><p>
        确认删除
        {{ removeTarget.name }}？相关配置将由服务端删除，此操作无法在页面撤销。
      </p>
      <Notice :error="error" />
      <footer>
        <button :disabled="busy" @click="removeTarget = null">取消</button
        ><button class="danger" :disabled="busy" @click="remove">
          {{ busy ? "删除中…" : "确认删除" }}
        </button>
      </footer></Modal
    >
  </section>
</template>
