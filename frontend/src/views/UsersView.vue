<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import CrudPanel from "../components/CrudPanel.vue";
import DataTable from "../components/DataTable.vue";
import Modal from "../components/Modal.vue";
import FormFields from "../components/FormFields.vue";
import Notice from "../components/Notice.vue";
import { userResource } from "../resources";
import { admin } from "../api";
import { useTask } from "../useTask";
import { date, integer, money } from "../utils";
import type { User, Token, Model, Quota, Field } from "../types";
const selected = ref<User>(),
  tokens = ref<Token[]>([]),
  models = ref<Model[]>([]),
  editor = ref(false),
  editing = ref(""),
  form = ref<Record<string, any>>({}),
  quota = ref<Quota>(),
  quotaToken = ref<Token>(),
  resetLayer = ref(""),
  revealed = ref<Token>();
const { busy, error, success, run } = useTask();
const base = computed(
  () => `/admin/users/${encodeURIComponent(selected.value?.code || "")}/tokens`,
);
const fields: Field[] = [
  {
    key: "qpmLimit",
    label: "QPM 上限",
    type: "number",
    min: 0,
    required: true,
  },
  {
    key: "expireTime",
    label: "有效期（上海时间）",
    type: "datetime-local",
    hint: "留空表示长期有效",
  },
  {
    key: "fiveHour",
    label: "5 小时额度（分）",
    type: "number",
    min: 0,
    hint: "留空不设此周期额度；0 表示额度为零",
  },
  {
    key: "week",
    label: "7 天额度（分）",
    type: "number",
    min: 0,
    hint: "两项均留空表示不限周期额度",
  },
  { key: "enabled", label: "启用", type: "checkbox" },
];
const modelError = ref("");
async function loadModels() {
  modelError.value = "";
  try {
    models.value = await admin<Model[]>("/admin/llm/models");
  } catch {
    modelError.value = "模型列表加载失败，请重试后配置令牌的模型权限。";
  }
}
onMounted(loadModels);
async function load() {
  if (selected.value) tokens.value = await admin<Token[]>(base.value);
}
function select(user: User) {
  if (busy.value) return;
  selected.value = user;
  tokens.value = [];
  void run(load);
}
function usersLoaded(users: User[]) {
  if (selected.value) {
    selected.value = users.find((u) => u.code === selected.value?.code);
    if (!selected.value) tokens.value = [];
  }
}
function edit(token?: Token) {
  error.value = "";
  editing.value = token?.code || "";
  form.value = {
    qpmLimit: token?.qpmLimit ?? 60,
    expireTime: token?.expireTime || "",
    enabled: token?.enabled ?? true,
    models: [...(token?.models || [])],
    fiveHour: token?.limits.find((l) => l.limitType === 1)?.usage ?? "",
    week: token?.limits.find((l) => l.limitType === 2)?.usage ?? "",
  };
  editor.value = true;
}
async function save() {
  await run(async () => {
    const f = form.value;
    const limits = [
      { limitType: 1, usage: f.fiveHour },
      { limitType: 2, usage: f.week },
    ]
      .filter((l) => l.usage !== "")
      .map((l) => ({ ...l, usage: integer(l.usage, "周期额度") }));
    await admin(
      base.value +
        (editing.value ? `/${encodeURIComponent(editing.value)}` : ""),
      editing.value ? "PUT" : "POST",
      {
        qpmLimit: integer(f.qpmLimit, "QPM"),
        expireTime: f.expireTime || null,
        enabled: f.enabled,
        models: f.models,
        limits: limits.length ? limits : [{ limitType: 0, usage: 0 }],
      },
    );
    editor.value = false;
    form.value = {};
    await load();
  }, "令牌已保存");
}
async function toggle(token: Token) {
  await run(async () => {
    await admin(`${base.value}/${encodeURIComponent(token.code)}`, "PUT", {
      qpmLimit: token.qpmLimit,
      enabled: !token.enabled,
    });
    await load();
  }, "令牌状态已更新");
}
async function showQuota(token: Token) {
  await run(async () => {
    const result = await admin<Quota>(
      `${base.value}/${encodeURIComponent(token.code)}/quota`,
    );
    quotaToken.value = token;
    quota.value = result;
  });
}
async function reset() {
  await run(async () => {
    await admin(
      `${base.value}/${encodeURIComponent(quotaToken.value!.code)}/quota/reset`,
      "POST",
      { layer: resetLayer.value },
    );
    resetLayer.value = "";
    quota.value = await admin<Quota>(
      `${base.value}/${encodeURIComponent(quotaToken.value!.code)}/quota`,
    );
    await load();
  }, "指定周期额度已重置");
}
const quotaRows = computed(() =>
  quota.value
    ? [
        {
          key: "FIVE_HOUR",
          label: "5 小时窗口",
          type: 1,
          data: quota.value.fiveHour,
        },
        { key: "WEEK", label: "7 天窗口", type: 2, data: quota.value.week },
      ]
    : [],
);
const modelNames = computed(() => [
  ...new Set([
    ...models.value.map((m) => m.name),
    ...(form.value.models || []),
  ]),
]);
</script>
<template>
  <Notice :error="modelError" /><button v-if="modelError" @click="loadModels">
    重新加载模型</button
  ><CrudPanel :resource="userResource" @loaded="usersLoaded"
    ><template #actions="{ row }"
      ><button :disabled="busy" class="accent-text" @click="select(row)">
        {{ selected?.code === row.code ? "已选中" : "管理令牌" }}
      </button></template
    ></CrudPanel
  >
  <section v-if="selected" class="subsection">
    <div class="page-heading">
      <div>
        <p class="eyebrow">ACCESS TOKENS</p>
        <h2>{{ selected.name }} 的访问令牌</h2>
        <p>{{ selected.code }} · 模型权限、QPM 与周期限额</p>
      </div>
      <div class="toolbar">
        <button :disabled="busy" @click="run(load)">刷新</button
        ><button class="primary" :disabled="busy" @click="edit()">
          ＋ 创建令牌
        </button>
      </div>
    </div>
    <Notice
      :error="editor || quota ? '' : error"
      :success="success"
      :loading="busy && !editor && !quota"
    />
    <div class="panel">
      <DataTable
        :rows="tokens"
        :columns="[
          { key: 'code', label: '令牌编码' },
          { key: 'qpmLimit', label: 'QPM' },
          {
            key: 'models',
            label: '模型权限',
            format: (v) => (v as string[]).join(', ') || '未授权任何模型',
          },
          { key: 'expireTime', label: '有效期', format: date },
          { key: 'enabled', label: '状态' },
        ]"
        :loading="busy"
        ><template #actions="{ row }"
          ><button :disabled="busy" @click="showQuota(row)">额度</button
          ><button :disabled="busy" @click="edit(row)">编辑</button
          ><button :disabled="busy" @click="toggle(row)">
            {{ row.enabled ? "停用" : "启用" }}</button
          ><button @click="revealed = row">查看密钥</button></template
        ></DataTable
      >
    </div>
  </section>
  <div v-else class="panel empty">
    选择上方用户的“管理令牌”，查看其访问配置。
  </div>
  <Modal
    v-if="editor"
    :title="editing ? '编辑访问令牌' : '创建访问令牌'"
    :busy="busy"
    @close="
      editor = false;
      form = {};
    "
    ><form @submit.prevent="save">
      <FormFields :fields="fields" :model="form" />
      <fieldset class="model-picks">
        <legend>允许调用的模型</legend>
        <small
          >Chat 调用至少需要授权一个模型。留空时不授权任何模型，可用于 MCP
          调用。</small
        ><label v-for="model in modelNames" :key="model"
          ><input v-model="form.models" type="checkbox" :value="model" />{{
            model
          }}</label
        >
      </fieldset>
      <Notice :error="error" />
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
    v-if="quota"
    title="实时周期额度"
    :busy="busy"
    @close="
      quota = undefined;
      resetLayer = '';
    "
  >
    <p class="muted">{{ quotaToken?.code }} · 金额以人民币展示</p>
    <div v-for="entry in quotaRows" :key="entry.key" class="quota-card">
      <h3>{{ entry.label }}</h3>
      <template
        v-if="quotaToken?.limits.some((l) => l.limitType === entry.type)"
        ><strong
          >{{ money(entry.data.used) }}
          <span class="muted">/ {{ money(entry.data.limit) }}</span></strong
        ><progress
          :value="entry.data.used"
          :max="Math.max(1, entry.data.limit)"
        />
        <p class="muted">
          结束时间：{{
            entry.data.windowEnd
              ? new Date(entry.data.windowEnd).toLocaleString("zh-CN", {
                  timeZone: "Asia/Shanghai",
                })
              : "—"
          }}
        </p>
        <button :disabled="busy" @click="resetLayer = entry.key">
          重置此周期
        </button></template
      >
      <p v-else class="muted">未设置此周期额度</p>
    </div>
    <div v-if="resetLayer" class="notice warning">
      <p>
        确认重置{{
          resetLayer === "WEEK" ? "7 天" : "5 小时"
        }}窗口？已用额度将清零。
      </p>
      <button :disabled="busy" @click="resetLayer = ''">取消</button>
      <button class="danger" :disabled="busy" @click="reset">确认重置</button>
    </div>
    <Notice :error="error" />
  </Modal>
  <Modal v-if="revealed" title="访问令牌密钥" @close="revealed = undefined"
    ><p class="notice warning">
      此密钥可用于真实调用，请妥善保管。页面关闭后不保存副本。
    </p>
    <input
      :value="revealed.accessToken"
      readonly
      class="mono"
      aria-label="访问令牌密钥"
      @focus="($event.target as HTMLInputElement).select()"
    />
    <p class="muted">
      选中后使用 Ctrl+C 复制。调试页需要单独输入此令牌。
    </p></Modal
  >
</template>
