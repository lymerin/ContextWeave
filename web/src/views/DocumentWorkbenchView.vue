<template>
  <main class="app-shell" :class="{ 'is-sidebar-collapsed': sidebarCollapsed }">
    <header class="document-global-topbar" aria-label="团队文档顶部栏">
      <button
        class="document-global-brand"
        type="button"
        aria-label="返回 ContextWeave 首页"
        @click="router.push('/')"
      >
        <span class="document-brand-logo" aria-hidden="true"><span /></span>
        <span class="document-brand-name">ContextWeave</span>
        <span class="document-brand-beta">BETA</span>
      </button>
    </header>

    <AppSidebar
      v-model="sidebarCollapsed"
      active="documents"
      :workspace-id="workspaceId"
      :document-tree="documentTree"
      :active-document-id="documentId"
      @select-document="openDocument"
    />

    <section class="workspace workbench-page">
      <header class="topbar document-workbench-header">
        <div class="document-heading">
          <p class="eyebrow">TEAM DOCUMENT / EDITOR</p>
          <h1>{{ document?.title || '文档工作台' }}</h1>
          <div class="document-heading-meta">
            <span class="connection-state" :class="{ 'is-online': collaborationConnected }">
              <i />{{ collaborationConnected ? '协作已连接' : '协作连接中' }}
            </span>
            <span>{{ collaborationMembers.length }} 人在线</span>
            <span v-if="versions.length">{{ versions.length }} 个版本</span>
          </div>
        </div>
        <WorkspaceContextNavigation
          :workspace-id="workspaceId"
          :document-id="documentId"
          active="documents"
        />
        <div class="topbar-actions">
          <NotificationCenter />
          <el-button class="ark-secondary-action" :icon="Back" @click="router.push(`/workspaces/${workspaceId}`)">
            返回工作区
          </el-button>
          <el-tag v-if="workspace" effect="light">
            {{ workspace.currentUserRole === 'ADMIN' ? '管理员' : '普通成员' }}
          </el-tag>
        </div>
      </header>

      <section class="workbench-layout document-editor-stage">
        <section class="workbench-editor-panel">
          <el-alert
            v-if="errorMessage"
            :title="errorMessage"
            type="error"
            show-icon
            :closable="false"
          />

          <el-skeleton v-if="documentLoading" :rows="10" animated />

          <template v-else-if="document">
            <DocumentStatusBar
              :document="document"
              :versions="versions"
              :can-review="canReview"
              :busy-action="busyAction"
              @submit="handleSubmit"
              @approve="handleApprove"
              @reject="handleReject"
              @deprecate="handleDeprecate"
            />

            <BlockEditor
              :document-id="document.id"
              :focus-block-id="focusBlockId"
              :readonly="isReadonly"
              :remote-block="collaborationRemoteBlock"
              :editing-states="collaborationEditingStates"
              :save-via-collaboration="saveBlockViaCollaboration"
              @editing-start="startEditing"
              @editing-stop="stopEditing"
            />
          </template>

          <el-empty v-else description="请选择一篇文档" />
        </section>

      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Back } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';

import {
  approveDocumentReview,
  deprecateDocument,
  getDocument,
  listDocumentTree,
  listDocumentVersions,
  rejectDocumentReview,
  submitDocumentReview,
  type DocumentSummary,
  type DocumentTreeNode,
  type DocumentVersion,
} from '@/api/document';
import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import { getWorkspace, type Workspace } from '@/api/workspace';
import DocumentStatusBar from '@/components/document/DocumentStatusBar.vue';
import NotificationCenter from '@/components/notification/NotificationCenter.vue';
import AppSidebar from '@/components/layout/AppSidebar.vue';
import BlockEditor from '@/components/editor/BlockEditor.vue';
import WorkspaceContextNavigation from '@/components/layout/WorkspaceContextNavigation.vue';
import { readableError } from '@/utils/error';
import { useDocumentCollaboration } from '@/composables/useDocumentCollaboration';

const route = useRoute();
const router = useRouter();

