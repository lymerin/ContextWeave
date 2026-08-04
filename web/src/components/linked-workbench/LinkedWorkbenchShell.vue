<template>
  <section class="linked-workbench-shell">
    <LinkedWorkbenchHeader
      :workspace-name="workspaceName"
      :repository-name="repositoryName"
      :branch="branch"
      :commit-sha="commitSha"
      :mode="mode"
      :inspector-open="inspectorOpen"
      @set-mode="emit('set-mode', $event)"
      @toggle-inspector="emit('toggle-inspector')"
    ><template #actions><slot name="header-actions" /></template></LinkedWorkbenchHeader>
    <LinkedWorkbenchContent
      ref="contentRef"
      v-bind="contentProps"
      @activate-code="emit('activate-code', $event)"
      @activate-rail="emit('activate-rail', $event)"
      @activate-inspector="emit('activate-inspector', $event)"
      @select-block="emit('select-block', $event)"
      @blocks-loaded="emit('blocks-loaded', $event)"
      @editing-start="emit('editing-start', $event)"
      @editing-stop="emit('editing-stop', $event)"
      @open-agent-review="emit('open-agent-review', $event)"
      @request-agent-check="emit('request-agent-check')"
      @close-inspector="emit('toggle-inspector')"
    />
    <LinkedWorkbenchStatusBar v-bind="statusProps" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import type { DocumentSummary, DocumentVersion } from '@/api/document';
import LinkedWorkbenchContent from './LinkedWorkbenchContent.vue';
import LinkedWorkbenchHeader from './LinkedWorkbenchHeader.vue';
import LinkedWorkbenchStatusBar from './LinkedWorkbenchStatusBar.vue';
import type { EditingState } from '@/composables/useDocumentCollaboration';
import type { CodeAnchor, CodeDocumentLink, EngineeringIssue, LinkedEvidence, WorkbenchMode } from '@/types/linkedWorkbench';

const props = defineProps<{
  workspaceId: string; repositoryId: string;
  workspaceName?: string; repositoryName?: string; branch?: string; commitSha?: string | null;
  mode: WorkbenchMode; inspectorOpen: boolean; sourceContent: string; sourcePath: string; sourceLoaded?: boolean;
  sourceLanguage?: string | null; sourceLoading?: boolean; anchors: CodeAnchor[]; links: CodeDocumentLink[];
  issues: EngineeringIssue[]; activeLinkId: string | null; document: DocumentSummary | null;
  activeBlockId: string | null; unboundBlockId: string | null; readonly: boolean; documentLoading?: boolean;
  remoteBlock?: DocumentBlock | null; editingStates?: EditingState[];
  saveViaCollaboration?: (block: DocumentBlock, content: DocumentBlockContent) => Promise<DocumentBlock>;
  activeLink: CodeDocumentLink | null; activeAnchor: CodeAnchor | null; activeBlock: DocumentBlock | null;
  activeIssue: EngineeringIssue | null; activeEvidence: LinkedEvidence[]; versions: DocumentVersion[];
  collaborationConnected: boolean; membersCount: number;
}>();
const emit = defineEmits<{
  'set-mode': [mode: WorkbenchMode]; 'toggle-inspector': []; 'activate-code': [linkId: string];
  'activate-rail': [linkId: string]; 'activate-inspector': [linkId: string]; 'select-block': [blockId: string];
  'blocks-loaded': [blocks: DocumentBlock[]]; 'editing-start': [blockId: string]; 'editing-stop': [blockId: string];
  'open-agent-review': [changeRequestId: string | null];
  'request-agent-check': [];
}>();
const contentRef = ref<InstanceType<typeof LinkedWorkbenchContent> | null>(null);
const contentProps = computed(() => ({ ...props }));
const statusProps = computed(() => ({ collaborationConnected: props.collaborationConnected,
  membersCount: props.membersCount, linksCount: props.links.length, activeAnchor: props.activeAnchor }));
function focusAnchor(anchorId: string) { contentRef.value?.focusAnchor(anchorId); }
function focusBlock(blockId: string) { contentRef.value?.focusBlock(blockId); }
function clearBlockFocus() { contentRef.value?.clearBlockFocus(); }
function confirmDocumentLeave() {
  return contentRef.value?.confirmDocumentLeave() ?? Promise.resolve(true);
}
defineExpose({ focusAnchor, focusBlock, clearBlockFocus, confirmDocumentLeave });
</script>

<style scoped>
.linked-workbench-shell { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border: 1px solid #dfe6f0; border-radius: 10px 10px 0 0; background: #fff; box-shadow: 0 8px 24px rgb(16 24 40 / 5%); }
</style>
