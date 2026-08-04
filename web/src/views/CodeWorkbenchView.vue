<template>
  <main class="app-shell linked-page-shell" :class="{ 'is-sidebar-collapsed': sidebarCollapsed }">
    <AppSidebar
      v-model="sidebarCollapsed"
      active="code"
      :workspace-id="workspaceId"
      :linked-navigation-active="sidebarNavigationActive"
      :linked-count="links.length"
      :review-count="pendingReviewCount"
      :drift-count="driftedLinkIds.length"
      @open-linked="handleLinkedNavigation"
      @open-review="handleReviewNavigation"
      @open-drift="handleModeChange('DRIFT_REVIEW')"
    >
      <template #workspace-panel>
        <LinkedRepositoryContext
          :repositories="repositories"
          :repository-id="selectedRepositoryId"
          :file-tree="fileTree"
          :files-count="files.length"
          :selected-file-path="selectedFilePath"
          :documents="relatedDocumentChoices"
          :selected-document-id="selectedDocumentId"
          :active-anchor="activeCodeAnchor"
          :file-link-counts="fileLinkCounts"
          :linked-block-count="activeLinkedBlockCount"
          :unresolved-issue-count="activeUnresolvedIssueCount"
          :recent-commit-count="recentCommitCount"
          :loading="contextLoading"
          @select-repository="handleRepositoryChange"
          @select-file="openSourceByPath"
          @select-document="handleDocumentChange"
        />
      </template>
    </AppSidebar>

    <section class="linked-page-main">
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
      <el-alert
        v-if="activeRepository?.lastSyncError"
        :title="activeRepository.lastSyncError"
        type="warning"
        show-icon
        :closable="false"
      />
      <LinkedWorkbenchShell
        v-if="workspace"
        ref="workbenchShellRef"
        :workspace-id="workspaceId"
        :repository-id="selectedRepositoryId"
        :workspace-name="workspace.name"
        :repository-name="activeRepository?.name"
        :branch="activeRepository?.defaultBranch"
        :commit-sha="selectedSource?.commitSha || activeRepository?.lastSyncedCommit"
        :mode="mode"
        :inspector-open="inspectorOpen"
        :source-content="selectedSource?.content || ''"
        :source-path="selectedSource?.path || selectedFilePath"
        :source-language="selectedSource?.language"
        :source-loading="sourceLoading"
        :source-loaded="Boolean(selectedSource?.readable && selectedSource.content !== null)"
        :anchors="codeAnchors"
        :links="links"
        :issues="issues"
        :active-link-id="activeLinkId"
        :document="document"
        :active-block-id="activeDocumentBlock?.id || null"
        :unbound-block-id="unboundBlockId"
        :readonly="documentReadonly"
        :document-loading="documentLoading"
        :remote-block="collaborationRemoteBlock"
        :editing-states="collaborationEditingStates"
        :save-via-collaboration="saveBlockViaCollaboration"
        :active-link="activeLink"
        :active-anchor="activeCodeAnchor"
        :active-block="activeDocumentBlock"
        :active-issue="activeIssue"
        :active-evidence="activeEvidence"
        :versions="versions"
        :collaboration-connected="collaborationConnected"
        :members-count="collaborationMembers.length"
        @set-mode="handleModeChange"
        @toggle-inspector="toggleInspector()"
        @activate-code="handleActivate($event, 'code')"
        @activate-rail="handleActivate($event, 'rail')"
        @activate-inspector="handleActivate($event, 'inspector')"
        @select-block="handleBlockSelection"
        @blocks-loaded="handleBlocksLoaded"
        @editing-start="startEditing"
        @editing-stop="stopEditing"
        @open-agent-review="handleAgentReviewNavigation"
        @request-agent-check="handleDocumentPaneAgentCheck"
      >
        <template #header-actions>
          <el-button
            data-test="linked-history-back"
            size="small"
            circle
            :icon="ArrowLeft"
            :disabled="!linkedNavigation.canGoBack.value"
            :title="historyTargetTitle('后退', linkedNavigation.backTarget.value)"
            @click="handleHistoryBack"
          />
          <el-button
            data-test="linked-history-forward"
            size="small"
            circle
            :icon="ArrowRight"
            :disabled="!linkedNavigation.canGoForward.value"
            :title="historyTargetTitle('前进', linkedNavigation.forwardTarget.value)"
            @click="handleHistoryForward"
          />
          <el-button-group v-if="linkCount > 1" class="binding-switcher">
            <el-button
              data-test="previous-binding"
              size="small"
              :disabled="!canSelectPreviousLink"
              @click="handleAdjacentLink('previous')"
            >上一关联</el-button>
            <el-button size="small" disabled>{{ activeLinkIndex + 1 }} / {{ linkCount }}</el-button>
            <el-button
              data-test="next-binding"
              size="small"
              :disabled="!canSelectNextLink"
              @click="handleAdjacentLink('next')"
            >下一关联</el-button>
          </el-button-group>
          <el-button
            v-if="activeRepository"
            data-test="project-scan"
            size="small"
            :loading="projectScanStarting"
            @click="confirmProjectScan"
          >检查整个项目</el-button>
          <NotificationCenter />
          <el-button
            v-if="workspace.currentUserRole === 'ADMIN' && activeRepository"
            size="small"
            :loading="syncing"
            :disabled="activeRepository.syncStatus === 'SYNC_PENDING' || activeRepository.syncStatus === 'SYNCING'"
            @click="handleSync"
          >重新同步</el-button>
        </template>
      </LinkedWorkbenchShell>
      <el-skeleton v-else :rows="12" animated />

      <el-drawer
        v-model="projectScanDrawerOpen"
        title="项目结构分析"
        size="420px"
        append-to-body
      >
        <section v-if="projectScanJob" class="project-scan-panel">
          <el-tag :type="projectJobTerminal ? 'success' : 'primary'">
            {{ projectScanPhaseLabel }}
          </el-tag>
          <el-progress
            v-if="!projectJobTerminal"
            :percentage="projectScanProgress"
            :indeterminate="projectScanJob.status === 'RUNNING'"
          />
          <template v-if="projectScanJob.plannedUnitCount > 0">
            <h3>项目结构分析完成</h3>
            <dl class="project-scan-stats">
              <div><dt>发现文件</dt><dd>{{ projectScanJob.discoveredFileCount }}</dd></div>
              <div><dt>支持代码</dt><dd>{{ projectScanJob.supportedCodeCount }}</dd></div>
              <div><dt>语义模块</dt><dd>{{ projectScanJob.analysisUnitCount }}</dd></div>
              <div><dt>存在于多个模块的文件</dt><dd>{{ projectScanJob.overlappingFileCount }}</dd></div>
            </dl>
            <dl class="project-scan-stats">
              <div><dt>正在处理</dt><dd>{{ projectScanJob.runningUnitCount }}</dd></div>
              <div><dt>已提交评审</dt><dd>{{ projectScanJob.reviewSubmittedUnitCount }}</dd></div>
              <div><dt>无需修改</dt><dd>{{ projectScanJob.noChangeUnitCount }}</dd></div>
              <div><dt>失败</dt><dd>{{ projectScanJob.failedUnitCount }}</dd></div>
            </dl>
            <p v-if="projectScanJob.currentUnitNames.length" class="project-scan-hint">
              正在处理：{{ projectScanJob.currentUnitNames.join('、') }}
            </p>
            <h4>语义模块预览</h4>
            <el-empty v-if="projectScanUnits.length === 0" description="暂无语义模块" />
            <ul v-else class="project-scan-units">
              <li v-for="unit in projectScanUnits" :key="unit.unitId">
                <strong>{{ unit.displayName }}</strong>
                <span>{{ unit.semanticKind }} · {{ unit.primaryFiles.length }} 个主文件</span>
              </li>
            </ul>
            <el-button
              v-if="projectScanJob.reviewSubmittedUnitCount > 0"
              type="primary"
              @click="handleReviewNavigation"
            >进入审批区</el-button>
          </template>
          <el-alert
            v-else-if="projectScanJob.status === 'FAILED'"
            :title="projectScanJob.errorMessage || '项目扫描失败'"
            type="error"
            show-icon
            :closable="false"
          />
          <p v-else class="project-scan-hint">扫描在后台运行，关闭当前页面不会取消任务。</p>
        </section>
      </el-drawer>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import {
  createAgentJob,
  listAgentJobUnits,
  readableAgentError,
  type AgentJob,
  type AgentSemanticUnit,
} from '@/api/agent';
import {
  getDocument,
  listDocumentTree,
  listDocumentVersions,
  type DocumentSummary,
  type DocumentTreeNode,
  type DocumentVersion,
} from '@/api/document';
import {
  getGitRepositorySource,
  listCodeBindingsContext,
  listGitChanges,
  listGitRepositories,
  listGitRepositoryFiles,
  queryCodeBindings,
  resolveBlockFileContext,
  syncGitRepository,
  type CodeBindingContextItem,
  type CodeBindingQueryItem,
  type GitRepository,
  type GitChange,
  type GitRepositoryFile,
  type GitRepositorySource,
} from '@/api/git';
import { getWorkspace, type Workspace } from '@/api/workspace';
import AppSidebar from '@/components/layout/AppSidebar.vue';
import LinkedRepositoryContext from '@/components/linked-workbench/LinkedRepositoryContext.vue';
import LinkedWorkbenchShell from '@/components/linked-workbench/LinkedWorkbenchShell.vue';
import NotificationCenter from '@/components/notification/NotificationCenter.vue';
import { useDocumentCollaboration } from '@/composables/useDocumentCollaboration';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';
import {
  createLinkedWorkbenchSnapshot,
  useLinkedWorkbenchNavigation,
  type LinkedWorkbenchSnapshot,
} from '@/composables/useLinkedWorkbenchNavigation';
import { useLinkedWorkbenchState } from '@/composables/useLinkedWorkbenchState';
import type { LinkedDocumentChoice, LinkActivationSource, WorkbenchMode } from '@/types/linkedWorkbench';
import { readableError } from '@/utils/error';
import {
  bindingDocumentChoices,
  buildBindingFixture,
  computeDocumentScopeFileLinkCounts,
  displayableBindings,
  expandContextBindingsToQueryItems,
  selectDefaultBinding,
  sortBindings,
} from '@/utils/linkedWorkbenchBindings';
import { focusPlan } from '@/utils/linkedWorkbenchInteraction';
import {
  buildRepositoryTree,
  normalizeRepositoryPath,
} from '@/utils/repositoryTree';

