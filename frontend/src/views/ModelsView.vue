<script setup lang="ts">
import { onMounted, ref, computed } from "vue";
import { admin, errorText } from "../api";
import type { ApiKey } from "../types";
import { modelResource } from "../resources";
import CrudPanel from "../components/CrudPanel.vue";
import Notice from "../components/Notice.vue";
const keys = ref<ApiKey[]>([]),
  error = ref(""),
  ready = ref(false);
const resource = computed(() => modelResource(keys.value));
async function load() {
  error.value = "";
  try {
    keys.value = await admin<ApiKey[]>("/admin/llm/apikeys");
    ready.value = true;
  } catch (e) {
    error.value = errorText(e);
  }
}
onMounted(load);
</script>
<template>
  <Notice :error="error" /><button v-if="error" @click="load">
    重试加载上游密钥</button
  ><CrudPanel v-if="ready" :resource="resource"
    ><template #actions="{ row }"
      ><RouterLink
        :to="{ path: '/playground', query: { model: row.name } }"
        class="button-link"
        >调试</RouterLink
      ></template
    ></CrudPanel
  >
</template>
