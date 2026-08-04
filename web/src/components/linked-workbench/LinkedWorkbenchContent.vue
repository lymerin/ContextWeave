<template>
  <section class="linked-content" :class="{ 'has-inspector': inspectorOpen }">
    <LinkedWorkbenchCanvas
      ref="canvasRef"
      v-bind="canvasProps"
      @activate-code="emit('activate-code', $event)"
      @activate-rail="emit('activate-rail', $event)"
      @select-block="emit('select-block', $event)"
      @blocks-loaded="emit('blocks-loaded', $event)"
      @editing-start="emit('editing-start', $event)"
      @editing-stop="emit('editing-stop', $event)"
      @open-agent-review="emit('open-agent-review', $event)"
      @request-agent-check="emit('request-agent-check')"
    />
    <LinkedInspector
      v-show="inspectorOpen"
      :mode="mode"
      :active-link="activeLink"
      :active-anchor="activeAnchor"
      :active-block="activeBlock"
      :active-issue="activeIssue"
      :active-evidence="activeEvidence"
      :versions="versions"
      @close="emit('close-inspector')"
      @activate="emit('activate-inspector', $event)"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import type { DocumentSummary, DocumentVersion } from '@/api/document';
import LinkedInspector from '@/components/linked-workbench/LinkedInspector.vue';
import LinkedWorkbenchCanvas from '@/components/linked-workbench/LinkedWorkbenchCanvas.vue';
import type { EditingState } from '@/composables/useDocumentCollaboration';
import type { CodeAnchor, CodeDocumentLink, EngineeringIssue, LinkedEvidence, WorkbenchMode } from '@/types/linkedWorkbench';

const props = defineProps<{
  workspaceId: string;
  repositoryId: string;
  mode: WorkbenchMode;
  inspectorOpen: boolean;
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
  activeLink: CodeDocumentLink | null;
  activeAnchor: CodeAnchor | null;
  activeBlock: DocumentBlock | null;
  activeIssue: EngineeringIssue | null;
  activeEvidence: LinkedEvidence[];
  versions: DocumentVersion[];
}>();
const emit = defineEmits<{
  'activate-code': [linkId: string]; 'activate-rail': [linkId: string]; 'activate-inspector': [linkId: string];
  'select-block': [blockId: string]; 'blocks-loaded': [blocks: DocumentBlock[]];
  'editing-start': [blockId: string]; 'editing-stop': [blockId: string]; 'close-inspector': [];
  'open-agent-review': [changeRequestId: string | null];
  'request-agent-check': [];
}>();
const canvasRef = ref<InstanceType<typeof LinkedWorkbenchCanvas> | null>(null);
const canvasProps = computed(() => ({
  workspaceId: props.workspaceId, repositoryId: props.repositoryId,
  mode: props.mode, sourceContent: props.sourceContent, sourcePath: props.sourcePath,
  sourceLanguage: props.sourceLanguage, sourceLoading: props.sourceLoading,
  sourceLoaded: props.sourceLoaded,
  anchors: props.anchors, links: props.links, issues: props.issues,
  activeLinkId: props.activeLinkId, document: props.document,
  activeBlockId: props.activeBlockId, unboundBlockId: props.unboundBlockId, readonly: props.readonly,
  documentLoading: props.documentLoading, remoteBlock: props.remoteBlock,
  editingStates: props.editingStates, saveViaCollaboration: props.saveViaCollaboration,
}));
function focusAnchor(anchorId: string) { canvasRef.value?.focusAnchor(anchorId); }
function focusBlock(blockId: string) { canvasRef.value?.focusBlock(blockId); }
function clearBlockFocus() { canvasRef.value?.clearBlockFocus(); }
function confirmDocumentLeave() {
  return canvasRef.value?.confirmDocumentLeave() ?? Promise.resolve(true);
}
defineExpose({ focusAnchor, focusBlock, clearBlockFocus, confirmDocumentLeave });
</script>

<style scoped>
.linked-content { display: grid; min-width: 0; min-height: 0; grid-template-columns: minmax(0, 1fr); overflow: hidden; }
.linked-content.has-inspector { grid-template-columns: minmax(0, 1fr) minmax(270px, 310px); }
@media (max-width: 1280px) { .linked-content.has-inspector { grid-template-columns: minmax(0, 1fr) 270px; } }
</style>