const route = useRoute();
const router = useRouter();
const workspaceId = computed(() => String(route.params.workspaceId || ''));
const workspace = ref<Workspace | null>(null);
const repositories = ref<GitRepository[]>([]);
const files = ref<GitRepositoryFile[]>([]);
const gitChanges = ref<GitChange[]>([]);
const documentTree = ref<DocumentTreeNode[]>([]);
const document = ref<DocumentSummary | null>(null);
const versions = ref<DocumentVersion[]>([]);
const selectedSource = ref<GitRepositorySource | null>(null);
const selectedFileBindings = ref<CodeBindingQueryItem[]>([]);
const contextLoading = ref(false);
const sourceLoading = ref(false);
const documentLoading = ref(false);
const isRestoringNavigation = ref(false);
const syncing = ref(false);
const projectScanStarting = ref(false);
const projectScanJobId = ref<string | null>(null);
const projectScanJob = ref<AgentJob | null>(null);
const projectScanUnits = ref<AgentSemanticUnit[]>([]);
const projectScanDrawerOpen = ref(false);
let sourceRequestSequence = 0;
let documentRequestSequence = 0;
let reverseBindingRequestSequence = 0;
let focusRequestSequence = 0;
let workbenchUnmounted = false;
const reverseBindingContext = ref<CodeBindingQueryItem[] | null>(null);

