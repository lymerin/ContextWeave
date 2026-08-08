<template>
  <header class="linked-workbench-header">
    <span class="header-accent" aria-hidden="true" />
    <div class="linked-heading">
      <p class="eyebrow">COLLABORATION / WORKSPACE</p>
      <h1>{{ workspaceName || '工程关联工作台' }}</h1>
      <p>{{ repositoryName || '未选择仓库' }} · {{ branch || '-' }} · {{ shortCommit }}</p>
    </div>
    <div class="linked-header-actions">
      <div class="mode-switcher" role="group" aria-label="工作台模式">
        <button
          v-for="item in modes"
          :key="item.value"
          type="button"
          :class="{ 'is-active': mode === item.value }"
          @click="emit('set-mode', item.value)"
        >{{ item.label }}</button>
      </div>
      <el-button class="inspector-toggle" size="small" @click="emit('toggle-inspector')">
        {{ inspectorOpen ? '收起问题与证据' : '问题与证据' }}
      </el-button>
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { WorkbenchMode } from '@/types/linkedWorkbench';

const props = defineProps<{
  workspaceName?: string;
  repositoryName?: string;
  branch?: string;
  commitSha?: string | null;
  mode: WorkbenchMode;
  inspectorOpen: boolean;
}>();

const emit = defineEmits<{
  'set-mode': [mode: WorkbenchMode];
  'toggle-inspector': [];
}>();

const shortCommit = computed(() => props.commitSha?.slice(0, 10) || '尚无 Commit');
const modes: Array<{ value: WorkbenchMode; label: string }> = [
  { value: 'LINKED', label: '关联对照' },
  { value: 'CODE_FOCUS', label: '代码聚焦' },
  { value: 'DOCUMENT_FOCUS', label: '文档聚焦' },
];
</script>

<style scoped>
.linked-workbench-header { position: relative; display: flex; min-width: 0; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px 18px; padding: 15px 18px 15px 22px; border-bottom: 1px solid #e3e8ed; background: rgba(255, 255, 255, .96); }
.header-accent { position: absolute; top: 18px; bottom: 18px; left: 0; width: 3px; border-radius: 0 999px 999px 0; background: linear-gradient(180deg, rgba(23, 214, 244, .25), #12c9eb 46%, rgba(19, 174, 214, .3)); box-shadow: 0 0 12px rgba(18, 201, 235, .34); }
.linked-heading { min-width: 220px; flex: 1 1 240px; }
.linked-heading h1 { margin: 3px 0 4px; color: #111820; font-size: 22px; letter-spacing: -.02em; }
.linked-heading p:last-child { overflow: hidden; margin: 0; color: #667085; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.linked-header-actions { display: flex; min-width: 0; flex: 1 1 620px; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 8px; }
.mode-switcher { display: flex; flex: 0 0 auto; gap: 3px; overflow: hidden; padding: 3px; border: 1px solid #e1e6eb; border-radius: 9px; background: #f3f5f7; }
.mode-switcher button { flex: 0 0 auto; border: 0; border-radius: 6px; padding: 7px 11px; background: transparent; color: #66717d; white-space: nowrap; cursor: pointer; transition: background .18s ease, color .18s ease, box-shadow .18s ease; }
.mode-switcher button:last-child { border-right: 0; }
.mode-switcher button:hover { color: #0a1118; }
.mode-switcher button.is-active { background: #0c1116; color: #fff; box-shadow: 0 5px 14px rgba(5, 13, 20, .16); }
.mode-switcher button.is-active::after { display: inline-block; width: 5px; height: 5px; margin-left: 7px; border-radius: 50%; background: #18d7f2; content: ''; vertical-align: 2px; }
.inspector-toggle:hover { border-color: rgba(20, 190, 225, .36); box-shadow: 3px 4px 12px rgba(17, 199, 235, .15); }
@media (max-width: 1100px) { .linked-workbench-header { align-items: flex-start; flex-direction: column; } .linked-header-actions { width: 100%; flex-wrap: wrap; justify-content: flex-start; } }
</style>