const workspace = ref<Workspace | null>(null);
const document = ref<DocumentSummary | null>(null);
const documentTree = ref<DocumentTreeNode[]>([]);
const versions = ref<DocumentVersion[]>([]);
const treeLoading = ref(false);
const documentLoading = ref(false);
const errorMessage = ref('');
const busyAction = ref<'submit' | 'approve' | 'reject' | 'deprecate' | null>(null);
const focusBlockId = ref<string | null>(null);
const sidebarCollapsed = ref(
  localStorage.getItem('devcollab.sidebar.collapsed') === 'true',
);

const workspaceId = computed(() => route.params.workspaceId as string);
const documentId = computed(() => route.params.documentId as string);
const canReview = computed(() => workspace.value?.currentUserRole === 'ADMIN');
const isReadonly = computed(() => (
  document.value?.reviewStatus === 'DEPRECATED'
  || document.value?.reviewStatus === 'SUPERSEDED'
));
const {
  connected: collaborationConnected,
  members: collaborationMembers,
  editingStates: collaborationEditingStates,
  latestRemoteBlock: collaborationRemoteBlock,
  startEditing,
  stopEditing,
  updateContent: updateBlockContentViaCollaboration,
} = useDocumentCollaboration(workspaceId, documentId);

onMounted(() => {
  focusBlockId.value = typeof route.query.blockId === 'string'
    ? route.query.blockId
    : null;
  void loadWorkbench();
});

watch(
  () => route.params.documentId,
  () => {
    focusBlockId.value = typeof route.query.blockId === 'string'
      ? route.query.blockId
      : null;
    void loadDocumentBundle();
  },
);

async function loadWorkbench() {
  await Promise.all([
    loadWorkspace(),
    loadDocumentTree(),
    loadDocumentBundle(),
  ]);
}

async function loadWorkspace() {
  try {
    workspace.value = await getWorkspace(workspaceId.value);
  } catch (error) {
    ElMessage.error(readableError(error, '工作区信息加载失败'));
  }
}

async function loadDocumentTree() {
  treeLoading.value = true;
  try {
    documentTree.value = await listDocumentTree(workspaceId.value);
  } catch (error) {
    ElMessage.error(readableError(error, '文档树加载失败'));
  } finally {
    treeLoading.value = false;
  }
}

async function loadDocumentBundle() {
  documentLoading.value = true;
  errorMessage.value = '';

  try {
    const [documentData, versionData] = await Promise.all([
      getDocument(documentId.value),
      listDocumentVersions(documentId.value),
    ]);
    document.value = documentData;
    versions.value = versionData;
  } catch (error) {
    document.value = null;
    versions.value = [];
    errorMessage.value = readableError(error, '文档工作台加载失败');
  } finally {
    documentLoading.value = false;
  }
}

function openDocument(nextDocumentId: string) {
  void router.push({
    name: 'document-workbench',
    params: {
      workspaceId: workspaceId.value,
      documentId: nextDocumentId,
    },
  });
}

async function saveBlockViaCollaboration(
  block: DocumentBlock,
  content: DocumentBlockContent,
) {
  return updateBlockContentViaCollaboration(
    block.id,
    content,
    block.version,
  );
}

async function handleSubmit() {
  if (!document.value) {
    return;
  }

  busyAction.value = 'submit';
  try {
    document.value = await submitDocumentReview(document.value.id);
    await refreshSideData();
    ElMessage.success(
      document.value.reviewStatus === 'IN_REVIEW'
        ? '文档已提交评审'
        : '新修订已创建',
    );
  } catch (error) {
    ElMessage.error(readableError(error, '提交评审失败'));
  } finally {
    busyAction.value = null;
  }
}

async function handleApprove() {
  if (!document.value) {
    return;
  }

  busyAction.value = 'approve';
  try {
    document.value = await approveDocumentReview(document.value.id);
    await refreshSideData();
    ElMessage.success('文档已发布为新版本');
  } catch (error) {
    ElMessage.error(readableError(error, '通过评审失败'));
  } finally {
    busyAction.value = null;
  }
}

async function handleReject() {
  if (!document.value) {
    return;
  }

  busyAction.value = 'reject';
  try {
    document.value = await rejectDocumentReview(document.value.id);
    await refreshSideData();
    ElMessage.success('文档已驳回');
  } catch (error) {
    ElMessage.error(readableError(error, '驳回评审失败'));
  } finally {
    busyAction.value = null;
  }
}