interface DocumentScope {
  items: CodeBindingContextItem[];
  active: boolean;
  source: 'document' | 'block';
}

const documentScope = ref<DocumentScope>({
  items: [],
  active: false,
  source: 'document',
});
const errorMessage = ref('');
const sidebarCollapsed = ref(localStorage.getItem('devcollab.sidebar.collapsed') === 'true');
const sidebarNavigationActive = ref<'linked' | 'review' | 'drift'>('linked');
const workbenchShellRef = ref<InstanceType<typeof LinkedWorkbenchShell> | null>(null);
const backgroundActivity = useBackgroundActivityStore();

const state = useLinkedWorkbenchState();
const {
  mode, inspectorOpen, activeLinkId, selectedRepositoryId, selectedFilePath, selectedDocumentId,
  selectedBlockId,
  codeAnchors, documentBlocks, links, issues, activeLink, activeCodeAnchor, activeDocumentBlock,
  activeIssue, activeEvidence, driftedLinkIds, activeLinkIndex, linkCount,
  canSelectPreviousLink, canSelectNextLink,
  toggleInspector,
  unboundBlockId, clearUnboundBlock,
} = state;

const activeRepository = computed(() => repositories.value.find(item => item.id === selectedRepositoryId.value) ?? null);
const linkedNavigationScope = computed(() => {
  const repository = activeRepository.value;
  const revision = repository?.lastSyncedCommit;
  if (!repository || !revision) return null;
  return {
    workspaceId: workspaceId.value,
    repositoryId: repository.id,
    revision,
  };
});
const linkedNavigation = useLinkedWorkbenchNavigation(linkedNavigationScope);
const fileTree = computed(() => buildRepositoryTree(files.value));
const documentChoices = computed(() => flattenDocumentTree(documentTree.value));
const latestVersion = computed(() => versions.value.reduce(
  (latest, item) => Math.max(latest, item.versionNo),
  0,
));
const relatedDocumentChoices = computed<LinkedDocumentChoice[]>(() => {
  return bindingDocumentChoices(selectedFileBindings.value, documentChoices.value)
    .map(item => item.id === selectedDocumentId.value
      ? {
          ...item,
          version: latestVersion.value || undefined,
          reviewStatus: document.value?.reviewStatus,
        }
      : item);
});
const pendingReviewCount = computed(() => backgroundActivity.pendingReviewCount);
const projectScanSnapshot = computed(() => (
  backgroundActivity.getJobSnapshot(projectScanJobId.value)
));
const fileLinkCounts = computed<Record<string, number>>(() => {
  if (!selectedDocumentId.value) return {};
  if (documentScope.value.active) {
    return computeDocumentScopeFileLinkCounts(
      documentScope.value.items,
      selectedRepositoryId.value,
    );
  }
  return selectedFilePath.value
    ? {
        [selectedFilePath.value]: links.value.filter(
          item => normalizeRepositoryPath(item.filePath ?? '') === normalizeRepositoryPath(selectedFilePath.value),
        ).length,
      }
    : {};
});
const activeLinkedBlockCount = computed(() => activeCodeAnchor.value
  ? links.value.filter(link => link.codeAnchorId === activeCodeAnchor.value?.id).length
  : 0);
const activeUnresolvedIssueCount = computed(() => activeCodeAnchor.value
  ? issues.value.filter(issue => issue.codeAnchorId === activeCodeAnchor.value?.id
    && issue.status === 'OPEN').length
  : 0);
const recentCommitCount = computed(() => gitChanges.value.filter(change =>
  change.files.some(file => file.path === selectedFilePath.value || file.oldPath === selectedFilePath.value),
).length);
const documentReadonly = computed(() => document.value?.reviewStatus === 'DEPRECATED' || document.value?.reviewStatus === 'SUPERSEDED');
const projectScanPhaseLabels: Partial<Record<NonNullable<AgentJob['phase']>, string>> = {
  DISCOVERING_FILES: '正在发现文件',
  CLASSIFYING_FILES: '正在分类文件',
  LOADING_CODE_METADATA: '正在读取代码结构',
  LOADING_BINDINGS: '正在读取已有 Binding',
  BUILDING_SEMANTIC_GRAPH: '正在构建语义关系',
  BUILDING_ANALYSIS_UNITS: '正在构建语义模块',
  READY_FOR_ANALYSIS: '项目结构分析完成',
  PLANNING_UNITS: '正在由 DeepSeek 划分语义模块',
  VALIDATING_UNIT_PLAN: '正在校验语义模块计划',
  EXECUTING_UNITS: '正在逐个生成正式文档',
  COMPLETED: '项目处理完成',
};
const projectJobTerminal = computed(() => [
  'COMPLETED',
  'PARTIALLY_COMPLETED',
  'FAILED',
  'CANCELLED',
].includes(projectScanJob.value?.status ?? ''));
const projectScanPhaseLabel = computed(() => {
  const phase = projectScanJob.value?.phase;
  return phase ? projectScanPhaseLabels[phase] ?? '等待后台扫描' : '等待后台扫描';
});
const projectScanProgress = computed(() => {
  if (projectScanJob.value?.plannedUnitCount) {
    return Math.round(
      ((projectScanJob.value.completedUnitCount + projectScanJob.value.failedUnitCount)
        / projectScanJob.value.plannedUnitCount) * 100,
    );
  }
  const phases: AgentJob['phase'][] = [
    'DISCOVERING_FILES',
    'CLASSIFYING_FILES',
    'LOADING_CODE_METADATA',
    'LOADING_BINDINGS',
    'BUILDING_SEMANTIC_GRAPH',
    'BUILDING_ANALYSIS_UNITS',
    'PLANNING_UNITS',
    'VALIDATING_UNIT_PLAN',
    'EXECUTING_UNITS',
    'COMPLETED',
    'READY_FOR_ANALYSIS',
  ];
  const index = phases.indexOf(projectScanJob.value?.phase ?? null);
  return index < 0 ? 5 : Math.round(((index + 1) / phases.length) * 100);
});

