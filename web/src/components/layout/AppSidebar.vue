<template>
  <aside
    ref="sidebarRef"
    class="sidebar"
    :class="{
      'is-collapsed': modelValue,
      'is-linked-workbench': isLinkedWorkbench,
    }"
    :data-variant="sidebarVariant"
  >
    <div class="brand">
      <span class="brand-mark">{{ isLinkedWorkbench ? 'DC' : 'D' }}</span>
      <span v-if="!modelValue" class="sidebar-label">DevCollab</span>
    </div>

    <template v-if="isLinkedWorkbench">
      <LinkedWorkspaceNavigation
        :collapsed="modelValue"
        :active-item="linkedNavigationActive"
        :linked-count="linkedCount"
        :review-count="reviewCount"
        :drift-count="driftCount"
        :review-status="reviewStatus"
        :review-status-counts="reviewStatusCounts"
        @open-workspace="navigateToWorkspaceList"
        @open-linked="emit('open-linked')"
        @open-review="emit('open-review')"
        @open-review-status="emit('open-review-status', $event)"
        @open-drift="emit('open-drift')"
      />
      <div v-if="!modelValue" class="linked-context-scroller">
        <slot name="workspace-panel" />
      </div>
    </template>

    <div v-else class="sidebar-content">
      <nav class="nav-list" aria-label="主导航">
        <button
          class="nav-item"
          :class="{ 'is-active': active === 'home' }"
          type="button"
          title="首页"
          @click="openHome"
        >
          <House class="nav-icon" />
          <span v-if="!modelValue" class="sidebar-label">首页</span>
        </button>
        <button
          v-if="workspaceId"
          class="nav-item"
          :class="{ 'is-active': active === 'documents' }"
          type="button"
          title="工作区"
          @click="openDocuments"
        >
          <Document class="nav-icon" />
          <span v-if="!modelValue" class="sidebar-label">工作区</span>
        </button>
        <button
          v-if="workspaceId"
          class="nav-item"
          :class="{ 'is-active': active === 'code' }"
          type="button"
          title="代码阅读"
          @click="openCode"
        >
          <Files class="nav-icon" />
          <span v-if="!modelValue" class="sidebar-label">代码</span>
        </button>
      </nav>

      <template v-if="workspaceId && !modelValue">
        <section class="sidebar-document-section">
          <div class="sidebar-section-label">
            <span>工程文档</span>
            <button
              v-if="manageable"
              type="button"
              title="新建文档"
              aria-label="新建文档"
              @click="emit('create-document')"
            >
              <Plus />
            </button>
          </div>

          <p v-if="documentTree.length === 0" class="sidebar-tree-empty">暂无文档</p>
          <DocumentTree
            v-else
            :nodes="documentTree"
            :active-document-id="activeDocumentId"
            :manageable="manageable"
            @select="emit('select-document', $event)"
            @create-child="emit('create-child', $event)"
            @rename="emit('rename', $event)"
            @move="emit('move', $event)"
            @move-root="emit('move-root', $event)"
            @delete="emit('delete', $event)"
          />

          <button
            v-if="manageable"
            class="sidebar-new-document"
            type="button"
            @click="emit('create-document')"
          >
            <Plus />
            <span>新建文档</span>
          </button>
        </section>
      </template>
    </div>

    <div
      v-if="!modelValue"
      class="sidebar-resizer"
      role="separator"
      aria-label="调整导航栏宽度"
      aria-orientation="vertical"
      :aria-valuemin="MIN_SIDEBAR_WIDTH"
      :aria-valuemax="MAX_SIDEBAR_WIDTH"
      :aria-valuenow="sidebarWidth"
      tabindex="0"
      @pointerdown="startResize"
      @keydown="resizeWithKeyboard"
      @dblclick="setSidebarWidth(DEFAULT_SIDEBAR_WIDTH)"
    />

    <button
      class="sidebar-toggle"
      type="button"
      :title="modelValue ? '展开导航' : '收起导航'"
      :aria-label="modelValue ? '展开导航' : '收起导航'"
      @click="toggle"
    >
      <DArrowRight v-if="modelValue" />
      <DArrowLeft v-else />
    </button>
  </aside>
</template>

<script setup lang="ts">
import { DArrowLeft, DArrowRight, Document, Files, House, Plus } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import type { DocumentTreeNode } from '@/api/document';
import DocumentTree, {
  type FlatDocumentTreeNode,
} from '@/components/document/DocumentTree.vue';
import LinkedWorkspaceNavigation from '@/components/layout/LinkedWorkspaceNavigation.vue';

