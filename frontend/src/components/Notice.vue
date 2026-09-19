<script setup lang="ts">
defineProps<{ error?: string; success?: string; loading?: boolean }>();
</script>
<template>
  <p v-if="error" role="alert" class="notice error">{{ error }}</p>
  <p v-if="success" role="status" class="notice success">{{ success }}</p>
  <p v-if="loading" role="status" class="loading">正在加载数据…</p>
</template>