const {
  connected: collaborationConnected,
  members: collaborationMembers,
  editingStates: collaborationEditingStates,
  latestRemoteBlock: collaborationRemoteBlock,
  startEditing,
  stopEditing,
  updateContent: updateBlockContentViaCollaboration,
} = useDocumentCollaboration(workspaceId, selectedDocumentId);

onMounted(() => {
  backgroundActivity.setActiveWorkspace(workspaceId.value);
  void initializeWorkbench();
});
const pollTimer = window.setInterval(() => {
  if (repositories.value.some(item => item.syncStatus === 'SYNC_PENDING' || item.syncStatus === 'SYNCING')) {
    void refreshSyncState();
  }
}, 2500);
onBeforeUnmount(() => {
  workbenchUnmounted = true;
  sourceRequestSequence += 1;
  documentRequestSequence += 1;
  reverseBindingRequestSequence += 1;
  focusRequestSequence += 1;
  window.clearInterval(pollTimer);
});

watch(projectScanSnapshot, job => void updateProjectScanSnapshot(job));

async function initializeWorkbench() {
  isRestoringNavigation.value = true;
  try {
    await loadWorkbench();
    restoreProjectScan();
  } finally {
    isRestoringNavigation.value = false;
  }
}

async function loadWorkbench() {
  if (!workspaceId.value) { errorMessage.value = '工作区地址无效'; return; }
  contextLoading.value = true;
  try {
    [workspace.value, repositories.value, documentTree.value] = await Promise.all([
      getWorkspace(workspaceId.value),
      listGitRepositories(workspaceId.value),
      listDocumentTree(workspaceId.value),
    ]);
    const queryRepositoryId = typeof route.query.repositoryId === 'string' ? route.query.repositoryId : '';
    const lastScope = linkedNavigation.restoreLastScope(workspaceId.value);
    const lastRepository = repositories.value.find(item =>
      item.id === lastScope?.repositoryId && item.lastSyncedCommit === lastScope.revision);
    state.selectedRepositoryId.value = repositories.value.some(item => item.id === queryRepositoryId)
      ? queryRepositoryId
      : lastRepository?.id || repositories.value[0]?.id || '';
    const navigationIntent = resolveNavigationIntent();
    state.selectFile(navigationIntent?.filePath || '');
    state.selectDocument(navigationIntent?.documentId || '');
    await loadRepositoryDetails('restore', navigationIntent);
  } catch (error) {
    errorMessage.value = readableError(error, '关联工作台加载失败');
  } finally {
    contextLoading.value = false;
  }
}

function resolveNavigationIntent(): LinkedWorkbenchSnapshot | null {
  const scope = linkedNavigationScope.value;
  if (!scope) return null;
  const queryFilePath = typeof route.query.filePath === 'string'
    ? normalizeRepositoryPath(route.query.filePath)
    : '';
  const queryDocumentId = typeof route.query.documentId === 'string'
    ? route.query.documentId
    : '';
  if (queryFilePath) {
    return createLinkedWorkbenchSnapshot(
      scope,
      queryFilePath,
      documentChoices.value.some(item => item.id === queryDocumentId) ? queryDocumentId : null,
    );
  }
  return linkedNavigation.restoreCurrent(scope);
}

function findRepositoryFile(path: string | null | undefined) {
  if (!path) return null;
  const normalizedTarget = normalizeRepositoryPath(path);
  return files.value.find(file =>
    normalizeRepositoryPath(file.path) === normalizedTarget && file.readable) ?? null;
}

function defaultRepositoryFile() {
  const readableFiles = files.value.filter(file => file.readable);
  return readableFiles.find(file =>
    normalizeRepositoryPath(file.path).toLocaleLowerCase() === 'readme.md')
    ?? readableFiles.find(file => {
      const firstSegment = normalizeRepositoryPath(file.path).split('/')[0];
      return firstSegment && !firstSegment.startsWith('.');
    })
    ?? readableFiles[0]
    ?? files.value[0];
}

async function loadRepositoryDetails(
  historyMode: 'navigate' | 'restore' = 'restore',
  navigationTarget: LinkedWorkbenchSnapshot | null = null,
) {
  if (!selectedRepositoryId.value) {
    files.value = [];
    gitChanges.value = [];
    selectedSource.value = null;
    selectedFileBindings.value = [];
    document.value = null;
    versions.value = [];
    return;
  }
  [files.value, gitChanges.value] = await Promise.all([
    listGitRepositoryFiles(workspaceId.value, selectedRepositoryId.value),
    listGitChanges(workspaceId.value, selectedRepositoryId.value).catch(() => []),
  ]);
  const requestedPath = navigationTarget?.filePath || selectedFilePath.value;
  const requestedFile = findRepositoryFile(requestedPath);
  const requestedFileInvalid = Boolean(requestedPath && !requestedFile);
  if (requestedFileInvalid) {
    state.selectFile('');
    state.selectDocument('');
    if (linkedNavigationScope.value) {
      linkedNavigation.updateCurrent(createLinkedWorkbenchSnapshot(
        linkedNavigationScope.value,
        null,
        null,
      ));
    }
  }
  const current = requestedFile ?? defaultRepositoryFile();
  if (current) {
    if (navigationTarget?.documentId && requestedFile) {
      state.selectDocument(navigationTarget.documentId);
    }
    await openSource(current, historyMode, navigationTarget);
  } else if (linkedNavigationScope.value) {
    state.selectFile('');
    state.selectDocument('');
    linkedNavigation.updateCurrent(createLinkedWorkbenchSnapshot(
      linkedNavigationScope.value,
      null,
      null,
    ));
  }
}