async function handleDeprecate() {
  if (!document.value) {
    return;
  }

  try {
    await ElMessageBox.confirm(
      '废弃后文档将进入只读状态，不能继续编辑或评审。确定废弃吗？',
      '废弃文档',
      {
        confirmButtonText: '废弃',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }

  busyAction.value = 'deprecate';
  try {
    document.value = await deprecateDocument(document.value.id);
    await refreshSideData();
    ElMessage.success('文档已废弃');
  } catch (error) {
    ElMessage.error(readableError(error, '废弃文档失败'));
  } finally {
    busyAction.value = null;
  }
}

async function refreshSideData() {
  if (!document.value) {
    return;
  }

  versions.value = await listDocumentVersions(document.value.id);
}
</script>

<style scoped>
.document-global-topbar {
  position: absolute;
  z-index: 20;
  top: 0;
  left: 0;
  width: 100%;
  height: 72px;
  border: 0;
  border-bottom: 1px solid rgba(202, 213, 220, .78);
  background: rgba(255, 255, 255, .98);
  box-shadow: 0 7px 24px rgba(28, 46, 58, .055);
}

.document-global-brand {
  position: absolute;
  top: 17px;
  left: 28px;
  display: flex;
  align-items: center;
  gap: 11px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #0c1116;
  cursor: pointer;
}

.document-brand-logo {
  position: relative;
  width: 38px;
  height: 38px;
  overflow: hidden;
  border-radius: 10px;
  background: #0b1015;
}

.document-brand-logo::before,
.document-brand-logo::after,
.document-brand-logo span {
  position: absolute;
  left: 9px;
  width: 20px;
  height: 2px;
  background: #18d1ff;
  content: "";
  transform: skewX(-28deg);
}

.document-brand-logo::before { top: 11px; }
.document-brand-logo span { top: 18px; width: 13px; }
.document-brand-logo::after { top: 25px; }

.document-brand-name {
  font-size: 20px;
  font-weight: 760;
  letter-spacing: -.035em;
}

.document-brand-beta {
  padding: 4px 7px;
  border: 1px solid #dfe5e8;
  border-radius: 5px;
  background: #f3f5f6;
  color: #74808a;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .12em;
}

.document-global-brand:focus-visible {
  outline: 2px solid #18d1ff;
  outline-offset: 3px;
}

.workbench-page {
  position: relative;
  min-height: 100vh;
  padding: 78px 24px 24px;
  background:
    radial-gradient(circle at 14% 9%, rgba(21, 202, 237, .09), transparent 27%),
    radial-gradient(circle at 78% 100%, rgba(136, 218, 230, .09), transparent 28%),
    radial-gradient(circle, rgba(20, 179, 215, .13) 1px, transparent 1.2px),
    #f2f5f6;
  background-size: auto, auto, 24px 24px, auto;
}

.document-workbench-header {
  min-height: 112px;
  padding: 18px 24px;
  border: 1px solid rgba(214, 224, 229, .92);
  border-bottom: 0;
  border-radius: 24px 24px 0 0;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 18px 44px rgba(32, 55, 68, .07);
}

.document-heading {
  min-width: 0;
}

.document-heading .eyebrow {
  margin: 0 0 7px;
  color: #168baa;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .16em;
}

.document-heading h1 {
  max-width: 680px;
  margin: 0;
  overflow: hidden;
  color: #0b1115;
  font-size: clamp(25px, 2vw, 34px);
  line-height: 1.15;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-heading-meta {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 10px;
  color: #7a8790;
  font-size: 12px;
}

.document-heading-meta > span + span::before {
  margin-right: 9px;
  color: #c8d0d5;
  content: "·";
}

.connection-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.connection-state i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #aab5bb;
}

.connection-state.is-online i {
  background: #20c895;
  box-shadow: 0 0 0 4px rgba(32, 200, 149, .11);
}

.document-workbench-header :deep(.workspace-context-navigation) {
  display: none;
}

.topbar-actions :deep(.el-tag) {
  border-color: #dfe7ea;
  background: #f5f7f8;
  color: #68757d;
}

.ark-secondary-action {
  min-height: 42px;
  border-color: #dbe4e8;
  border-radius: 11px;
  color: #182126;
  transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
}

.ark-secondary-action:hover {
  border-color: rgba(31, 197, 232, .42);
  color: #0b1115;
  box-shadow: 7px 8px 18px -12px rgba(18, 196, 234, .95), 0 8px 20px rgba(27, 49, 61, .07);
  transform: translateY(-1px);
}

.document-editor-stage {
  display: block;
  height: auto;
  min-height: calc(100vh - 214px);
  margin-top: 0;
  overflow: visible;
  border-color: rgba(214, 224, 229, .92);
  border-bottom: 1px solid rgba(214, 224, 229, .92);
  border-radius: 0 0 24px 24px;
  box-shadow: 0 24px 50px rgba(32, 55, 68, .08);
}

.workbench-editor-panel {
  min-height: calc(100vh - 214px);
  overflow: visible;
  padding: 22px 30px 64px;
}

.workbench-editor-panel :deep(.document-status-bar) {
  position: sticky;
  z-index: 8;
  top: 12px;
  margin-bottom: 26px;
  border-color: #e0e8eb;
  border-radius: 15px;
  background: rgba(248, 250, 250, .94);
  box-shadow: 0 10px 28px rgba(32, 55, 68, .055);
  backdrop-filter: blur(14px);
}

.workbench-editor-panel :deep(.el-button--primary) {
  border-color: #090d0f;
  background: #090d0f;
  color: #fff;
  box-shadow: none;
  transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
}

.workbench-editor-panel :deep(.el-button--primary:hover) {
  border-color: #090d0f;
  background: #090d0f;
  box-shadow: 8px 10px 20px -12px rgba(18, 196, 234, .95), 0 10px 22px rgba(27, 49, 61, .09);
  transform: translateY(-1px);
}

.workbench-editor-panel :deep(.block-editor) {
  max-width: 1180px;
  margin-inline: auto;
}

.workbench-editor-panel :deep(.block-card) {
  border-color: #dfe7ea;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(31, 51, 62, .045);
}

.workbench-editor-panel :deep(.block-card:hover) {
  border-color: rgba(31, 197, 232, .34);
  box-shadow: 8px 10px 22px -16px rgba(18, 196, 234, .9), 0 14px 32px rgba(31, 51, 62, .06);
}

:global(.app-shell:has(.workbench-page) .sidebar) {
  top: 78px;
  height: calc(100vh - 102px);
  margin: 0 0 24px 24px;
  border: 1px solid rgba(214, 224, 229, .92);
  border-radius: 24px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 18px 44px rgba(32, 55, 68, .07);
}

:global(.app-shell:has(.workbench-page) .sidebar .brand),
:global(.app-shell:has(.workbench-page) .sidebar .nav-list) {
  display: none;
}

:global(.app-shell:has(.workbench-page) .sidebar-document-section) {
  margin-top: 0;
  padding-top: 6px;
  border-top: 0;
}

:global(.app-shell:has(.workbench-page) .sidebar-section-label) {
  margin-bottom: 10px;
  color: #168baa;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  letter-spacing: .14em;
}

:global(.app-shell:has(.workbench-page) .document-tree-row) {
  min-height: 38px;
  border-radius: 9px;
}

:global(.app-shell:has(.workbench-page) .document-tree-row.is-active) {
  background: #f2f7f8;
  color: #0a1115;
  box-shadow: inset 3px 0 0 #18c9ef;
}

@media (max-width: 1100px) {
  .document-workbench-header {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .document-workbench-header :deep(.workspace-context-navigation) {
    display: none;
  }
}

@media (max-width: 760px) {
  .document-global-topbar {
    display: none;
  }

  .workbench-page {
    padding: 14px;
  }

  .document-workbench-header,
  .document-editor-stage {
    border-radius: 16px;
  }

  .document-editor-stage {
    margin-top: 12px;
  }

  .workbench-editor-panel {
    padding: 16px;
  }

  :global(.app-shell:has(.workbench-page) .sidebar) {
    display: none;
  }
}
</style>
