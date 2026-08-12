<template>
  <main
    class="collaboration-page linked-page-shell"
    data-ark-theme="ark"
    data-ark-depth="moderate"
  >
    <header class="collaboration-topbar">
      <button
        class="collaboration-brand"
        type="button"
        aria-label="返回 ContextWeave 首页"
        @click="navigatePrimary('/')"
      >
        <span class="collaboration-logo" aria-hidden="true"><span /></span>
        <span class="collaboration-brand-name">ContextWeave</span>
        <span class="collaboration-beta">BETA</span>
      </button>

      <WorkspaceContextNavigation :workspace-id="workspaceId" active="linked" />

      <Teleport to="body">
        <div class="collaboration-account">
          <span class="collaboration-avatar" :aria-label="`当前用户：${currentUserName}`">
            {{ currentUserInitial }}
          </span>
        </div>
      </Teleport>
    </header>

    <section class="collaboration-stage">
      <div class="collaboration-workspace-grid">
        <header class="collaboration-workspace-header">
          <div class="workspace-identity">
            <span class="workspace-signal" aria-hidden="true" />
            <div>
              <p>COLLABORATION / WORKSPACE</p>
              <h1>{{ workspace?.name || '协作空间' }}</h1>
              <span class="workspace-context-line">
                <i aria-hidden="true" />
                {{ repositorySyncLabel }}
                <b>·</b>{{ activeRepository?.defaultBranch || '-' }}
                <b>·</b>{{ shortWorkspaceCommit }}
              </span>
            </div>
          </div>
          <div class="workspace-check-actions" aria-label="检查操作">
            <el-button
              v-if="activeRepository"
              data-test="project-scan"
              :loading="projectScanStarting"
              @click="confirmProjectScan"
            >全项目检查 <span aria-hidden="true">↗</span></el-button>
            <el-button
              v-if="activeRepository"
              class="is-primary-check"
              data-test="module-scan"
              :loading="moduleCheckStarting"
              :disabled="!selectedFilePath"
              @click="confirmModuleCheck"
            >模块检查 <span aria-hidden="true">↗</span></el-button>
            <el-button
              v-if="activeRepository"
              data-test="agent-check"
              :loading="agentCheckStarting"
              :disabled="!selectedFilePath"
              @click="agentCheckDialogOpen = true"
            >Agent 检查 <span aria-hidden="true">↗</span></el-button>
          </div>
        </header>

        <div class="collaboration-workspace-body">
          <aside class="collaboration-tree-panel" aria-label="文档与仓库树">
            <header>
              <span>DOCUMENT / TREE</span>
              <strong>文档树</strong>
            </header>
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
              :show-summary="false"
              @select-repository="handleRepositoryChange"
              @select-file="openSourceByPath"
              @select-document="handleDocumentChange"
            />
          </aside>

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
        @set-mode="setMode"
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
      />
      <el-skeleton v-else :rows="12" animated />

      <el-drawer
        v-model="projectScanDrawerOpen"
        title="全项目检查"
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

      <el-dialog
        v-model="agentCheckDialogOpen"
        title="Agent 检查"
        width="460px"
        append-to-body
      >
        <div class="agent-check-dialog">
          <p><strong>检查对象：</strong>{{ selectedFilePath || '尚未选择源码文件' }}</p>
          <el-input
            v-model="agentInstruction"
            type="textarea"
            :rows="4"
            placeholder="补充希望 Agent 重点检查的内容（可选）"
          />
        </div>
        <template #footer>
          <el-button @click="agentCheckDialogOpen = false">取消</el-button>
          <el-button
            type="primary"
            :loading="agentCheckStarting"
            :disabled="!selectedFilePath"
            @click="startAgentCheck"
          >开始检查</el-button>
        </template>
      </el-dialog>
          </section>
        </div>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import type { DocumentBlock, DocumentBlockContent } from '@/api/block';
