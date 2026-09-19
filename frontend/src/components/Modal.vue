<script setup lang="ts">
import { onMounted, ref } from "vue";
defineProps<{ title: string; busy?: boolean }>();
const emit = defineEmits<{ close: [] }>(),
  dialog = ref<HTMLDialogElement>();
onMounted(() => dialog.value?.showModal());
</script>
<template>
  <dialog
    ref="dialog"
    class="modal"
    aria-labelledby="modal-title"
    @cancel.prevent="!busy && emit('close')"
  >
    <header>
      <h2 id="modal-title">{{ title }}</h2>
      <button
        type="button"
        :disabled="busy"
        aria-label="关闭"
        @click="emit('close')"
      >
        ×
      </button>
    </header>
    <slot />
  </dialog>
</template>
