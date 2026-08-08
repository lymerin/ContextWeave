<template>
  <section
    class="review-document-pane"
    :class="{ 'has-operation-switcher': operations.length > 1 }"
  >
    <nav v-if="operations.length > 1" class="operation-strip" aria-label="评审变更列表">
      <button
        v-for="item in operations"
        :key="item.operationId"
        type="button"
        :class="{ 'is-active': item.operationId === activeOperationId }"
        @click="emit('select-operation', item.operationId)"
      >
        {{ operationLabels[item.operationType] }}
        <span aria-hidden="true">↗</span>
      </button>
      <small>{{ activeOperationIndex + 1 }} / {{ operations.length }}</small>
    </nav>

    <div class="review-document-scroll">
      <el-skeleton v-if="loading" :rows="8" animated />
      <template v-else>
        <el-alert
          v-if="operation.conflict.conflicted"
          title="目标内容已发生变化；评审仍可查看，但不能覆盖人工修改。"
          type="warning"
          :closable="false"
          show-icon
        />

        <header class="change-header">
          <p>CHANGE DIFF / {{ operation.operationType }}</p>
          <div>
            <h2>{{ documentTitle }}</h2>
            <el-tag size="small" effect="plain">{{ statusLabel }}</el-tag>
          </div>
          <span>{{ changeContext }}</span>
        </header>

        <section class="inline-diff" :class="`is-${operation.operationType.toLowerCase()}`">
          <div class="diff-heading">
            <strong>{{ operationLabel }}</strong>
            <span v-if="operation.target.blockId">Block {{ shortBlockId }}</span>
          </div>
          <div v-if="showsBefore" class="diff-row is-before">
            <span>当前</span>
            <div class="diff-copy">
              <p>{{ beforeText || targetBlock?.content.text || '（空内容）' }}</p>
            </div>
          </div>
          <div v-if="showsAfter" class="diff-row is-after">
            <span>建议</span>
            <div class="diff-copy">
              <p>{{ afterPreview || '（空内容）' }}</p>
              <details v-if="hasLongSuggestion" class="full-suggestion">
                <summary>展开完整建议</summary>
                <p>{{ afterText }}</p>
              </details>
            </div>
          </div>
        </section>

        <div class="diff-owner-note">
          <span>01</span>
          修改建议仅在此处呈现，审查上下文不再重复正文
        </div>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import type { DocumentBlock } from '@/api/block';
import type {
  DocumentChangeOperation,
  DocumentChangeStatus,
} from '@/api/documentChange';
import {
  currentText,
  operationDocumentTitle,
  operationLabels,
  proposalText,
  reviewStatusLabels,
} from './reviewPresentation';

const props = defineProps<{
  operation: DocumentChangeOperation;
  operations: DocumentChangeOperation[];
  activeOperationId: string;
  requestStatus: DocumentChangeStatus;
  blocks: DocumentBlock[];
  loading?: boolean;
}>();

const emit = defineEmits<{
  'select-operation': [operationId: string];
}>();

const documentTitle = computed(() => operationDocumentTitle(props.operation));
const operationLabel = computed(() => operationLabels[props.operation.operationType]);
const statusLabel = computed(() => reviewStatusLabels[props.requestStatus]);
const beforeText = computed(() => currentText(props.operation));
const afterText = computed(() => proposalText(props.operation));
const shortBlockId = computed(() => props.operation.target.blockId?.slice(0, 8) ?? '');
const targetBlock = computed(() => props.blocks.find(item => item.id === props.operation.target.blockId) ?? null);
const hasLongSuggestion = computed(() => afterText.value.length > 320);
const afterPreview = computed(() => hasLongSuggestion.value
  ? `${afterText.value.slice(0, 300).trim()}…`
  : afterText.value);
const activeOperationIndex = computed(() => Math.max(0, props.operations.findIndex(
  item => item.operationId === props.activeOperationId,
)));
const changeContext = computed(() => {
  if (props.operation.operationType === 'CREATE_DOCUMENT') return '新文档建议 · 创建前预览';
  const version = props.operation.baseSnapshot?.blockVersion;
  return `${version == null ? '当前 Block' : `Block V${version}`} → 建议版本 · 与左侧绑定代码联动`;
});
const showsBefore = computed(() => (
  props.operation.operationType === 'UPDATE_BLOCK'
  || props.operation.operationType === 'DELETE_BLOCK'
));
const showsAfter = computed(() => props.operation.operationType !== 'DELETE_BLOCK');
</script>