import {
  createAgentRun,
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
  type CodeBindingContextItem,
  type CodeBindingQueryItem,
  type GitRepository,
  type GitChange,
  type GitRepositoryFile,
  type GitRepositorySource,
} from '@/api/git';
import { getWorkspace, type Workspace } from '@/api/workspace';
import LinkedRepositoryContext from '@/components/linked-workbench/LinkedRepositoryContext.vue';
import LinkedWorkbenchShell from '@/components/linked-workbench/LinkedWorkbenchShell.vue';
import { useDocumentCollaboration } from '@/composables/useDocumentCollaboration';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';
import { useAuthStore } from '@/stores/auth';
import {
  createLinkedWorkbenchSnapshot,
  useLinkedWorkbenchNavigation,
  type LinkedWorkbenchSnapshot,
} from '@/composables/useLinkedWorkbenchNavigation';
import { useLinkedWorkbenchState } from '@/composables/useLinkedWorkbenchState';
import type { LinkedDocumentChoice, LinkActivationSource } from '@/types/linkedWorkbench';
import { readableError } from '@/utils/error';
import WorkspaceContextNavigation from '@/components/layout/WorkspaceContextNavigation.vue';
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
const projectScanStarting = ref(false);
const moduleCheckStarting = ref(false);
const agentCheckStarting = ref(false);
const agentCheckDialogOpen = ref(false);
const agentInstruction = ref('');
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
const workbenchShellRef = ref<InstanceType<typeof LinkedWorkbenchShell> | null>(null);
const backgroundActivity = useBackgroundActivityStore();
const authStore = useAuthStore();

const state = useLinkedWorkbenchState();
const {
  mode, activeLinkId, selectedRepositoryId, selectedFilePath, selectedDocumentId,
  selectedBlockId,
  codeAnchors, documentBlocks, links, issues, activeLink, activeCodeAnchor, activeDocumentBlock,
  activeIssue, activeEvidence,
  inspectorOpen, setMode, toggleInspector,
  unboundBlockId, clearUnboundBlock,
} = state;

const currentUserName = computed(() => (
  authStore.currentUser?.displayName
  || authStore.currentUser?.username
  || '用户'
));
const currentUserInitial = computed(() => currentUserName.value.trim().slice(0, 1).toUpperCase() || 'U');

function navigatePrimary(target: string) {
  void router.push(target);
}

const activeRepository = computed(() => repositories.value.find(item => item.id === selectedRepositoryId.value) ?? null);
const shortWorkspaceCommit = computed(() => (
  selectedSource.value?.commitSha
  || activeRepository.value?.lastSyncedCommit
  || '尚无提交'
).slice(0, 10));
const repositorySyncLabel = computed(() => {
  switch (activeRepository.value?.syncStatus) {
    case 'READY': return '同步正常';
    case 'SYNC_PENDING': return '等待同步';
    case 'SYNCING': return '正在同步';
    case 'FAILED': return '同步异常';
    default: return '尚未同步';
  }
});
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

