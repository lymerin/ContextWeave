<template>
  <section class="review-code-pane">
    <header class="code-pane-header">
      <div>
        <p>BOUND CODE</p>
        <strong>绑定代码</strong>
      </div>
      <span v-if="binding" class="binding-state">已自动复用 Binding</span>
      <span v-else-if="hasContent" class="binding-state is-fallback">{{ fallbackLabel }}</span>
    </header>

    <div v-if="hasContent" class="code-filebar">
      <strong :title="displayPath">{{ fileName }}</strong>
    </div>

    <el-skeleton v-if="loading" class="code-loading" :rows="10" animated />
    <div v-else-if="!hasContent" class="code-empty">
      <span aria-hidden="true">／</span>
      <strong>未找到正式代码绑定</strong>
      <p>当前变更没有可复用的 Binding 或提交证据</p>
    </div>
    <div v-else class="evidence-content" role="list" aria-label="自动定位的绑定代码">
      <div
        v-for="(line, index) in visibleLines"
        :key="`${visibleStartLine + index}:${line}`"
        class="evidence-line"
        :class="{ 'is-bound': isBoundLine(visibleStartLine + index) }"
      >
        <span>{{ visibleStartLine + index }}</span>
        <code>{{ line || ' ' }}</code>
      </div>
    </div>

    <footer v-if="hasContent" class="code-binding-note">
      <span aria-hidden="true">01</span>
      <p>{{ bindingNote }}</p>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import type { DocumentChangeEvidence } from '@/api/documentChange';
import type { CodeBindingContextItem } from '@/api/git';

const props = defineProps<{
  evidence: DocumentChangeEvidence | null;
  binding?: CodeBindingContextItem | null;
  loading?: boolean;
  sourceContent?: string | null;
  sourcePath?: string;
  sourceCommit?: string | null;
  sourceRepository?: string;
  sourceMode?: 'binding' | 'evidence' | 'repository-default' | null;
}>();

const hasSource = computed(() => props.sourceContent != null);
const hasContent = computed(() => hasSource.value || Boolean(props.evidence?.excerptText));
const displayPath = computed(() => props.sourcePath || props.binding?.matchedFilePath || props.evidence?.filePath || '');
const fileName = computed(() => displayPath.value.split(/[\\/]/).pop() || displayPath.value);
const allLines = computed(() => (
  hasSource.value ? props.sourceContent : props.evidence?.excerptText
)?.split(/\r?\n/) ?? []);
const bindingStart = computed(() => props.binding?.startLine ?? props.evidence?.startLine ?? 1);
const bindingEnd = computed(() => props.binding?.endLine ?? props.evidence?.endLine ?? bindingStart.value);
const visibleStartLine = computed(() => {
  if (!hasSource.value) return props.evidence?.startLine ?? 1;
  return Math.max(1, bindingStart.value - 4);
});
const visibleEndLine = computed(() => {
  if (!hasSource.value) return visibleStartLine.value + allLines.value.length - 1;
  return Math.min(allLines.value.length, Math.max(bindingEnd.value + 5, visibleStartLine.value + 18));
});
const visibleLines = computed(() => hasSource.value
  ? allLines.value.slice(visibleStartLine.value - 1, visibleEndLine.value)
  : allLines.value);
const bindingNote = computed(() => props.binding
  ? '代码由当前 Block 的正式 Binding 自动定位，评审者无需再次选择 Evidence。'
  : props.sourceMode === 'repository-default'
    ? '当前变更没有可用 Binding，已自动打开仓库中的第一个可读文件。'
    : '正式 Binding 暂不可用，当前显示提交时保存的可信 Evidence 代码片段。');
const fallbackLabel = computed(() => props.sourceMode === 'repository-default'
  ? '默认首个代码文件'
  : 'Evidence 回退');

function isBoundLine(lineNumber: number) {
  if (props.binding?.startLine == null || props.binding?.endLine == null) return true;
  return lineNumber >= props.binding.startLine && lineNumber <= props.binding.endLine;
}
</script>

<style scoped>
.review-code-pane {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: 64px auto minmax(0, 1fr) auto;
  border-right: 1px solid #e3eaec;
  background: #fbfcfc;
}

.code-pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 18px;
  border-bottom: 1px solid #e3eaec;
  background: #fff;
}

.code-pane-header div { display: grid; gap: 4px; }
.code-pane-header p {
  margin: 0;
  color: #168ba5;
  font: 600 9px/1 "IBM Plex Mono", Consolas, monospace;
  letter-spacing: .14em;
}
.code-pane-header strong { font-size: 14px; }

.binding-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #167b63;
  font-size: 10px;
  white-space: nowrap;
}
.binding-state::before {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #21c891;
  box-shadow: 0 0 0 4px #e7faf3;
  content: "";
}
.binding-state.is-fallback { color: #7b858a; }
.binding-state.is-fallback::before { background: #9aa4a8; box-shadow: 0 0 0 4px #eef1f2; }

.code-filebar {
  display: flex;
  min-height: 44px;
  align-items: center;
  padding: 0 17px;
  border-bottom: 1px solid #e7ecee;
  background: #f6f8f9;
}
.code-filebar strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.code-filebar strong { font-size: 12px; }

.code-loading { padding: 18px; }
.evidence-content {
  min-width: 0;
  overflow: auto;
  padding: 12px 0 24px;
  background: linear-gradient(90deg, #f7f9f9 0 49px, #fff 49px);
}
.evidence-line {
  display: grid;
  min-width: max-content;
  grid-template-columns: 49px minmax(0, 1fr);
  border-left: 3px solid transparent;
}
.evidence-line.is-bound { border-left-color: #18c9ef; background: #eafcff; }
.evidence-line > span {
  padding-right: 11px;
  color: #9aa7ad;
  font: 10px/23px Consolas, monospace;
  text-align: right;
  user-select: none;
}
.evidence-line code {
  min-height: 23px;
  padding: 0 18px 0 12px;
  color: #3d4b52;
  font: 11px/23px Consolas, "Cascadia Code", monospace;
  white-space: pre;
}
.code-binding-note {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 16px 14px;
  border-left: 2px solid #18c9ef;
  padding: 10px 12px;
  background: #f1fbfd;
}
.code-binding-note span {
  display: grid;
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: #090c0e;
  box-shadow: 0 0 0 2px #18c9ef;
  color: #fff;
  font: 600 9px Consolas, monospace;
}
.code-binding-note p { margin: 0; color: #66747a; font-size: 10px; line-height: 1.55; }
.code-empty { display: grid; align-content: center; justify-items: center; gap: 8px; padding: 30px; color: #869197; text-align: center; }
.code-empty > span { color: #18c9ef; font-size: 34px; }
.code-empty strong { color: #3d494f; font-size: 13px; }
.code-empty p { margin: 0; font-size: 11px; }
</style>