<style scoped>
.review-document-pane {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: 64px minmax(0, 1fr);
  border-right: 1px solid #e3eaec;
  background: #fff;
}
.review-document-pane:not(.has-operation-switcher) {
  grid-template-rows: minmax(0, 1fr);
}
.operation-strip {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  overflow-x: auto;
  padding: 10px 14px;
  border-bottom: 1px solid #e3eaec;
}
.operation-strip button {
  display: inline-flex;
  min-height: 48px;
  align-items: center;
  gap: 10px;
  border: 1px solid #dfe7e9;
  border-radius: 7px;
  padding: 0 22px;
  background: #fff;
  color: #6f7c82;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  transition: border-color 180ms ease, background 180ms ease, box-shadow 220ms ease, color 180ms ease, transform 180ms ease;
}
.operation-strip button > span {
  color: #18c9ef;
  font-size: 15px;
  line-height: 1;
  transition: transform 180ms ease;
}
.operation-strip button.is-active {
  border-color: #090c0e;
  background: #090c0e;
  box-shadow: none;
  color: #fff;
}
.operation-strip button:hover,
.operation-strip button:focus-visible {
  border-color: rgb(24 201 239 / 20%);
  box-shadow:
    7px 7px 14px -9px rgb(24 201 239 / 78%),
    13px 12px 24px -17px rgb(24 201 239 / 64%),
    0 10px 18px -13px rgb(24 201 239 / 58%);
  outline: none;
  transform: translateY(-2px);
}
.operation-strip button:hover > span,
.operation-strip button:focus-visible > span {
  transform: translate(2px, -1px);
}
.operation-strip small { margin-left: auto; color: #8b979c; font: 10px Consolas, monospace; white-space: nowrap; }
.review-document-scroll { min-width: 0; overflow: auto; padding: 19px 20px 32px; }
.change-header { margin-bottom: 15px; }
.change-header p { margin: 0 0 7px; color: #168ba5; font: 600 9px Consolas, monospace; letter-spacing: .13em; }
.change-header > div { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.change-header h2 { margin: 0; color: #111a1f; font-size: 18px; font-weight: 650; }
.change-header > span { display: block; margin-top: 6px; color: #7b878d; font-size: 10px; }
.inline-diff { border: 1px solid #dfe7e9; border-radius: 9px; overflow: hidden; }
.diff-heading { display: flex; justify-content: space-between; padding: 10px 13px; background: #f7f9f9; color: #4c5960; font-size: 10px; }
.diff-row { display: grid; grid-template-columns: 58px minmax(0, 1fr); min-height: 118px; border-top: 1px solid #e3e9eb; }
.diff-row > span { padding: 14px 10px; font: 600 10px Consolas, monospace; }
.diff-copy { min-width: 0; padding: 14px 16px; color: #334047; line-height: 1.75; }
.diff-copy p { margin: 0; white-space: pre-wrap; }
.full-suggestion { margin-top: 12px; border-top: 1px solid rgb(22 128 96 / 14%); padding-top: 9px; }
.full-suggestion summary { width: max-content; color: #16785d; cursor: pointer; font-size: 10px; font-weight: 600; }
.full-suggestion p { margin-top: 10px; }
.diff-row.is-before { background: #fff5f5; }
.diff-row.is-before > span { color: #b44242; }
.diff-row.is-after { background: #f0fbf6; }
.diff-row.is-after > span { color: #168060; }
.diff-owner-note { display: flex; align-items: center; gap: 9px; margin-top: 13px; color: #7c898e; font-size: 10px; }
.diff-owner-note span { display: grid; width: 20px; height: 20px; place-items: center; border-radius: 50%; background: #090c0e; box-shadow: 0 0 0 2px #18c9ef; color: #fff; font: 600 9px Consolas, monospace; }
</style>
