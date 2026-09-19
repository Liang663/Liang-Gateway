<script setup lang="ts">
import type { Column } from "../types";
defineProps<{ columns: Column[]; rows: any[]; loading?: boolean }>();
</script>
<template>
  <div class="table-wrap" :aria-busy="loading">
    <table>
      <thead>
        <tr>
          <th v-for="c in columns" :key="c.key">{{ c.label }}</th>
          <th v-if="$slots.actions" class="actions-col">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!rows.length">
          <td
            :colspan="columns.length + ($slots.actions ? 1 : 0)"
            class="empty"
          >
            {{ loading ? "正在加载…" : "暂无数据" }}
          </td>
        </tr>
        <tr v-for="(row, index) in rows" :key="row.code || index">
          <td v-for="c in columns" :key="c.key">
            <span
              v-if="c.key === 'enabled' || c.key === 'success'"
              class="badge"
              :class="row[c.key] ? 'green' : 'gray'"
              >{{
                c.key === "enabled"
                  ? row[c.key]
                    ? "已启用"
                    : "已停用"
                  : row[c.key]
                    ? "成功"
                    : "失败"
              }}</span
            ><template v-else>{{
              c.format ? c.format(row[c.key]) : (row[c.key] ?? "—")
            }}</template>
          </td>
          <td v-if="$slots.actions">
            <div class="row-actions"><slot name="actions" :row="row" /></div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