async function loadDocumentBundle() {
  const requestSequence = ++documentRequestSequence;
  const documentId = selectedDocumentId.value;
  if (!documentId) { document.value = null; versions.value = []; return; }
  documentLoading.value = true;
  try {
    const [loadedDocument, loadedVersions] = await Promise.all([
      getDocument(documentId), listDocumentVersions(documentId),
    ]);
    if (requestSequence !== documentRequestSequence || documentId !== selectedDocumentId.value) return;
    document.value = loadedDocument;
    versions.value = loadedVersions;
  } catch (error) {
    if (requestSequence !== documentRequestSequence) return;
    document.value = null; versions.value = [];
    ElMessage.error(readableError(error, '关联文档加载失败'));
  } finally {
    if (requestSequence === documentRequestSequence) documentLoading.value = false;
  }
}

async function handleRepositoryChange(repositoryId: string) {
  if (!(await confirmDocumentLeave())) return;
  isRestoringNavigation.value = false;
  sourceRequestSequence += 1;
  state.selectedRepositoryId.value = repositoryId;
  state.selectFile('');
  state.selectDocument('');
  selectedSource.value = null;
  selectedFileBindings.value = [];
    reverseBindingContext.value = null;
    documentScope.value = { items: [], active: false, source: 'document' };
  await router.replace({ query: { ...route.query, repositoryId } });
  try { contextLoading.value = true; await loadRepositoryDetails('navigate'); }
  catch (error) { ElMessage.error(readableError(error, '仓库内容加载失败')); }
  finally { contextLoading.value = false; }
}

async function openSourceByPath(path: string) {
  if (path !== selectedFilePath.value && !(await confirmDocumentLeave())) return;
  isRestoringNavigation.value = false;
  reverseBindingContext.value = null;
  const file = files.value.find(item => item.path === path);
  if (file) await openSource(file, 'navigate');
}

async function openSource(
  file: GitRepositoryFile,
  historyMode: 'navigate' | 'restore' = 'navigate',
  navigationTarget: LinkedWorkbenchSnapshot | null = null,
  bindingContext: CodeBindingQueryItem[] | null = null,
) {
  if (bindingContext === null) {
    reverseBindingContext.value = null;
    // User clicked a file directly — exit document scope.
    if (historyMode === 'navigate') {
      documentScope.value = { items: [], active: false, source: 'document' };
    }
  }
  const requestSequence = ++sourceRequestSequence;
  const repositoryId = selectedRepositoryId.value;
  sourceLoading.value = true;
  state.selectFile(file.path);
  selectedFileBindings.value = [];
    state.replaceFixture({ codeAnchors: [], links: [], issues: [], evidence: [] });
  try {
    const source = await getGitRepositorySource(
      workspaceId.value,
      repositoryId,
      file.path,
    );
    if (requestSequence !== sourceRequestSequence
      || repositoryId !== selectedRepositoryId.value
      || file.path !== selectedFilePath.value) return;
    const revision = source.commitSha
      || repositories.value.find(item => item.id === repositoryId)?.lastSyncedCommit;
    if (!revision) throw new Error('当前仓库尚无可查询的同步版本');
    const bindings = bindingContext ?? (await queryCodeBindings(
      workspaceId.value,
      repositoryId,
      revision,
      file.path,
    )).bindings;
    if (requestSequence !== sourceRequestSequence
      || repositoryId !== selectedRepositoryId.value
      || file.path !== selectedFilePath.value) return;
    selectedSource.value = source;
    selectedFileBindings.value = sortBindings(bindings, revision);
    rebuildBindings(navigationTarget?.bindingId, navigationTarget?.blockId);
    const targetLink = activeLink.value;
    const targetDocumentId = targetLink?.documentId
      ?? navigationTarget?.documentId
      ?? '';
    const boundDocumentIds = new Set(selectedFileBindings.value.map(item => item.documentId));
    if (selectedFileBindings.value.length === 0) {
      state.selectDocument('');
      state.replaceDocumentBlocks([]);
            document.value = null;
      versions.value = [];
      documentRequestSequence += 1;
    } else if (targetDocumentId && boundDocumentIds.has(targetDocumentId)) {
      const documentChanged = selectedDocumentId.value !== targetDocumentId;
      state.selectDocument(targetDocumentId);
      if (documentChanged) {
        state.replaceDocumentBlocks([]);
              }
      if (document.value?.id !== targetDocumentId) await loadDocumentBundle();
    } else if (!boundDocumentIds.has(selectedDocumentId.value)) {
      state.selectDocument(activeLink.value?.documentId || selectedFileBindings.value[0].documentId);
      state.replaceDocumentBlocks([]);
            await router.replace({
        query: { ...route.query, repositoryId, documentId: selectedDocumentId.value },
      });
      await loadDocumentBundle();
      if (requestSequence !== sourceRequestSequence
        || repositoryId !== selectedRepositoryId.value
        || file.path !== selectedFilePath.value) return;
    } else if (document.value?.id !== selectedDocumentId.value) {
      await loadDocumentBundle();
    }
    if (requestSequence !== sourceRequestSequence
      || repositoryId !== selectedRepositoryId.value
      || file.path !== selectedFilePath.value) return;
    persistReadingTarget(historyMode);
    await updateWorkbenchRoute();
    rebuildBindings(activeLink.value?.bindingId, activeLink.value?.blockId);
    await nextTick();
    focusActiveLink('system');
  } catch (error) {
    if (requestSequence !== sourceRequestSequence) return;
    selectedSource.value = null;
    selectedFileBindings.value = [];
    rebuildBindings();
    ElMessage.error(readableError(error, '源码读取失败'));
  } finally {
    if (requestSequence === sourceRequestSequence) sourceLoading.value = false;
  }
}

