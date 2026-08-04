<template>
  <div class="linked-canvas" :class="`mode-${mode.toLowerCase().replace('_', '-')}`">
    <LinkedCodePane
      v-show="mode !== 'DOCUMENT_FOCUS'"
      ref="codePaneRef"
      :workspace-id="workspaceId"
      :repository-id="repositoryId"
      :content="sourceContent"
      :path="sourcePath"
      :language="sourceLanguage"
      :anchors="anchors"
      :links="links"
      :issues="issues"
      :active-link-id="activeLinkId"
      :loading="sourceLoading"
      :source-loaded="sourceLoaded"
      @activate="emit('activate-code', $event)"
      @open-agent-review="emit('open-agent-review', $event)"
    />
    <CodeAnchorRail
      v-show="mode === 'LINKED' || mode === 'DRIFT_REVIEW'"
      :links="links"
      :anchors="anchors"
      :issues="issues"
      :active-link-id="activeLinkId"
      @activate="emit('activate-rail', $event)"
    />
    <LinkedDocumentPane
      v-show="mode !== 'CODE_FOCUS'"
      ref="documentPaneRef"
      :document="document"
      :active-block-id="activeBlockId"
      :readonly="readonly"
      :loading="documentLoading"
      :remote-block="remoteBlock"
      :editing-states="editingStates"
      :save-via-collaboration="saveViaCollaboration"
      @select-block="emit('select-block', $event)"
      @blocks-loaded="emit('blocks-loaded', $event)"
      @editing-start="emit('editing-start', $event)"
      :unbound-block-id="unboundBlockId"
      @editing-stop="emit('editing-stop', $event)"
      @request-agent-check="emit('request-agent-check')"
    />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import type { DocumentSummary } from '@/api/document';
import CodeAnchorRail from '@/components/linked-workbench/CodeAnchorRail.vue';
import LinkedCodePane from '@/components/linked-workbench/LinkedCodePane.vue';
import LinkedDocumentPane from '@/components/linked-workbench/LinkedDocumentPane.vue';
import type { EditingState } from '@/composables/useDocumentCollaboration';
import type { CodeAnchor, CodeDocumentLink, EngineeringIssue, WorkbenchMode } from '@/types/linkedWorkbench';

defineProps<{
  workspaceId: string;
  repositoryId: string;
  mode: WorkbenchMode;
  sourceContent: string;
  sourcePath: string;
  sourceLanguage?: string | null;
  sourceLoading?: boolean;
  sourceLoaded?: boolean;
  anchors: CodeAnchor[];
  links: CodeDocumentLink[];
  issues: EngineeringIssue[];
  activeLinkId: string | null;
  document: DocumentSummary | null;
  activeBlockId: string | null;
  unboundBlockId: string | null;
  readonly: boolean;
  documentLoading?: boolean;
  remoteBlock?: DocumentBlock | null;
  editingStates?: EditingState[];
  saveViaCollaboration?: (block: DocumentBlock, content: DocumentBlockContent) => Promise<DocumentBlock>;
}>();
const emit = defineEmits<{
  'activate-code': [linkId: string];
  'activate-rail': [linkId: string];
  'select-block': [blockId: string];
  'blocks-loaded': [blocks: DocumentBlock[]];
  'editing-start': [blockId: string];
  'editing-stop': [blockId: string];
  'open-agent-review': [changeRequestId: string | null];
  'request-agent-check': [];
}>();
const codePaneRef = ref<InstanceType<typeof LinkedCodePane> | null>(null);
const documentPaneRef = ref<InstanceType<typeof LinkedDocumentPane> | null>(null);
function focusAnchor(anchorId: string) { codePaneRef.value?.focusAnchor(anchorId); }
function focusBlock(blockId: string) { documentPaneRef.value?.focusBlock(blockId); }
function clearBlockFocus() { documentPaneRef.value?.clearBlockFocus(); }
function confirmDocumentLeave() {
  return documentPaneRef.value?.confirmLeave() ?? Promise.resolve(true);
}
defineExpose({ focusAnchor, focusBlock, clearBlockFocus, confirmDocumentLeave });
</script>

<style scoped>
.linked-canvas { display: grid; min-width: 0; min-height: 0; grid-template-columns: minmax(330px, 1fr) 62px minmax(380px, 1.08fr); overflow: hidden; }
.linked-canvas.mode-code-focus { grid-template-columns: minmax(0, 1fr); }
.linked-canvas.mode-document-focus { grid-template-columns: minmax(0, 1fr); }
@media (max-width: 1120px) { .linked-canvas { grid-template-columns: minmax(280px, .9fr) 50px minmax(340px, 1.1fr); } }
</style>
