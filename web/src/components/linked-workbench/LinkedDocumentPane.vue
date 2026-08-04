<template>
  <section class="linked-pane linked-document-pane">
    <header class="document-pane-header">
      <div>
        <strong>{{ document?.title || '选择关联文档' }}</strong>
        <span v-if="document">{{ document.documentType }} · {{ document.reviewStatus }}</span>
      </div>
      <el-tag v-if="document" size="small" :type="readonly ? 'info' : 'primary'">
        {{ readonly ? '只读' : '可编辑' }}
      </el-tag>
    </header>
    <div
      v-if="unboundBlockId && unboundBlockId === activeBlockId"
      class="unbound-block-hint"
    >
      <span>此段落未关联代码</span>
      <button type="button" class="unbound-agent-link" @click="emit('request-agent-check')">
        Agent 分析
      </button>
    </div>
    <div class="document-editor-scroll">
      <el-skeleton v-if="loading" :rows="8" animated />
      <el-empty v-else-if="!document" description="从左侧选择关联文档" />
      <BlockEditor
        v-else
        ref="blockEditorRef"
        :document-id="document.id"
        :active-block-id="activeBlockId"
        :readonly="readonly"
        compact-reading
        :remote-block="remoteBlock"
        :editing-states="editingStates"
        :save-via-collaboration="saveViaCollaboration"
        @select-block="emit('select-block', $event)"
        @blocks-loaded="emit('blocks-loaded', $event)"
        @editing-start="emit('editing-start', $event)"
        @editing-stop="emit('editing-stop', $event)"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import type { DocumentSummary } from '@/api/document';
import BlockEditor from '@/components/editor/BlockEditor.vue';
import type { EditingState } from '@/composables/useDocumentCollaboration';

defineProps<{
  document: DocumentSummary | null;
  activeBlockId: string | null;
  unboundBlockId: string | null;
  readonly: boolean;
  loading?: boolean;
  remoteBlock?: DocumentBlock | null;
  editingStates?: EditingState[];
  saveViaCollaboration?: (
    block: DocumentBlock,
    content: DocumentBlockContent,
  ) => Promise<DocumentBlock>;
}>();

const emit = defineEmits<{
  'select-block': [blockId: string];
  'blocks-loaded': [blocks: DocumentBlock[]];
  'editing-start': [blockId: string];
  'editing-stop': [blockId: string];
  'request-agent-check': [];
}>();

const blockEditorRef = ref<InstanceType<typeof BlockEditor> | null>(null);
function focusBlock(blockId: string) {
  return blockEditorRef.value?.focusBlock(blockId);
}
function clearBlockFocus() {
  blockEditorRef.value?.clearBlockFocus();
}
function confirmLeave() {
  return blockEditorRef.value?.confirmLeave() ?? Promise.resolve(true);
}
defineExpose({ focusBlock, clearBlockFocus, confirmLeave });
</script>

<style scoped>
.linked-document-pane { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto minmax(0, 1fr); background: #fff; }
.document-pane-header { display: flex; min-width: 0; min-height: 58px; align-items: center; justify-content: space-between; gap: 10px; padding: 9px 13px; border-bottom: 1px solid #e4e9f1; }
.document-pane-header > div { display: grid; min-width: 0; gap: 3px; }
.document-pane-header strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.document-pane-header span { color: #667085; font-size: 11px; }
.unbound-block-hint { display: flex; align-items: center; justify-content: space-between; padding: 7px 14px; border-bottom: 1px solid #f0e6c0; background: #fffdf5; color: #92600a; font-size: 12px; }
.unbound-agent-link { border: 0; padding: 3px 10px; border-radius: 5px; background: #fff7e0; color: #b45309; font-size: 12px; cursor: pointer; }
.unbound-agent-link:hover { background: #fde68a; color: #92400e; }
.document-editor-scroll { min-width: 0; min-height: 0; overflow: auto; padding: 20px 22px 32px; }
.document-editor-scroll :deep(.block-editor-header) { align-items: flex-start; }
.document-editor-scroll :deep(.section-hint) { display: none; }
.document-editor-scroll :deep(.block-editor) { border-top: 0; padding-top: 0; }
.document-editor-scroll :deep(.block-list) { gap: 20px; }
.document-editor-scroll :deep(.paragraph-block.is-compact-reading) {
  position: relative;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  padding: 10px 12px 10px 15px;
  transition: background-color .15s ease, border-color .15s ease;
}
.document-editor-scroll :deep(.paragraph-block.is-compact-reading:hover) {
  border-color: #e5eaf1;
  background: #fafbfc;
}
.document-editor-scroll :deep(.paragraph-block.is-compact-reading.is-linked-active) {
  border-color: #b9ceff;
  background: #f2f6ff;
  box-shadow: none;
}
.document-editor-scroll :deep(.paragraph-block.is-compact-reading.is-linked-active::before) {
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 0;
  width: 3px;
  border-radius: 2px;
  background: #155eef;
  content: '';
}
.document-editor-scroll :deep(.is-compact-reading .tiptap-editor-shell) {
  min-height: 0;
  border: 0;
  background: transparent;
  cursor: default;
}
.document-editor-scroll :deep(.is-compact-reading.is-editing .tiptap-editor-shell) {
  border: 1px solid #9eb9f4;
  background: #fff;
  cursor: text;
}
.document-editor-scroll :deep(.is-compact-reading .tiptap-content) {
  min-height: 0;
  padding: 0;
  font-size: 15px;
  line-height: 1.78;
}
.document-editor-scroll :deep(.is-compact-reading.is-editing .tiptap-content) {
  min-height: 96px;
  padding: 12px 14px;
}
.document-editor-scroll :deep(.compact-edit-toolbar) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.document-editor-scroll :deep(.compact-edit-state) { color: #667085; font-size: 12px; }
.document-editor-scroll :deep(.compact-edit-actions) { display: flex; align-items: center; gap: 6px; }
</style>