async function handleDocumentChange(documentId: string) {
  isRestoringNavigation.value = false;
  if (documentId === selectedDocumentId.value) return;
  if (!(await confirmDocumentLeave())) return;
  state.selectDocument(documentId);
  state.replaceDocumentBlocks([]);
  await router.replace({ query: { ...route.query, documentId } });
  await loadDocumentBundle();
  persistReadingTarget('navigate');
  await updateWorkbenchRoute();

  const revision = activeRepository.value?.lastSyncedCommit;
  if (!revision) return;
  const requestSequence = ++reverseBindingRequestSequence;
  try {
    const contextItems = await listCodeBindingsContext(documentId, {
      revision,
      includeLegacy: true,
    });
    if (workbenchUnmounted
      || requestSequence !== reverseBindingRequestSequence
      || documentId !== selectedDocumentId.value) return;

    const repoItems = contextItems
      .filter(item => repositories.value.some(repo => repo.id === item.repositoryId));
    const choices = expandContextBindingsToQueryItems(repoItems, selectedFilePath.value);
    documentScope.value = {
      items: repoItems,
      active: choices.length > 0,
      source: 'document',
    };
    reverseBindingContext.value = choices.length > 0
      ? sortBindings(choices, revision)
      : null;
    if (reverseBindingContext.value) selectedFileBindings.value = reverseBindingContext.value;
    const preferred = reverseBindingContext.value?.find(item => item.documentId === documentId);
    rebuildBindings(preferred?.bindingId, preferred?.blockId);
    persistReadingTarget('restore');
  } catch (error) {
    if (requestSequence === reverseBindingRequestSequence) {
      ElMessage.error(readableError(error, '文档关联查询失败'));
    }
  }
}

function persistReadingTarget(historyMode: 'navigate' | 'restore') {
  if (!linkedNavigationScope.value) return;
  const snapshot = createCurrentSnapshot();
  if (historyMode === 'navigate') linkedNavigation.navigateTo(snapshot);
  else linkedNavigation.updateCurrent(snapshot);
}

function createCurrentSnapshot(link = activeLink.value) {
  return createLinkedWorkbenchSnapshot(
    linkedNavigationScope.value!,
    link?.filePath || selectedFilePath.value || null,
    link?.documentId || selectedDocumentId.value || null,
    link?.bindingId || null,
    link?.blockId ?? selectedBlockId.value,
  );
}

async function updateWorkbenchRoute() {
  await router.replace({
    name: 'workspace-code',
    params: { workspaceId: workspaceId.value },
    query: {
      repositoryId: selectedRepositoryId.value || undefined,
      filePath: selectedFilePath.value || undefined,
      documentId: selectedDocumentId.value || undefined,
    },
  });
}

async function restoreReadingTarget(snapshot: LinkedWorkbenchSnapshot) {
  const repository = repositories.value.find(item =>
    item.id === snapshot.repositoryId && item.lastSyncedCommit === snapshot.revision);
  if (!repository) return;
  isRestoringNavigation.value = true;
  try {
    if (selectedRepositoryId.value !== repository.id) {
      sourceRequestSequence += 1;
      state.selectedRepositoryId.value = repository.id;
      state.selectFile('');
      state.selectDocument(snapshot.documentId || '');
      selectedSource.value = null;
      selectedFileBindings.value = [];
      await loadRepositoryDetails('restore', snapshot);
      return;
    }
    state.selectDocument(snapshot.documentId || '');
    const file = findRepositoryFile(snapshot.filePath);
    if (file) {
      await openSource(file, 'restore', snapshot);
      return;
    }
    await loadRepositoryDetails('restore', snapshot);
  } finally {
    isRestoringNavigation.value = false;
  }
}

async function handleHistoryBack() {
  if (!(await confirmDocumentLeave())) return;
  const target = linkedNavigation.goBack();
  if (target) await restoreReadingTarget(target);
}

async function handleHistoryForward() {
  if (!(await confirmDocumentLeave())) return;
  const target = linkedNavigation.goForward();
  if (target) await restoreReadingTarget(target);
}

function historyTargetTitle(action: string, target: LinkedWorkbenchSnapshot | null) {
  const fileName = target?.filePath?.split('/').at(-1);
  return fileName ? `${action}到 ${fileName}` : action;
}

function handleBlocksLoaded(blocks: DocumentBlock[]) {
  state.replaceDocumentBlocks(blocks);
  const blockSortOrders = documentBlocks.value.length > 0
    && documentBlocks.value[0].documentId === selectedDocumentId.value
    ? new Map(documentBlocks.value.map(block => [block.id, block.sortOrder]))
    : undefined;
  rebuildBindings(
    activeLink.value?.bindingId,
    activeLink.value?.blockId,
    blockSortOrders,
  );
  void nextTick(() => focusActiveLink('system'));
}

function rebuildBindings(
  preferredBindingId: string | null = activeLink.value?.bindingId ?? null,
  preferredBlockId: string | null = null,
  blockSortOrders?: Map<string, number>,
) {
  if (!selectedSource.value?.readable || selectedSource.value.content === null || !activeRepository.value) {
    state.replaceFixture({ codeAnchors: [], links: [], issues: [], evidence: [] });
    return;
  }
  const fixtureInput = {
    repositoryId: activeRepository.value.id,
    branch: activeRepository.value.defaultBranch,
    commitSha: selectedSource.value.commitSha || activeRepository.value.lastSyncedCommit || 'working-tree',
    source: selectedSource.value,
    bindings: selectedFileBindings.value,
    allowCrossFile: reverseBindingContext.value !== null,
    activeDocumentId: selectedDocumentId.value || null,
    blockSortOrders,
  };
  const fixture = buildBindingFixture(fixtureInput);
  state.replaceFixture(fixture);
  const revision = selectedSource.value.commitSha || activeRepository.value.lastSyncedCommit || '';
  const visibleBindings = displayableBindings(fixtureInput);
  const selected = selectDefaultBinding(
    visibleBindings,
    revision,
    preferredBindingId,
    preferredBlockId,
    selectedFilePath.value,
    fixtureInput.blockSortOrders,
  );
  if (selected) state.activateLink(`binding-link-${selected.bindingId}`, 'system');
}