function handleBlocksLoaded(blocks: DocumentBlock[]) {
  state.replaceDocumentBlocks(blocks);
  const sameDocument = documentBlocks.value.length > 0
    && documentBlocks.value[0].documentId === selectedDocumentId.value;
  if (sameDocument) {
    // 块被删除后（DB 级联删除其绑定），缓存 bindings 里的 blockExists
    // 仍是查询时刻的旧值：按最新块列表就地修正为 false，
    // displayableBindings 据此过滤，避免已删块的绑定残留显示。
    const blockIds = new Set(blocks.map(block => block.id));
    selectedFileBindings.value = selectedFileBindings.value.map((binding) => (
      binding.blockId && !blockIds.has(binding.blockId)
        ? { ...binding, blockExists: false }
        : binding
    ));
  }
  const blockSortOrders = sameDocument
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
  await confirmModuleCheck();
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

async function confirmProjectScan() {
  if (!selectedRepositoryId.value || projectScanStarting.value) return;
  try {
    await ElMessageBox.confirm(
      '系统将在后台扫描当前仓库，识别代码文件、已有文档关联和语义模块。本阶段只分析项目结构，不会修改正式文档。',
      '全项目检查',
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

async function confirmModuleCheck() {
  if (!selectedRepositoryId.value || !selectedFilePath.value || moduleCheckStarting.value) return;
  try {
    await ElMessageBox.confirm(
      `将按当前文件所属模块执行检查：${selectedFilePath.value}`,
      '模块检查',
      {
        confirmButtonText: '开始检查',
        cancelButtonText: '取消',
        type: 'info',
      },
    );
  } catch {
    return;
  }
  await startModuleCheck();
}

async function startModuleCheck() {
  if (!selectedRepositoryId.value || !selectedFilePath.value || moduleCheckStarting.value) return;
  moduleCheckStarting.value = true;
  try {
    const filePath = selectedFilePath.value;
    const queued = await createAgentJob({
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      scope: { type: 'CURRENT_FILE', filePath },
      userInstruction: null,
    });
    backgroundActivity.registerJob({
      jobId: queued.jobId,
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      label: `模块检查 ${filePath}`,
      filePath,
      createdAt: queued.createdAt,
      lastKnownStatus: queued.status,
    });
    clearUnboundBlock();
    ElMessage.success('模块检查已在后台启动');
  } catch (error) {
    ElMessage.error(readableAgentError(error, '模块检查启动失败'));
  } finally {
    moduleCheckStarting.value = false;
  }
}

async function startAgentCheck() {
  if (!selectedRepositoryId.value || !selectedFilePath.value || agentCheckStarting.value) return;
  agentCheckStarting.value = true;
  try {
    const queued = await createAgentRun({
      workspaceId: workspaceId.value,
      repositoryId: selectedRepositoryId.value,
      selectedPaths: [selectedFilePath.value],
      userInstruction: agentInstruction.value.trim() || null,
    });
    agentCheckDialogOpen.value = false;
    agentInstruction.value = '';
    ElMessage.success(`Agent 检查已提交（${queued.runId.slice(0, 8)}）`);
  } catch (error) {
    ElMessage.error(readableAgentError(error, 'Agent 检查启动失败'));
  } finally {
    agentCheckStarting.value = false;
  }
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
.linked-page-shell { height: 100vh; overflow: hidden; color: #101820; background: #eef3f5; }
.collaboration-page { display: grid; grid-template-rows: 72px minmax(0, 1fr); }
.collaboration-topbar { position: relative; z-index: 20; display: grid; grid-template-columns: minmax(240px, 1fr) auto minmax(240px, 1fr); align-items: center; padding: 0 28px; border-bottom: 1px solid rgba(202, 213, 220, .78); background: rgba(255, 255, 255, .94); box-shadow: 0 7px 24px rgba(28, 46, 58, .055); backdrop-filter: blur(18px); }
.collaboration-account { position: fixed; top: 16px; right: 28px; z-index: 120; display: flex; }
.collaboration-avatar { display: grid; width: 39px; height: 39px; place-items: center; border-radius: 50%; background: #0b1015; color: #fff; font-size: 15px; font-weight: 700; }
.collaboration-brand { display: flex; width: max-content; align-items: center; gap: 11px; border: 0; padding: 0; background: transparent; color: #0c1116; cursor: pointer; }
.collaboration-logo { position: relative; width: 38px; height: 38px; overflow: hidden; border-radius: 10px; background: #0b1015; }
.collaboration-logo::before, .collaboration-logo::after, .collaboration-logo span { position: absolute; left: 9px; width: 20px; height: 2px; content: ''; background: #18d1ff; transform: skewX(-28deg); }
.collaboration-logo::before { top: 11px; }
.collaboration-logo span { top: 18px; width: 13px; }
.collaboration-logo::after { top: 25px; }
.collaboration-brand-name { font-size: 20px; font-weight: 760; letter-spacing: -.035em; }
.collaboration-brand:focus-visible, .collaboration-navigation-item:focus-visible, .workspace-check-actions :deep(.el-button:focus-visible) { outline: 2px solid #18d1ff; outline-offset: 3px; }
.collaboration-beta { padding: 4px 7px; border: 1px solid #dfe5e8; border-radius: 5px; background: #f3f5f6; color: #74808a; font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.collaboration-navigation { display: flex; align-items: center; gap: 3px; padding: 5px; border: 1px solid #e3e8eb; border-radius: 999px; background: #f0f2f3; }
.collaboration-navigation-item { min-width: 82px; border: 0; border-radius: 999px; padding: 10px 14px; background: transparent; color: #78838c; font: inherit; font-size: 14px; cursor: pointer; transition: color .18s ease, background .18s ease, box-shadow .18s ease; }
.collaboration-navigation-item:not(:disabled):hover { color: #0a1015; }
.collaboration-navigation-item.is-active { background: #0b1015; color: #fff; box-shadow: 0 6px 14px rgba(8, 15, 20, .17); }
.collaboration-navigation-item.is-active span { display: inline-block; width: 6px; height: 6px; margin-left: 7px; border-radius: 50%; background: #17d5f2; vertical-align: 2px; }
.collaboration-navigation-item:disabled:not(.is-active) { opacity: .42; cursor: not-allowed; }
.collaboration-stage { min-height: 0; overflow: hidden; padding: 18px 22px 22px; background-color: #eef4f6; background-image: radial-gradient(circle at 1px 1px, rgba(27, 170, 199, .16) 1px, transparent 1.15px), radial-gradient(circle at 18% 8%, rgba(34, 213, 237, .13), transparent 25%), radial-gradient(circle at 82% 92%, rgba(90, 177, 210, .12), transparent 28%); background-size: 22px 22px, auto, auto; }
.collaboration-workspace-grid { display: grid; height: 100%; min-height: 0; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; border: 1px solid rgba(215, 224, 229, .92); border-radius: 18px; background: rgba(255, 255, 255, .94); box-shadow: 0 22px 54px rgba(35, 57, 68, .1); backdrop-filter: blur(16px); }
.collaboration-workspace-header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 24px; padding: 18px 24px; border-bottom: 1px solid #e1e7ea; background: rgba(255, 255, 255, .97); }
.workspace-identity { display: flex; min-width: 0; align-items: stretch; gap: 16px; }
.workspace-signal { width: 3px; border-radius: 999px; background: linear-gradient(180deg, rgba(24, 209, 255, .35), #18d1ff 48%, rgba(24, 209, 255, .28)); box-shadow: 0 0 11px rgba(24, 209, 255, .25); }
.workspace-identity > div { min-width: 0; }
.workspace-identity p { margin: 0 0 6px; color: #71818d; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; letter-spacing: .11em; }
.workspace-identity h1 { overflow: hidden; margin: 0 0 7px; color: #10171d; font-size: 24px; letter-spacing: -.035em; text-overflow: ellipsis; white-space: nowrap; }
.workspace-context-line { display: flex; align-items: center; gap: 8px; color: #75828c; font-size: 12px; }
.workspace-context-line i { width: 7px; height: 7px; border-radius: 50%; background: #24c99a; box-shadow: 0 0 0 4px rgba(36, 201, 154, .1); }
.workspace-context-line b { color: #bcc5ca; font-weight: 400; }
.workspace-check-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 10px; }
.workspace-check-actions :deep(.el-button) { min-height: 44px; margin: 0; border-color: #dce4e8; border-radius: 8px; padding: 0 18px; background: #fff; color: #202b33; font-weight: 600; transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease; }
.workspace-check-actions :deep(.el-button span span) { margin-left: 9px; color: #19bfe5; }
.workspace-check-actions :deep(.el-button:not(:disabled):hover) { border-color: rgba(24, 198, 232, .35); color: #0b1116; box-shadow: 4px 5px 15px rgba(18, 195, 231, .18); transform: translateY(-1px); }
.workspace-check-actions :deep(.el-button.is-primary-check) { border-color: #0b1116; background: #0b1116; color: #fff; box-shadow: 3px 4px 12px rgba(8, 16, 21, .14); }
.workspace-check-actions :deep(.el-button.is-primary-check:not(:disabled):hover) { border-color: #0b1116; background: #0b1116; color: #fff; box-shadow: 4px 5px 16px rgba(18, 195, 231, .24); }
.collaboration-workspace-body { display: grid; min-width: 0; min-height: 0; grid-template-columns: 264px minmax(0, 1fr); overflow: hidden; }
.collaboration-tree-panel { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; border-right: 1px solid #e1e7eb; background: rgba(247, 249, 250, .86); }
.collaboration-tree-panel > header { display: grid; gap: 5px; padding: 20px 20px 14px; }
.collaboration-tree-panel > header span { color: #207f96; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 9px; letter-spacing: .16em; }
.collaboration-tree-panel > header strong { font-size: 20px; letter-spacing: -.025em; }
.collaboration-tree-panel :deep(.linked-context) { min-height: 0; overflow: auto; gap: 22px; padding: 0 12px 18px; }
.collaboration-tree-panel :deep(.linked-context-title) { padding: 8px 8px 4px; color: #7c8993; font-size: 11px; }
.collaboration-tree-panel :deep(.el-tree-node__content), .collaboration-tree-panel :deep(.related-document) { min-height: 38px; border-radius: 7px; }
.collaboration-tree-panel :deep(.el-tree-node.is-current > .el-tree-node__content), .collaboration-tree-panel :deep(.related-document.is-active) { position: relative; background: #fff; color: #111820; box-shadow: 0 7px 18px rgba(33, 53, 64, .07); }
.collaboration-tree-panel :deep(.el-tree-node.is-current > .el-tree-node__content::before), .collaboration-tree-panel :deep(.related-document.is-active::before) { position: absolute; top: 7px; bottom: 7px; left: 0; width: 3px; border-radius: 999px; background: #18d1ff; content: ''; }
.linked-page-main { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto auto minmax(0, 1fr); gap: 8px; overflow: hidden; padding: 14px; background: #f7f9fa; }
.linked-page-main > .linked-workbench-shell:first-child { grid-row: 1 / -1; }
.linked-page-main :deep(.linked-workbench-shell) { overflow: hidden; border: 1px solid #dfe6ea; border-radius: 10px; background: #fff; box-shadow: none; }
.linked-page-main :deep([data-testid="agent-check-button"]) { display: none; }
.linked-page-main :deep(.linked-canvas) { grid-template-columns: minmax(320px, .96fr) 54px minmax(360px, 1.04fr); }
.linked-page-main :deep(.linked-pane-header), .linked-page-main :deep(.document-pane-header) { min-height: 56px; padding: 10px 16px; }
.linked-page-main :deep(.linked-pane-header strong), .linked-page-main :deep(.document-pane-header strong) { color: #151d23; font-size: 15px; }
.linked-page-main :deep(.code-range-overlay.is-active) { border-color: rgba(24, 209, 255, .3); border-left-color: #18d1ff; background: rgba(226, 249, 253, .74); }
.linked-page-main :deep(.anchor-rail) { border-color: #e1e7ea; background: linear-gradient(90deg, #fafcfc, #fff, #fafcfc); }
.linked-page-main :deep(.rail-line) { width: 1px; background: #dce4e7; }
.linked-page-main :deep(.rail-node) { border-color: #cdd7dc; color: #7c8a93; }
.linked-page-main :deep(.rail-node.is-active) { border-color: #18d1ff; background: #0b1116; color: #fff; box-shadow: 0 0 0 4px rgba(24, 209, 255, .13); }
.linked-page-main :deep(.paragraph-block.is-compact-reading.is-linked-active) { border-color: rgba(24, 209, 255, .35); background: #f1fbfc; }
.linked-page-main :deep(.paragraph-block.is-compact-reading.is-linked-active::before) { background: #18d1ff; }
.agent-check-dialog { display: grid; gap: 14px; }
.agent-check-dialog p { margin: 0; color: #596772; font-size: 13px; }
.project-scan-panel { display: grid; gap: 16px; }
.project-scan-stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 0; }
.project-scan-stats div { padding: 12px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.project-scan-stats dt { color: #64748b; font-size: 12px; }
.project-scan-stats dd { margin: 4px 0 0; color: #0f172a; font-size: 22px; font-weight: 700; }
.project-scan-units { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.project-scan-units li { display: grid; gap: 3px; padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 8px; }
.project-scan-units span, .project-scan-hint { color: #64748b; font-size: 13px; }
@media (max-width: 1180px) { .collaboration-topbar { grid-template-columns: auto 1fr auto; padding: 0 18px; } .collaboration-account { right: 18px; } .collaboration-brand-name, .collaboration-beta { display: none; } .collaboration-navigation-item { min-width: 0; padding-inline: 12px; } .collaboration-workspace-body { grid-template-columns: 220px minmax(0, 1fr); } .workspace-check-actions :deep(.el-button) { padding-inline: 12px; } }
@media (max-width: 820px) { .linked-page-shell { height: auto; min-height: 100vh; overflow: visible; } .collaboration-page { grid-template-rows: auto auto; } .collaboration-topbar { grid-template-columns: auto auto; gap: 12px; padding: 12px 14px; } .collaboration-account { top: 12px; right: 14px; } .collaboration-navigation { grid-column: 1 / -1; grid-row: 2; overflow-x: auto; justify-self: stretch; } .collaboration-navigation-item { flex: 1 0 auto; } .collaboration-stage { overflow: visible; padding: 10px; } .collaboration-workspace-grid { height: auto; } .collaboration-workspace-header { align-items: flex-start; flex-direction: column; } .workspace-check-actions { width: 100%; overflow-x: auto; } .collaboration-workspace-body { grid-template-columns: 1fr; } .collaboration-tree-panel { max-height: 320px; border-right: 0; border-bottom: 1px solid #e1e7eb; } .linked-page-main { min-height: 720px; } }
@media (max-width: 820px) {
  .workspace-check-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    overflow: visible;
  }

  .workspace-check-actions :deep(.el-button) {
    width: 100%;
    padding-inline: 8px;
  }
}
@media (orientation: portrait) and (max-width: 1024px) { .collaboration-workspace-header { align-items: flex-start; flex-direction: column; } .collaboration-workspace-body { grid-template-columns: 1fr; } .collaboration-tree-panel { max-height: 340px; border-right: 0; border-bottom: 1px solid #e1e7eb; } }
@media (prefers-reduced-motion: reduce) { .collaboration-navigation-item, .linked-page-main :deep(.el-button) { transition: none !important; } }
</style>