const props = withDefaults(defineProps<{
  modelValue: boolean;
  active: 'home' | 'documents' | 'code';
  workspaceId?: string | null;
  documentTree?: DocumentTreeNode[];
  activeDocumentId?: string;
  manageable?: boolean;
  linkedNavigationActive?: 'linked' | 'review' | 'drift';
  linkedCount?: number;
  reviewCount?: number;
  driftCount?: number;
  reviewStatus?: 'pending' | 'applied' | 'rejected' | 'stale';
  reviewStatusCounts?: Record<'pending' | 'applied' | 'rejected' | 'stale', number>;
}>(), {
  workspaceId: null,
  documentTree: () => [],
  activeDocumentId: undefined,
  manageable: false,
  linkedNavigationActive: 'linked',
  linkedCount: 0,
  reviewCount: 0,
  driftCount: 0,
  reviewStatus: 'pending',
  reviewStatusCounts: () => ({
    pending: 0,
    applied: 0,
    rejected: 0,
    stale: 0,
  }),
});

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  'select-document': [documentId: string];
  'create-document': [];
  'create-child': [node: FlatDocumentTreeNode];
  rename: [node: FlatDocumentTreeNode];
  move: [node: FlatDocumentTreeNode];
  'move-root': [node: FlatDocumentTreeNode];
  delete: [node: FlatDocumentTreeNode];
  'open-linked': [];
  'open-review': [];
  'open-review-status': [status: 'pending' | 'applied' | 'rejected' | 'stale'];
  'open-drift': [];
}>();

const route = useRoute();
const router = useRouter();
const DEFAULT_SIDEBAR_WIDTH = 280;
const MIN_SIDEBAR_WIDTH = 240;
const MAX_SIDEBAR_WIDTH = 360;
const SIDEBAR_WIDTH_KEY = 'devcollab.sidebar.width';
type SidebarVariant = 'DEFAULT' | 'LINKED_WORKBENCH';
const sidebarVariant = computed<SidebarVariant>(() => route.matched.some(
  record => record.meta.sidebarVariant === 'LINKED_WORKBENCH',
) ? 'LINKED_WORKBENCH' : 'DEFAULT');
const isLinkedWorkbench = computed(() => sidebarVariant.value === 'LINKED_WORKBENCH');

const sidebarRef = ref<HTMLElement | null>(null);
const sidebarWidth = ref(DEFAULT_SIDEBAR_WIDTH);
let resizeStartX = 0;
let resizeStartWidth = DEFAULT_SIDEBAR_WIDTH;

onMounted(() => {
  const savedWidth = Number(localStorage.getItem(SIDEBAR_WIDTH_KEY));
  setSidebarWidth(Number.isFinite(savedWidth) && savedWidth > 0
    ? savedWidth
    : DEFAULT_SIDEBAR_WIDTH);
});

onBeforeUnmount(stopResize);

function toggle() {
  const value = !props.modelValue;
  localStorage.setItem('devcollab.sidebar.collapsed', String(value));
  emit('update:modelValue', value);
}

function openHome() {
  void router.push('/workspaces');
}

function navigateToWorkspaceList() {
  void router.push('/workspaces');
}

function openDocuments() {
  if (props.workspaceId) {
    void router.push(`/workspaces/${props.workspaceId}`);
  }
}

function openCode() {
  if (props.workspaceId) {
    void router.push(`/workspaces/${props.workspaceId}/code`);
  }
}

function startResize(event: PointerEvent) {
  if (event.button !== 0) return;
  event.preventDefault();
  resizeStartX = event.clientX;
  resizeStartWidth = sidebarWidth.value;
  document.body.classList.add('is-resizing-sidebar');
  window.addEventListener('pointermove', handleResize);
  window.addEventListener('pointerup', stopResize);
  window.addEventListener('pointercancel', stopResize);
}

function handleResize(event: PointerEvent) {
  setSidebarWidth(resizeStartWidth + event.clientX - resizeStartX);
}

function stopResize() {
  document.body.classList.remove('is-resizing-sidebar');
  window.removeEventListener('pointermove', handleResize);
  window.removeEventListener('pointerup', stopResize);
  window.removeEventListener('pointercancel', stopResize);
}

function resizeWithKeyboard(event: KeyboardEvent) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
  event.preventDefault();
  setSidebarWidth(sidebarWidth.value + (event.key === 'ArrowRight' ? 16 : -16));
}

function setSidebarWidth(width: number) {
  const nextWidth = Math.round(Math.min(
    MAX_SIDEBAR_WIDTH,
    Math.max(MIN_SIDEBAR_WIDTH, width),
  ));
  sidebarWidth.value = nextWidth;
  localStorage.setItem(SIDEBAR_WIDTH_KEY, String(nextWidth));
  sidebarRef.value?.parentElement?.style.setProperty(
    '--sidebar-width',
    `${nextWidth}px`,
  );
}
</script>