async function handleActivate(linkId: string, source: LinkActivationSource) {
  state.activateLink(linkId, source);
  const link = activeLink.value;
  if (!link) return;
  if (link.filePath && normalizeRepositoryPath(link.filePath) !== normalizeRepositoryPath(selectedFilePath.value)) {
    const file = findRepositoryFile(link.filePath);
    if (!file) {
      ElMessage.warning('关联的代码文件已不存在');
      return;
    }
    const snapshot = createCurrentSnapshot(link);
    await openSource(file, 'navigate', snapshot, reverseBindingContext.value);
    return;
  }
  if (link.documentId !== selectedDocumentId.value) {
    if (!(await confirmDocumentLeave())) return;
    state.selectDocument(link.documentId);
    state.replaceDocumentBlocks([]);
        await loadDocumentBundle();
  }
  persistReadingTarget('restore');
  await updateWorkbenchRoute();
  await nextTick();
  focusActiveLink(source);
}

async function handleBlockSelection(blockId: string) {
  clearUnboundBlock();
  state.selectDocumentBlock(blockId);
  const requestSequence = ++reverseBindingRequestSequence;
  const documentId = selectedDocumentId.value;
  const revision = activeRepository.value?.lastSyncedCommit;
  if (!documentId || !revision) return;
  try {
    const ctx = await resolveBlockFileContext(documentId, {
      blockId,
      revision,
      includeLegacy: true,
    });
    if (workbenchUnmounted
      || requestSequence !== reverseBindingRequestSequence
      || documentId !== selectedDocumentId.value) return;
    if (!ctx) {
      state.selectUnboundDocumentBlock(blockId);
      persistReadingTarget('restore');
      return;
    }

    documentScope.value = {
      items: [{
        bindingId: ctx.preferredBindingId,
        workspaceId: ctx.workspaceId,
        repositoryId: ctx.repositoryId,
        revision,
        anchorKind: 'FILE' as const,
        symbolKey: null,
        startLine: null,
        endLine: null,
        documentId: ctx.documentId,
        blockId: ctx.blockId,
        targetKey: 'BLOCK',
        pathPattern: ctx.filePath,
        documentTitle: document.value?.title ?? null,
        matchedFilePath: ctx.filePath,
        matchingFilePaths: [ctx.filePath],
        blockExists: true,
      }],
      active: true,
      source: 'block',
    };

    reverseBindingContext.value = ctx.bindings;

    const targetFile = findRepositoryFile(ctx.filePath);
    if (!targetFile) {
      ElMessage.warning('关联的代码文件不可读取');
      return;
    }

    // Cross-repo navigation.
    if (ctx.repositoryId !== selectedRepositoryId.value) {
      state.selectedRepositoryId.value = ctx.repositoryId;
      await loadRepositoryDetails('navigate', createLinkedWorkbenchSnapshot(
        { workspaceId: workspaceId.value, repositoryId: ctx.repositoryId, revision },
        ctx.filePath,
        ctx.documentId,
        ctx.preferredBindingId,
        ctx.blockId,
      ));
      return;
    }

    // Navigate to the bound file if different from current.
    if (normalizeRepositoryPath(ctx.filePath) !== normalizeRepositoryPath(selectedFilePath.value)) {
      await openSource(targetFile, 'navigate', null, ctx.bindings);
      void nextTick(() => {
        state.activateLink(`binding-link-${ctx.preferredBindingId}`, 'document');
        focusActiveLink('document');
      });
      return;
    }

    // Already on the bound file — refresh bindings in place.
    selectedFileBindings.value = ctx.bindings;
    rebuildBindings(ctx.preferredBindingId, blockId);
    await handleActivate(`binding-link-${ctx.preferredBindingId}`, 'document');
  } catch (error) {
    if (requestSequence === reverseBindingRequestSequence) {
      ElMessage.error(readableError(error, 'Block 代码关联查询失败'));
    }
  }
}

async function handleAdjacentLink(direction: 'previous' | 'next') {
  const link = direction === 'previous'
    ? state.selectPreviousLink('rail')
    : state.selectNextLink('rail');
  if (link) await handleActivate(link.id, 'rail');
}

async function handleModeChange(nextMode: WorkbenchMode) {
  sidebarNavigationActive.value = nextMode === 'DRIFT_REVIEW' ? 'drift' : 'linked';
  state.setMode(nextMode);
  await nextTick();
  focusActiveLink('system');
}

async function handleLinkedNavigation() {
  sidebarNavigationActive.value = 'linked';
  await handleModeChange('LINKED');
}

function confirmDocumentLeave() {
  return workbenchShellRef.value?.confirmDocumentLeave?.() ?? Promise.resolve(true);
}

async function handleReviewNavigation() {
  await router.push({
    name: 'workspace-reviews',
    params: {
      workspaceId: workspaceId.value,
      status: 'pending',
    },
    query: {
      repositoryId: selectedRepositoryId.value || undefined,
    },
  });
}

async function handleAgentReviewNavigation(changeRequestId: string | null) {
  await router.push(changeRequestId ? {
    name: 'workspace-review-detail',
    params: {
      workspaceId: workspaceId.value,
      status: 'pending',
      requestId: changeRequestId,
    },
    query: {
      repositoryId: selectedRepositoryId.value || undefined,
    },
  } : {
    name: 'workspace-reviews',
    params: {
      workspaceId: workspaceId.value,
      status: 'pending',
    },
    query: {
      repositoryId: selectedRepositoryId.value || undefined,
    },
  });
}

