<template>
  <nav
    class="workspace-context-navigation"
    :style="{ '--active-index': activeIndex }"
    aria-label="当前工作区导航"
  >
    <span class="workspace-navigation-indicator" aria-hidden="true" />
    <button type="button" :class="{ 'is-active': visualActive === 'spaces' }" @click="navigateTo('spaces')">协作空间<span v-if="visualActive === 'spaces'" aria-hidden="true" /></button>
    <button type="button" :class="{ 'is-active': visualActive === 'linked' }" @click="navigateTo('linked')">关联工作台<span v-if="visualActive === 'linked'" aria-hidden="true" /></button>
    <button type="button" :class="{ 'is-active': visualActive === 'reviews' }" @click="navigateTo('reviews')">审阅中心<span v-if="visualActive === 'reviews'" aria-hidden="true" /></button>
    <button type="button" :class="{ 'is-active': visualActive === 'documents' }" :disabled="documentsLoading || !firstDocumentId" :title="!documentsLoading && !firstDocumentId ? '当前工作区还没有文档' : undefined" @click="navigateTo('documents')">团队文档<span v-if="visualActive === 'documents'" aria-hidden="true" /></button>
  </nav>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { listDocumentTree, type DocumentTreeNode } from '@/api/document';

type NavigationTarget = 'spaces' | 'linked' | 'reviews' | 'documents';

const props = defineProps<{ workspaceId: string; active: 'linked' | 'reviews' | 'documents'; documentId?: string | null }>();
const router = useRouter();
const visualActive = ref<NavigationTarget>(props.active);
const firstDocumentId = ref(props.documentId || '');
const documentsLoading = ref(false);
const activeIndex = computed(() => ({ spaces: 0, linked: 1, reviews: 2, documents: 3 })[visualActive.value]);

function firstId(nodes: DocumentTreeNode[]): string {
  for (const node of nodes) return node.id || firstId(node.children || []);
  return '';
}
async function loadFirstDocument() {
  if (props.documentId) { firstDocumentId.value = props.documentId; return; }
  documentsLoading.value = true;
  try { firstDocumentId.value = firstId(await listDocumentTree(props.workspaceId)); }
  catch { firstDocumentId.value = ''; }
  finally { documentsLoading.value = false; }
}
async function navigateTo(target: NavigationTarget) {
  if (target === visualActive.value) return;
  if (target === 'documents' && !firstDocumentId.value) return;
  visualActive.value = target;
  await new Promise(resolve => window.setTimeout(resolve, 190));
  if (target === 'spaces') await router.push('/workspaces');
  else if (target === 'linked') await router.push(`/workspaces/${props.workspaceId}/code`);
  else if (target === 'reviews') await router.push(`/workspaces/${props.workspaceId}/reviews/pending`);
  else await router.push(`/w/${props.workspaceId}/docs/${firstDocumentId.value}`);
}
onMounted(loadFirstDocument);
watch(() => props.workspaceId, loadFirstDocument);
watch(() => props.active, value => { visualActive.value = value; });
</script>

<style scoped>
.workspace-context-navigation {
  --item-width: 112px;
  --navigation-ink: #090c0e;
  --navigation-paper: #f0f2f3;
  --navigation-muted: #748087;
  --navigation-signal: #18c9ef;
  position: absolute;
  z-index: 4;
  top: 50%;
  left: 50%;
  display: grid;
  width: max-content;
  grid-template-columns: repeat(4, var(--item-width));
  align-items: center;
  border: 1px solid #e3e8eb;
  border-radius: 999px;
  padding: 5px;
  background: var(--navigation-paper);
  transform: translate(calc(-50% + var(--item-width)), -50%);
}

.workspace-navigation-indicator {
  position: absolute;
  z-index: 0;
  top: 5px;
  left: 5px;
  width: var(--item-width);
  height: calc(100% - 10px);
  border-radius: 999px;
  background: var(--navigation-ink);
  box-shadow:
    0 6px 14px rgb(8 15 20 / 17%),
    6px 7px 14px -10px rgb(24 201 239 / 82%);
  transform: translateX(calc(var(--active-index) * var(--item-width)));
  transition: transform 190ms cubic-bezier(.22, .8, .24, 1);
}

button {
  position: relative;
  z-index: 1;
  min-height: 40px;
  border: 0;
  border-radius: 999px;
  padding: 0 14px;
  background: transparent;
  color: var(--navigation-muted);
  cursor: pointer;
  font: inherit;
  font-family: "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", system-ui, sans-serif;
  font-size: 14px;
  transition: color 180ms ease, transform 180ms ease;
}

button:hover:not(:disabled) {
  color: var(--navigation-ink);
}

button:active:not(:disabled) {
  transform: translateY(1px);
}

button.is-active,
button.is-active:hover {
  color: #fff;
}

button.is-active span {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 7px;
  border-radius: 50%;
  background: var(--navigation-signal);
  vertical-align: 2px;
}

button:disabled {
  cursor: not-allowed;
  opacity: .42;
}

button:focus-visible {
  outline: 2px solid var(--navigation-signal);
  outline-offset: 2px;
}

@media (max-width: 820px), (orientation: portrait) {
  .workspace-context-navigation {
    --item-width: 96px;
    position: static;
    grid-column: 1 / -1;
    grid-row: 2;
    max-width: 100%;
    transform: none;
  }

  button {
    padding-inline: 8px;
    font-size: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  button,
  .workspace-navigation-indicator {
    transition: none;
  }
}
</style>
