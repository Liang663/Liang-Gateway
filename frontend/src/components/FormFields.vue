<script setup lang="ts">
import type { Field } from "../types";
defineProps<{ fields: Field[]; model: Record<string, any> }>();
</script>
<template>
  <div class="form-grid">
    <label
      v-for="f in fields"
      :key="f.key"
      :class="{ wide: f.type === 'json' || f.type === 'textarea' }"
      ><span>{{ f.label }} <b v-if="f.required" class="required">*</b></span>
      <textarea
        v-if="f.type === 'json' || f.type === 'textarea'"
        v-model="model[f.key]"
        :required="f.required"
        :rows="f.type === 'json' ? 5 : 3"
        :class="{ mono: f.type === 'json' }"
        spellcheck="false"
      />
      <select
        v-else-if="f.type === 'select'"
        v-model="model[f.key]"
        :required="f.required"
      >
        <option value="">请选择</option>
        <option
          v-for="option in f.options"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </option>
      </select>
      <input
        v-else-if="f.type === 'checkbox'"
        v-model="model[f.key]"
        type="checkbox"
      />
      <input
        v-else
        v-model="model[f.key]"
        :type="f.type || 'text'"
        :required="f.required"
        :min="f.min"
        :step="
          f.step ||
          (f.type === 'number'
            ? '1'
            : f.type === 'datetime-local'
              ? 'any'
              : undefined)
        "
        :autocomplete="f.type === 'password' ? 'new-password' : 'off'"
      />
      <small v-if="f.hint">{{ f.hint }}</small></label
    >
  </div>
</template>