async function handleDocumentPaneAgentCheck() {
  if (!selectedRepositoryId.value || !selectedFilePath.value) return;
  try {
    const queued = await createAgentJob({
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      scope: { type: 'CURRENT_FILE', filePath: selectedFilePath.value },
      userInstruction: unboundBlockId.value
        ? `请检查当前文件是否与文档 Block (ID: ${unboundBlockId.value.slice(0, 12)}) 有关联`
        : null,
    });
    backgroundActivity.registerJob({
      jobId: queued.jobId,
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      label: selectedFilePath.value,
      filePath: selectedFilePath.value,
      createdAt: queued.createdAt,
      lastKnownStatus: queued.status,
    });
    clearUnboundBlock();
    ElMessage.success('Agent 已在后台开始处理，分析完成后将检查 Block 关联。');
  } catch (error) {
    ElMessage.error(readableAgentError(error, 'Agent 检查启动失败'));
  }
}

function focusActiveLink(source: LinkActivationSource) {
  const link = activeLink.value;
  if (!link) return;
  const requestSequence = ++focusRequestSequence;
  const plan = focusPlan(source);
  if (plan.code) workbenchShellRef.value?.focusAnchor(link.codeAnchorId);
  if (!plan.document) return;
  if (!link.blockId) {
    workbenchShellRef.value?.clearBlockFocus();
    return;
  }
  void nextTick(() => {
    if (workbenchUnmounted || requestSequence !== focusRequestSequence) return;
    if (!documentBlocks.value.some(block => block.id === link.blockId)) {
      workbenchShellRef.value?.clearBlockFocus();
      if (documentBlocks.value.length > 0) ElMessage.warning('关联的文档 Block 已不存在');
      return;
    }
    workbenchShellRef.value?.focusBlock(link.blockId!);
  });
}

async function saveBlockViaCollaboration(block: DocumentBlock, content: DocumentBlockContent) {
  return updateBlockContentViaCollaboration(block.id, content, block.version);
}

async function handleSync() {
  if (!selectedRepositoryId.value) return;
  syncing.value = true;
  try {
    const updated = await syncGitRepository(workspaceId.value, selectedRepositoryId.value);
    repositories.value = repositories.value.map(item => item.id === updated.id ? updated : item);
    ElMessage.success('仓库同步任务已提交');
  } catch (error) { ElMessage.error(readableError(error, '仓库同步失败')); }
  finally { syncing.value = false; }
}

async function confirmProjectScan() {
  if (!selectedRepositoryId.value || projectScanStarting.value) return;
  try {
    await ElMessageBox.confirm(
      'Agent 将在后台扫描当前仓库，识别代码文件、已有文档关联和语义模块。本阶段只分析项目结构，不会修改正式文档。',
      '检查整个项目',
      {
        confirmButtonText: '开始扫描',
        cancelButtonText: '取消',
        type: 'info',
      },
    );
  } catch {
    return;
  }
  await startProjectScan();
}

async function startProjectScan() {
  projectScanStarting.value = true;
  try {
    const queued = await createAgentJob({
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      scope: { type: 'PROJECT_INITIALIZATION' },
      userInstruction: null,
    });
    projectScanJobId.value = queued.jobId;
    backgroundActivity.registerJob({
      jobId: queued.jobId,
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      label: activeRepository.value?.name ?? '项目扫描',
      filePath: null,
      createdAt: queued.createdAt,
      lastKnownStatus: queued.status,
    });
    projectScanUnits.value = [];
    projectScanDrawerOpen.value = true;
    ElMessage.success('项目扫描已在后台启动，可以关闭当前窗口。');
  } catch (error) {
    ElMessage.error(readableAgentError(error, '项目扫描启动失败'));
  } finally {
    projectScanStarting.value = false;
  }
}

function restoreProjectScan() {
  projectScanJobId.value = backgroundActivity.findActiveJob({
    workspaceId: workspaceId.value,
    repositoryId: selectedRepositoryId.value,
    filePath: null,
  })?.jobId ?? null;
}

async function updateProjectScanSnapshot(job: AgentJob | null) {
  if (!job || job.jobId !== projectScanJobId.value) return;
  projectScanJob.value = job;
  if (job.plannedUnitCount > 0) {
    try {
      const page = await listAgentJobUnits(job.jobId, 0, 20);
      projectScanUnits.value = page.units;
      projectScanDrawerOpen.value = true;
    } catch {
      // The global poller owns retry behavior; the drawer keeps its last good data.
    }
  }
  if (projectJobTerminal.value) projectScanDrawerOpen.value = true;
}

async function refreshSyncState() {
  const previous = activeRepository.value?.syncStatus;
  repositories.value = await listGitRepositories(workspaceId.value);
  const current = activeRepository.value?.syncStatus;
  if ((previous === 'SYNC_PENDING' || previous === 'SYNCING') && current !== previous && current !== 'SYNC_PENDING' && current !== 'SYNCING') {
    await loadRepositoryDetails();
  }
}

function flattenDocumentTree(nodes: DocumentTreeNode[], depth = 0): LinkedDocumentChoice[] {
  return nodes.flatMap(node => [
    { id: node.id, title: node.title, depth },
    ...flattenDocumentTree(node.children, depth + 1),
  ]);
}
</script>

<style scoped>
.linked-page-shell { height: 100vh; overflow: hidden; }
.linked-page-main { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto auto minmax(0, 1fr); gap: 8px; overflow: hidden; padding: 12px 14px 0; background: #f2f5f9; }
.linked-page-main > .linked-workbench-shell:first-child { grid-row: 1 / -1; }
.project-scan-panel { display: grid; gap: 16px; }
.project-scan-stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; }
.project-scan-stats div { padding: 12px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.project-scan-stats dt { color: #64748b; font-size: 12px; }
.project-scan-stats dd { margin: 4px 0 0; color: #0f172a; font-size: 22px; font-weight: 700; }
.project-scan-units { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.project-scan-units li { display: grid; gap: 3px; padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 8px; }
.project-scan-units span, .project-scan-hint { color: #64748b; font-size: 13px; }
@media (max-width: 760px) { .linked-page-shell { height: auto; min-height: 100vh; overflow: visible; } .linked-page-main { min-height: 100vh; } }
</style>
