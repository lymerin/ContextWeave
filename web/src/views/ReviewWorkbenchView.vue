<template>
  <main
    class="review-page-shell"
    data-ark-theme="ark"
    data-ark-depth="moderate"
  >
    <header class="review-topbar">
      <button
        class="review-brand"
        type="button"
        aria-label="返回 ContextWeave 首页"
        @click="navigatePrimary('/')"
      >
        <span class="review-brand-logo" aria-hidden="true"><span /></span>
        <span class="review-brand-name">ContextWeave</span>
        <span class="review-brand-beta">BETA</span>
      </button>

      <WorkspaceContextNavigation :workspace-id="workspaceId" active="reviews" />

      <Teleport to="body">
        <div class="review-account">
          <span class="review-avatar" :aria-label="`当前用户：${currentUserName}`">
            {{ currentUserInitial }}
          </span>
        </div>
      </Teleport>
    </header>

    <section class="review-page-main">
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        show-icon
        :closable="false"
      >
        <template #default>
          <el-button size="small" @click="reloadCurrentView">重试</el-button>
        </template>
      </el-alert>

      <section v-if="!requestId" class="review-list-shell">
        <header class="review-list-header">
          <div class="review-list-heading">
            <p>DOCUMENT REVIEW / QUEUE</p>
            <h1>文档审查</h1>
            <span>{{ workspace?.name || '工作区' }} · 当前状态 {{ pageData.totalElements }} 条真实请求</span>
          </div>
          <div class="review-list-tools">
            <el-input
              v-model="searchText"
              clearable
              placeholder="搜索摘要、文档或提交人"
            />
            <el-select v-model="sort" aria-label="排序">
              <el-option label="最新提交" value="createdAt,desc" />
              <el-option label="最早提交" value="createdAt,asc" />
            </el-select>
            <el-button :loading="loading" @click="loadListAndCounts">刷新</el-button>
          </div>
        </header>

        <nav class="review-status-tabs" aria-label="审查状态">
          <button
            v-for="status in reviewStatuses"
            :key="status"
            type="button"
            :class="{ 'is-active': routeStatus === status }"
            :aria-current="routeStatus === status ? 'page' : undefined"
            @click="openStatus(status)"
          >
            <span>{{ reviewRouteLabels[status] }}</span>
            <strong>{{ statusCounts[status] }}</strong>
          </button>
        </nav>

        <div class="review-list-body">
          <el-skeleton v-if="loading" :rows="9" animated />
          <el-empty
            v-else-if="filteredItems.length === 0"
            :description="`${statusHeading}暂无文档变更请求`"
          />
          <div v-else class="review-request-list">
          <article
            v-for="item in filteredItems"
            :key="item.id"
            class="review-request-item"
          >
            <div class="request-state">
              <span :class="`is-${item.status.toLowerCase()}`">{{ reviewStatusLabels[item.status] }}</span>
            </div>
            <div class="request-summary">
              <strong>{{ item.summary }}</strong>
              <p>{{ item.affectedDocumentTitles.join('、') || '未命名文档变更' }}</p>
              <small>
                {{ item.submittedByDisplayName }} · {{ formatDate(item.createdAt) }} · {{ item.sourceType }} 提交
              </small>
            </div>
            <dl>
              <div><dt>Operations</dt><dd>{{ item.operationCount }}</dd></div>
              <div><dt>Evidence</dt><dd>{{ item.evidenceCount }}</dd></div>
            </dl>
            <button
              class="review-enter-button"
              type="button"
              :aria-label="`进入审查：${item.summary}`"
              @click="openRequest(item.id)"
            >
              进入审查 <span aria-hidden="true">↗</span>
            </button>
          </article>
          </div>
        </div>

        <el-pagination
          v-if="pageData.totalPages > 1"
          background
          layout="prev, pager, next"
          :current-page="pageData.page + 1"
          :page-size="pageData.size"
          :total="pageData.totalElements"
          @current-change="changePage"
        />
      </section>

      <section v-else class="review-detail-shell">
        <header class="review-detail-header">
          <div class="review-detail-heading">
            <p>REVIEW / {{ routeStatus.toUpperCase() }} / {{ activeOperationIndexLabel }}</p>
            <div class="review-detail-titleline">
              <h1>{{ detail?.request.summary || '加载评审请求' }}</h1>
              <span v-if="detail" class="review-detail-status">
                {{ reviewStatusLabels[detail.request.status] }}
              </span>
            </div>
            <span class="review-detail-meta">
              {{ workspace?.name || '工作区' }} ·
              {{ operationDocumentTitleLabel }} ·
              commit {{ shortCommit }} ·
              {{ detail?.operations.length || 0 }} 项变更
            </span>
          </div>
          <div class="review-detail-context">
            <button type="button" class="review-detail-linked" @click="openLinkedWorkbench">
              返回工作台 <span aria-hidden="true">↗</span>
            </button>
            <button
              v-if="detail?.request.status === 'PENDING'"
              type="button"
              class="review-detail-action"
              :disabled="decisionLoading"
              @click="openRejectDialog"
            >退回修改</button>
            <button
              v-if="detail?.request.status === 'PENDING'"
              type="button"
              class="review-detail-action is-primary"
              :disabled="decisionLoading || hasOperationConflict"
              @click="confirmApply"
            >通过审查 <span aria-hidden="true">↗</span></button>
            <button
              v-for="target in detail?.request.status === 'APPLIED' ? appliedNavigationTargets : []"
              :key="`${target.repositoryId}:${target.documentId}`"
              type="button"
              class="review-detail-action is-primary"
              :title="target.filePath || '暂无可定位的代码绑定'"
              :disabled="!target.filePath"
              @click="viewAppliedTarget(target)"
            >
              查看应用结果 <span aria-hidden="true">↗</span>
            </button>
          </div>
        </header>

        <el-skeleton v-if="detailViewState === 'loading'" :rows="12" animated />
        <el-empty
          v-else-if="detailViewState === 'not-found'"
          description="评审请求不存在"
        />
        <el-empty
          v-else-if="detailViewState === 'failed'"
          description="评审请求加载失败，请重试"
        />
        <section
          v-else-if="detailViewState === 'empty-operations' && detail"
          class="review-empty-operations"
          data-test="review-empty-operations"
        >
          <header>
            <div>
              <span :class="`is-${detail.request.status.toLowerCase()}`">
                {{ reviewStatusLabels[detail.request.status] }}
              </span>
              <h2>{{ detail.request.summary }}</h2>
              <p>{{ detail.request.rationale || '该评审没有补充说明。' }}</p>
            </div>
            <dl>
              <div><dt>仓库</dt><dd>{{ activeRepository?.name || '未指定仓库' }}</dd></div>
              <div><dt>Revision</dt><dd>{{ shortCommit }}</dd></div>
              <div><dt>提交人</dt><dd>{{ detail.request.submittedBy.displayName }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatDate(detail.request.createdAt) }}</dd></div>
            </dl>
          </header>
          <el-alert
            title="该评审当前没有可执行 Operation"
            description="评审请求仍然存在，以下 Evidence 可继续用于核对上下文；当前不能执行批准应用。"
            type="info"
            show-icon
            :closable="false"
          />
          <section class="review-request-evidence">
            <div class="review-request-evidence-title">
              <strong>Request Evidence</strong>
              <span>{{ detail.requestEvidence.length }}</span>
            </div>
            <article
              v-for="evidence in detail.requestEvidence"
              :key="evidence.id"
            >
              <strong>{{ evidence.filePath }}</strong>
              <small>
                {{ evidence.repository.name }} · {{ evidence.commitHash.slice(0, 10) }}
                <template v-if="evidence.startLine != null">
                  · L{{ evidence.startLine }}–{{ evidence.endLine ?? evidence.startLine }}
                </template>
              </small>
              <p>{{ evidence.description || evidence.excerptText || '该 Evidence 没有补充描述。' }}</p>
            </article>
            <el-empty
              v-if="detail.requestEvidence.length === 0"
              description="该评审当前没有 Request Evidence"
            />
          </section>
        </section>
        <div
          v-else-if="detailViewState === 'ready' && detail && activeOperation"
          class="review-four-area"
          :class="{ 'is-inspector-collapsed': !inspectorOpen }"
        >
          <ReviewCodeEvidencePane
            :evidence="activeEvidence"
            :binding="activeBinding"
            :loading="bindingLoading"
            :source-content="boundSource?.content"
            :source-path="boundSource?.path"
            :source-commit="boundSource?.commitSha"
            :source-repository="boundRepositoryName"
            :source-mode="boundCodeSourceMode"
          />
          <ReviewDocumentPane
            :operation="activeOperation"
            :operations="detail.operations"
            :active-operation-id="activeOperation.operationId"
            :request-status="detail.request.status"
            :blocks="documentBlocks"
            :loading="documentLoading"
            @select-operation="selectOperation"
          />
          <ReviewInspector
            :detail="detail"
            :active-operation-id="activeOperation.operationId"
            :active-evidence-id="activeEvidence?.id || null"
            :open="inspectorOpen"
            :decision-loading="decisionLoading"
            :has-code-context="Boolean(activeBinding || boundSource || activeEvidence)"
            @toggle="inspectorOpen = !inspectorOpen"
            @select-operation="selectOperation"
            @select-evidence="selectEvidence"
            @apply="confirmApply"
            @reject="openRejectDialog"
          />
        </div>
      </section>
    </section>

    <el-dialog
      v-model="applyDialogOpen"
      width="520px"
      append-to-body
      class="review-apply-dialog"
    >
      <template #header>
        <div class="review-dialog-heading">
          <small>REVIEW / APPLY</small>
          <h2>批准并应用文档变更</h2>
          <p>确认后将一次性写入本次审查通过的变更</p>
        </div>
      </template>

      <div class="review-dialog-summary">
        <div>
          <small>OPERATIONS</small>
          <strong>{{ detail?.operations.length || 0 }}</strong>
          <span>项变更</span>
        </div>
        <div>
          <small>APPLY MODE</small>
          <strong>单事务</strong>
          <span>全部成功后生效</span>
        </div>
      </div>

      <div class="review-dialog-safety">
        <span aria-hidden="true">✓</span>
        <p><strong>版本与冲突保护</strong>应用前会重新校验目标 Block 版本；如检测到冲突，不会覆盖人工修改。</p>
      </div>
      <template #footer>
        <button class="review-dialog-cancel" type="button" @click="applyDialogOpen = false">取消</button>
        <button class="review-dialog-confirm" type="button" :disabled="decisionLoading" @click="applyRequest">
          {{ decisionLoading ? '正在应用' : '批准并应用' }}
          <span aria-hidden="true">↗</span>
        </button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="rejectDialogOpen"
      title="拒绝文档变更"
      width="460px"
      append-to-body
      @closed="rejectReason = ''"
    >
      <p>请说明拒绝原因，提交人可据此重新生成建议。</p>
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="5"
        maxlength="2000"
        show-word-limit
        placeholder="输入拒绝原因"
      />
      <template #footer>
        <el-button @click="rejectDialogOpen = false">取消</el-button>
        <el-button
          type="danger"
          :loading="decisionLoading"
          :disabled="!rejectReason.trim() || decisionLoading"
          @click="rejectRequest"
        >确认拒绝</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
import axios from 'axios';
import { ElMessage } from 'element-plus';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { listBlocks, type DocumentBlock } from '@/api/block';
import {
  applyDocumentChange,
  getDocumentChange,
  listDocumentChanges,
  rejectDocumentChange,
  type DocumentChangeDetail,
  type DocumentChangeEvidence,
  type DocumentChangeOperation,
  type DocumentChangePage,
  type DocumentChangeStatus,
} from '@/api/documentChange';
import {
  getGitRepositorySource,
  listCodeBindingsContext,
  listGitRepositories,
  listGitRepositoryFiles,
  queryCodeBindings,
  type GitRepository,
  type GitRepositoryFile,
  type GitRepositorySource,
  type CodeBindingContextItem,
} from '@/api/git';
import { getWorkspace, type Workspace } from '@/api/workspace';
import ReviewCodeEvidencePane from '@/components/review/ReviewCodeEvidencePane.vue';
import ReviewDocumentPane from '@/components/review/ReviewDocumentPane.vue';
import ReviewInspector from '@/components/review/ReviewInspector.vue';
import WorkspaceContextNavigation from '@/components/layout/WorkspaceContextNavigation.vue';
import {
  createLinkedWorkbenchSnapshot,
  useLinkedWorkbenchNavigation,
} from '@/composables/useLinkedWorkbenchNavigation';
import {
  reviewStatusLabels,
  selectedEvidence,
} from '@/components/review/reviewPresentation';
import { readableError } from '@/utils/error';
import {
  resolveAppliedReviewNavigationTargets,
  type AppliedReviewNavigationTarget,
} from '@/utils/linkedReviewNavigation';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';
import { useAuthStore } from '@/stores/auth';

type ReviewRouteStatus = 'pending' | 'applied' | 'rejected' | 'stale';
type ReviewDetailViewState =
  | 'loading'
  | 'not-found'
  | 'failed'
  | 'empty-operations'
  | 'ready';

const route = useRoute();
const router = useRouter();
const backgroundActivity = useBackgroundActivityStore();
const authStore = useAuthStore();
const workspaceId = computed(() => String(route.params.workspaceId || ''));
const requestId = computed(() => String(route.params.requestId || ''));
const routeStatus = computed<ReviewRouteStatus>(() => (
  ['pending', 'applied', 'rejected', 'stale'].includes(String(route.params.status))
    ? String(route.params.status) as ReviewRouteStatus
    : 'pending'
));
const apiStatus = computed<DocumentChangeStatus>(() => routeStatus.value.toUpperCase() as DocumentChangeStatus);

const workspace = ref<Workspace | null>(null);
const repositories = ref<GitRepository[]>([]);
const selectedRepositoryId = ref('');
const files = ref<GitRepositoryFile[]>([]);
const selectedFilePath = ref('');
const manualSource = ref<GitRepositorySource | null>(null);
const boundSource = ref<GitRepositorySource | null>(null);
const activeBinding = ref<CodeBindingContextItem | null>(null);
const bindingLoading = ref(false);
const boundCodeSourceMode = ref<'binding' | 'evidence' | 'repository-default' | null>(null);
const loading = ref(false);
const detailLoading = ref(Boolean(requestId.value));
const detailNotFound = ref(false);
const detailLoadFailed = ref(false);
const documentLoading = ref(false);
const decisionLoading = ref(false);
const errorMessage = ref('');
const searchText = ref('');
const sort = ref('createdAt,desc');
const page = ref(0);
const detail = ref<DocumentChangeDetail | null>(null);
const documentBlocks = ref<DocumentBlock[]>([]);
const inspectorOpen = ref(true);
const applyDialogOpen = ref(false);
const rejectDialogOpen = ref(false);
const rejectReason = ref('');
const appliedNavigationTargets = ref<AppliedReviewNavigationTarget[]>([]);
const appliedTargetsLoading = ref(false);
const reviewStatuses: ReviewRouteStatus[] = ['pending', 'applied', 'rejected', 'stale'];
const reviewRouteLabels: Record<ReviewRouteStatus, string> = {
  pending: '待审查',
  applied: '已应用',
  rejected: '已拒绝',
  stale: '已失效',
};
const statusCounts = ref<Record<ReviewRouteStatus, number>>({
  pending: 0,
  applied: 0,
  rejected: 0,
  stale: 0,
});
const pageData = ref<DocumentChangePage>({
  items: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
});

let detailRequestGeneration = 0;
let operationDocumentGeneration = 0;
let appliedTargetGeneration = 0;
let bindingRequestGeneration = 0;
const linkedNavigation = useLinkedWorkbenchNavigation(() => null);
const globalReviewRefreshVersion = computed(() => (
  backgroundActivity.reviewRefreshVersion(workspaceId.value)
));

const activeRepository = computed(() =>
  repositories.value.find(item => item.id === selectedRepositoryId.value) ?? null);
const activeOperation = computed<DocumentChangeOperation | null>(() => {
  const operations = detail.value?.operations ?? [];
  const requested = typeof route.query.operationId === 'string' ? route.query.operationId : '';
  return operations.find(item => item.operationId === requested) ?? operations[0] ?? null;
});
const activeEvidence = computed<DocumentChangeEvidence | null>(() =>
  selectedEvidence(
    activeOperation.value,
    detail.value?.requestEvidence ?? [],
    typeof route.query.evidenceId === 'string' ? route.query.evidenceId : null,
  ));
const activeOperationIndexLabel = computed(() => {
  const operations = detail.value?.operations ?? [];
  const index = activeOperation.value
    ? operations.findIndex(item => item.operationId === activeOperation.value?.operationId)
    : -1;
  return String(Math.max(1, index + 1)).padStart(2, '0');
});
const operationDocumentTitleLabel = computed(() => activeOperation.value
  ? activeOperation.value.target.documentTitle
    || activeOperation.value.proposal?.documentTitle
    || '待创建文档'
  : '未选择文档');
const hasOperationConflict = computed(() => detail.value?.operations.some(
  item => item.conflict?.conflicted,
) ?? false);
const boundRepositoryName = computed(() => repositories.value.find(
  item => item.id === (activeBinding.value?.repositoryId || activeEvidence.value?.repository.id),
)?.name || activeEvidence.value?.repository.name || '仓库代码');
const filteredItems = computed(() => {
  const keyword = searchText.value.trim().toLocaleLowerCase();
  if (!keyword) return pageData.value.items;
  return pageData.value.items.filter(item => [
    item.summary,
    item.submittedByDisplayName,
    ...item.affectedDocumentTitles,
  ].some(value => value.toLocaleLowerCase().includes(keyword)));
});
const statusHeading = computed(() => reviewStatusLabels[apiStatus.value]);
const detailViewState = computed<ReviewDetailViewState>(() => {
  if (detailLoading.value) return 'loading';
  if (detailNotFound.value) return 'not-found';
  if (detailLoadFailed.value || !detail.value) return 'failed';
  if (detail.value.operations.length === 0) return 'empty-operations';
  return 'ready';
});
const shortCommit = computed(() => (
  activeEvidence.value?.commitHash
  || activeRepository.value?.lastSyncedCommit
  || '尚无 Commit'
).slice(0, 10));
const currentUserName = computed(() => (
  authStore.currentUser?.displayName
  || authStore.currentUser?.username
  || '用户'
));
const currentUserInitial = computed(() => currentUserName.value.trim().slice(0, 1) || '用');
onMounted(() => {
  backgroundActivity.setActiveWorkspace(workspaceId.value);
  void backgroundActivity.refreshPendingReviewCount(workspaceId.value);
  void loadInitialState();
});

onUnmounted(() => {
  detailRequestGeneration++;
  operationDocumentGeneration++;
  appliedTargetGeneration++;
});

watch(
  () => [route.params.workspaceId, route.params.status, route.params.requestId],
  ([nextWorkspace, nextStatus, nextRequest], [prevWorkspace, prevStatus, prevRequest]) => {
    const workspaceChanged = nextWorkspace !== prevWorkspace;
    const statusChanged = nextStatus !== prevStatus;
    const requestChanged = nextRequest !== prevRequest;

    if (!workspaceChanged && !statusChanged && !requestChanged) return;

    errorMessage.value = '';
    detailNotFound.value = false;
    detailLoadFailed.value = false;
    appliedTargetGeneration++;
    appliedNavigationTargets.value = [];
    appliedTargetsLoading.value = false;

    if (workspaceChanged) {
      detailRequestGeneration++;
      operationDocumentGeneration++;
      detail.value = null;
      detailNotFound.value = false;
      detailLoadFailed.value = false;
      documentBlocks.value = [];
      files.value = [];
      selectedRepositoryId.value = '';
      selectedFilePath.value = '';
      manualSource.value = null;
      appliedNavigationTargets.value = [];
      void loadInitialState();
      return;
    }

    if (nextRequest) {
      if (requestChanged) void loadDetail();
    } else {
      detail.value = null;
      detailNotFound.value = false;
      detailLoadFailed.value = false;
      void loadListAndCounts();
    }
  },
);

watch(activeOperation, () => {
  void loadOperationDocument();
  void loadBoundCode();
}, { flush: 'post' });
watch(sort, () => {
  if (!requestId.value) {
    page.value = 0;
    void loadListAndCounts();
  }
});
watch(globalReviewRefreshVersion, (next, previous) => {
  if (next === previous) return;
  if (requestId.value) void loadCounts();
  else void loadListAndCounts();
});

async function loadInitialState() {
  try {
    [workspace.value, repositories.value] = await Promise.all([
      getWorkspace(workspaceId.value),
      listGitRepositories(workspaceId.value),
    ]);
    const queryRepository = typeof route.query.repositoryId === 'string'
      ? route.query.repositoryId
      : '';
    selectedRepositoryId.value = repositories.value.some(item => item.id === queryRepository)
      ? queryRepository
      : repositories.value[0]?.id || '';
    await loadRepositoryFiles();
    if (requestId.value) await Promise.all([loadDetail(), loadCounts()]);
    else await loadListAndCounts();
  } catch (error) {
    errorMessage.value = readableError(error, '评审工作台加载失败');
  }
}

async function loadRepositoryFiles() {
  if (!selectedRepositoryId.value) {
    files.value = [];
    return;
  }
  try {
    files.value = await listGitRepositoryFiles(workspaceId.value, selectedRepositoryId.value);
  } catch {
    files.value = [];
  }
}

async function loadCounts() {
  const statuses: ReviewRouteStatus[] = ['pending', 'applied', 'rejected', 'stale'];
  const responses = await Promise.all(statuses.map(status =>
    listDocumentChanges(workspaceId.value, {
      status: status.toUpperCase() as DocumentChangeStatus,
      page: 0,
      size: 1,
    })));
  statusCounts.value = Object.fromEntries(
    statuses.map((status, index) => [status, responses[index].totalElements]),
  ) as Record<ReviewRouteStatus, number>;
}

async function loadListAndCounts() {
  loading.value = true;
  errorMessage.value = '';
  try {
    [pageData.value] = await Promise.all([
      listDocumentChanges(workspaceId.value, {
        status: apiStatus.value,
        page: page.value,
        size: 20,
        sort: sort.value,
      }),
      loadCounts(),
    ]);
  } catch (error) {
    errorMessage.value = readableError(error, '评审请求列表加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadDetail() {
  if (!requestId.value) return;
  const currentGeneration = ++detailRequestGeneration;
  
  detailLoading.value = true;
  detailNotFound.value = false;
  detailLoadFailed.value = false;
  errorMessage.value = '';
  try {
    const result = await getDocumentChange(workspaceId.value, requestId.value);
    if (currentGeneration !== detailRequestGeneration) return;
    detail.value = result;
    await normalizeDetailSelection();
    await refreshAppliedNavigationTargets(result);
  } catch (error) {
    if (currentGeneration !== detailRequestGeneration) return;
    detail.value = null;
    if (axios.isAxiosError(error) && error.response?.status === 404) {
      detailNotFound.value = true;
    } else {
      detailLoadFailed.value = true;
      errorMessage.value = readableError(error, '评审请求详情加载失败');
    }
  } finally {
    if (currentGeneration === detailRequestGeneration) {
      detailLoading.value = false;
    }
  }
}

async function normalizeDetailSelection() {
  const operation = activeOperation.value ?? detail.value?.operations[0];
  if (!operation) return;
  const evidence = selectedEvidence(
    operation,
    detail.value?.requestEvidence ?? [],
    typeof route.query.evidenceId === 'string' ? route.query.evidenceId : null,
  );
  
  const queryUpdates: Record<string, any> = {
    ...route.query,
    operationId: operation.operationId,
  };
  
  if (evidence) {
    queryUpdates.evidenceId = evidence.id;
    queryUpdates.repositoryId = evidence.repository.id;
    queryUpdates.filePath = evidence.filePath;
  } else {
    queryUpdates.evidenceId = undefined;
    queryUpdates.repositoryId = undefined;
    queryUpdates.filePath = undefined;
  }
  
  await router.replace({ query: queryUpdates });
  
  if (evidence) {
    manualSource.value = null;
    selectedRepositoryId.value = evidence.repository.id;
    selectedFilePath.value = evidence.filePath;
    if (!files.value.some(file => file.path === evidence.filePath)) {
      await loadRepositoryFiles();
    }
  } else {
    manualSource.value = null;
    selectedRepositoryId.value = '';
    selectedFilePath.value = '';
  }
  await loadOperationDocument();
  await loadBoundCode();
}

async function loadBoundCode() {
  const operation = activeOperation.value;
  const generation = ++bindingRequestGeneration;
  activeBinding.value = null;
  boundSource.value = null;
  boundCodeSourceMode.value = null;
  if (!operation) return;

  bindingLoading.value = true;
  const documentId = operation.target.documentId;
  if (documentId) {
    try {
      const bindings = await listCodeBindingsContext(documentId, {
        revision: activeEvidence.value?.commitHash || undefined,
        includeLegacy: true,
      });
      if (generation !== bindingRequestGeneration) return;
      activeBinding.value = bindings.find(item => (
        item.blockId === operation.target.blockId && item.bindingRole === 'PRIMARY'
      )) || bindings.find(item => item.blockId === operation.target.blockId)
        || bindings.find(item => item.bindingRole === 'PRIMARY')
        || bindings[0]
        || null;
      if (activeBinding.value) boundCodeSourceMode.value = 'binding';
    } catch {
      if (generation !== bindingRequestGeneration) return;
      activeBinding.value = null;
    }
  }

  try {
    const binding = activeBinding.value;
    const evidence = activeEvidence.value;
    const proposal = detail.value?.bindingProposals.find(item => (
      item.documentTarget.blockId === operation.target.blockId
      || item.documentTarget.documentId === operation.target.documentId
    ));
    let repositoryId = binding?.repositoryId || evidence?.repository.id || proposal?.repository.id;
    let path = binding?.matchedFilePath || evidence?.filePath || proposal?.filePath;
    if (!repositoryId || !path) {
      const fallbackRepository = repositories.value.find(item => item.id === selectedRepositoryId.value)
        || repositories.value[0];
      if (fallbackRepository && selectedRepositoryId.value !== fallbackRepository.id) {
        selectedRepositoryId.value = fallbackRepository.id;
        await loadRepositoryFiles();
      }
      const fallbackFile = files.value.find(file => file.readable) || files.value[0];
      repositoryId = fallbackRepository?.id;
      path = fallbackFile?.path;
      if (repositoryId && path) boundCodeSourceMode.value = 'repository-default';
    } else if (!binding) {
      boundCodeSourceMode.value = evidence ? 'evidence' : 'repository-default';
    }
    if (!repositoryId || !path) return;

    selectedRepositoryId.value = repositoryId;
    selectedFilePath.value = path;
    const source = await getGitRepositorySource(workspaceId.value, repositoryId, path);
    if (generation === bindingRequestGeneration) boundSource.value = source;
  } catch {
    // The saved Evidence excerpt remains visible when repository source is unavailable.
    boundSource.value = null;
  } finally {
    if (generation === bindingRequestGeneration) bindingLoading.value = false;
  }
}

async function loadOperationDocument() {
  const operation = activeOperation.value;
  const documentId = operation?.target.documentId;
  if (!operation || !documentId || operation.operationType === 'CREATE_DOCUMENT') {
    documentBlocks.value = [];
    return;
  }
  
  const currentGeneration = ++operationDocumentGeneration;
  
  documentLoading.value = true;
  try {
    const result = await listBlocks(documentId);
    if (currentGeneration !== operationDocumentGeneration) return;
    documentBlocks.value = result;
  } catch {
    if (currentGeneration !== operationDocumentGeneration) return;
    documentBlocks.value = [];
  } finally {
    if (currentGeneration === operationDocumentGeneration) {
      documentLoading.value = false;
    }
  }
}

async function selectRepository(repositoryId: string) {
  selectedRepositoryId.value = repositoryId;
  selectedFilePath.value = '';
  manualSource.value = null;
  await router.replace({ query: { ...route.query, repositoryId, filePath: undefined } });
  await loadRepositoryFiles();
}

async function selectRepositoryFile(path: string) {
  selectedFilePath.value = path;
  await router.replace({ query: { ...route.query, filePath: path } });
  try {
    manualSource.value = await getGitRepositorySource(
      workspaceId.value,
      selectedRepositoryId.value,
      path,
    );
  } catch (error) {
    manualSource.value = null;
    ElMessage.error(readableError(error, '仓库文件读取失败'));
  }
}

async function selectOperation(operationId: string) {
  const operation = detail.value?.operations.find(item => item.operationId === operationId);
  const evidence = selectedEvidence(operation ?? null, detail.value?.requestEvidence ?? []);
  
  const queryUpdates: Record<string, any> = {
    ...route.query,
    operationId,
  };
  
  if (evidence) {
    queryUpdates.evidenceId = evidence.id;
    queryUpdates.repositoryId = evidence.repository.id;
    queryUpdates.filePath = evidence.filePath;
  } else {
    queryUpdates.evidenceId = undefined;
    queryUpdates.repositoryId = undefined;
    queryUpdates.filePath = undefined;
  }
  
  await router.replace({ query: queryUpdates });
  
  if (evidence) {
    manualSource.value = null;
    selectedRepositoryId.value = evidence.repository.id;
    selectedFilePath.value = evidence.filePath;
    await loadRepositoryFiles();
  } else {
    manualSource.value = null;
    selectedRepositoryId.value = '';
    selectedFilePath.value = '';
  }
}

async function selectEvidence(evidenceId: string) {
  const allEvidence = [
    ...(activeOperation.value?.evidence ?? []),
    ...(detail.value?.requestEvidence ?? []),
  ];
  const evidence = allEvidence.find(item => item.id === evidenceId);
  if (evidence) {
    selectedRepositoryId.value = evidence.repository.id;
    selectedFilePath.value = evidence.filePath;
    manualSource.value = null;
    await router.replace({
      query: {
        ...route.query,
        evidenceId,
        repositoryId: evidence.repository.id,
        filePath: evidence.filePath,
      },
    });
    await loadRepositoryFiles();
    return;
  }

  const bindingProposal = detail.value?.bindingProposals?.find(
    item => item.bindingProposalId === evidenceId,
  );
  if (bindingProposal) {
    if (selectedRepositoryId.value !== bindingProposal.repository.id) {
      await selectRepository(bindingProposal.repository.id);
    }
    await selectRepositoryFile(bindingProposal.filePath);
    await router.replace({
      query: {
        ...route.query,
        evidenceId,
      },
    });
  }
}

function openStatus(status: ReviewRouteStatus) {
  page.value = 0;
  void router.push({
    name: 'workspace-reviews',
    params: { workspaceId: workspaceId.value, status },
    query: {
      repositoryId: selectedRepositoryId.value || undefined,
    },
  });
}

function navigatePrimary(target: string) {
  void router.push(target);
}

function openRequest(id: string) {
  void router.push({
    name: 'workspace-review-detail',
    params: {
      workspaceId: workspaceId.value,
      status: routeStatus.value,
      requestId: id,
    },
    query: {
      repositoryId: selectedRepositoryId.value || undefined,
    },
  });
}

function openLinkedWorkbench() {
  void router.push({
    name: 'workspace-code',
    params: { workspaceId: workspaceId.value },
  });
}

function changePage(nextPage: number) {
  page.value = nextPage - 1;
  void loadListAndCounts();
}

function reloadCurrentView() {
  if (requestId.value) void loadDetail();
  else void loadListAndCounts();
}

function confirmApply() {
  applyDialogOpen.value = true;
}

function openRejectDialog() {
  rejectReason.value = '';
  rejectDialogOpen.value = true;
}

async function applyRequest() {
  if (!detail.value || decisionLoading.value) return;
  decisionLoading.value = true;
  try {
    detail.value = await applyDocumentChange(workspaceId.value, detail.value.request.id);
    applyDialogOpen.value = false;
    await Promise.all([
      loadCounts(),
      backgroundActivity.refreshPendingReviewCount(workspaceId.value),
    ]);
    await loadOperationDocument();
    await refreshAppliedNavigationTargets(detail.value);
    if (detail.value.request.status === 'STALE') {
      ElMessage.warning('目标内容已经变化，请求已标记为失效');
    } else {
      ElMessage.success('文档变更已原子应用');
    }
  } catch (error) {
    ElMessage.error(readableError(error, '批准应用失败'));
  } finally {
    decisionLoading.value = false;
  }
}

async function refreshAppliedNavigationTargets(result: DocumentChangeDetail) {
  const generation = ++appliedTargetGeneration;
  if (result.request.status !== 'APPLIED') {
    appliedNavigationTargets.value = [];
    return;
  }
  appliedTargetsLoading.value = true;
  try {
    const targets = await resolveAppliedReviewNavigationTargets(
      workspaceId.value,
      result,
      repositories.value,
      queryCodeBindings,
    );
    if (generation === appliedTargetGeneration) appliedNavigationTargets.value = targets;
  } finally {
    if (generation === appliedTargetGeneration) appliedTargetsLoading.value = false;
  }
}

async function viewAppliedTarget(target: AppliedReviewNavigationTarget) {
  if (!target.filePath) return;
  linkedNavigation.navigateTo(createLinkedWorkbenchSnapshot(
    {
      workspaceId: workspaceId.value,
      repositoryId: target.repositoryId,
      revision: target.revision,
    },
    target.filePath,
    target.documentId,
  ));
  await router.push({
    name: 'workspace-code',
    params: { workspaceId: workspaceId.value },
    query: { repositoryId: target.repositoryId },
  });
}

async function rejectRequest() {
  if (!detail.value || !rejectReason.value.trim() || decisionLoading.value) return;
  decisionLoading.value = true;
  try {
    detail.value = await rejectDocumentChange(
      workspaceId.value,
      detail.value.request.id,
      rejectReason.value.trim(),
    );
    rejectDialogOpen.value = false;
    await Promise.all([
      loadCounts(),
      backgroundActivity.refreshPendingReviewCount(workspaceId.value),
    ]);
    ElMessage.success('文档变更已拒绝');
    openStatus(routeStatus.value);
  } catch (error) {
    ElMessage.error(readableError(error, '拒绝评审请求失败'));
  } finally {
    decisionLoading.value = false;
  }
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}
</script>

<style scoped>
.review-page-shell {
  --review-ink: #090c0e;
  --review-paper: #f5f7f7;
  --review-signal: #18c9ef;
  --review-muted: #6d787d;
  min-height: 100svh;
  height: 100svh;
  display: grid;
  grid-template-rows: 72px minmax(0, 1fr);
  overflow: hidden;
  color: var(--review-ink);
  background:
    radial-gradient(circle at 12% 8%, rgb(24 201 239 / 9%), transparent 26%),
    radial-gradient(circle at 80% 90%, rgb(112 157 255 / 8%), transparent 28%),
    radial-gradient(circle, rgb(40 76 89 / 10%) 1px, transparent 1.15px),
    var(--review-paper);
  background-size: auto, auto, 18px 18px, auto;
}

.review-topbar {
  position: relative;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(250px, 1fr) auto minmax(190px, 1fr);
  align-items: center;
  gap: 24px;
  padding: 0 36px;
  border-bottom: 1px solid rgb(15 26 31 / 8%);
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 10px 32px rgb(38 56 67 / 6%);
}

.review-account {
  position: fixed;
  top: 17px;
  right: 36px;
  z-index: 120;
  display: flex;
}

.review-avatar {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--review-ink);
  color: #fff;
  font-weight: 650;
}

.review-brand,
.review-navigation,
.review-navigation-item,
.review-enter-button {
  display: flex;
  align-items: center;
}

.review-brand {
  width: max-content;
  gap: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.review-brand-logo {
  position: relative;
  width: 38px;
  height: 38px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--review-ink);
}

.review-brand-logo::before,
.review-brand-logo::after,
.review-brand-logo span {
  position: absolute;
  left: 9px;
  width: 20px;
  height: 2px;
  content: "";
  background: var(--review-signal);
  transform: skewX(-28deg);
}

.review-brand-logo::before { top: 11px; }
.review-brand-logo span { top: 18px; width: 13px; }
.review-brand-logo::after { top: 25px; }

.review-brand-name {
  font-weight: 700;
  letter-spacing: -0.03em;
}

.review-brand-beta,
.review-list-heading p,
.request-state span,
.review-request-item dt {
  font-family: "IBM Plex Mono", Consolas, monospace;
  letter-spacing: 0.1em;
}

.review-brand-beta {
  padding: 3px 7px;
  border: 1px solid #e2e8eb;
  border-radius: 4px;
  background: #f0f3f5;
  color: #566066;
  font-size: 10px;
}

.review-navigation {
  gap: 4px;
  padding: 5px;
  border: 1px solid #e4e9ec;
  border-radius: 999px;
  background: #eef1f3;
}

.review-navigation-item {
  min-height: 36px;
  gap: 7px;
  padding: 0 16px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #6d777c;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
  transition: color 180ms ease, background 180ms ease, transform 180ms ease;
}

.review-navigation-item:hover:not(:disabled) { color: var(--review-ink); }
.review-navigation-item:active:not(:disabled) { transform: translateY(1px); }
.review-navigation-item:disabled { cursor: not-allowed; opacity: 0.62; }

.review-navigation-item.is-active {
  background: var(--review-ink);
  box-shadow: 0 5px 16px rgb(9 12 14 / 20%);
  color: #fff;
  cursor: default;
  opacity: 1;
}

.review-navigation-item.is-active span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--review-signal);
}

.review-page-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 8px;
  overflow: hidden;
  padding: 30px max(24px, calc((100vw - 1760px) / 2)) 34px;
}

.review-list-shell,
.review-detail-shell {
  min-width: 0;
  min-height: 0;
  flex: 1;
  border: 1px solid rgb(255 255 255 / 84%);
  border-radius: 22px;
  background: rgb(255 255 255 / 76%);
  box-shadow: 0 18px 52px rgb(39 59 70 / 6%);
  backdrop-filter: blur(20px);
  overflow: hidden;
}

.review-list-shell {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  padding: 34px 38px 22px;
}

.review-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 0 24px;
}

.review-list-header p,
.review-detail-header p {
  margin: 0;
  color: #76909a;
  font-size: 9px;
}

.review-list-header h1 {
  margin: 8px 0 10px;
  color: var(--review-ink);
  font-size: clamp(34px, 4vw, 46px);
  letter-spacing: -0.055em;
  line-height: 1;
}

.review-list-header span {
  color: var(--review-muted);
  font-size: 13px;
}

.review-list-tools {
  display: grid;
  width: min(580px, 54%);
  grid-template-columns: minmax(210px, 1fr) 136px 72px;
  gap: 8px;
}

.review-list-tools :deep(.el-input__wrapper),
.review-list-tools :deep(.el-select__wrapper),
.review-list-tools :deep(.el-button) {
  min-height: 42px;
  border-radius: 8px;
  box-shadow: 0 0 0 1px #dfe6e9 inset;
}

.review-list-tools :deep(.el-input__wrapper.is-focus),
.review-list-tools :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--review-signal) inset;
}

.review-status-tabs {
  display: flex;
  gap: 4px;
  padding: 5px;
  border: 1px solid #e4e9ec;
  border-radius: 12px;
  background: #f0f3f4;
}

.review-status-tabs button {
  min-height: 42px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #657177;
  cursor: pointer;
  transition: color 180ms ease, background 180ms ease, box-shadow 180ms ease;
}

.review-status-tabs strong {
  min-width: 22px;
  padding: 2px 6px;
  border-radius: 999px;
  background: rgb(9 12 14 / 6%);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.review-status-tabs button:hover { color: var(--review-ink); }
.review-status-tabs button.is-active {
  background: #fff;
  box-shadow: 0 5px 15px rgb(40 58 66 / 8%);
  color: var(--review-ink);
  font-weight: 650;
}

.review-status-tabs button.is-active::before {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--review-signal);
  content: "";
}

.review-list-body {
  min-height: 0;
  overflow: auto;
  margin: 18px -8px 0;
  padding: 0 8px 14px;
}

.review-request-list {
  display: grid;
  gap: 10px;
}

.review-request-item {
  position: relative;
  display: grid;
  width: 100%;
  grid-template-columns: 94px minmax(0, 1fr) 210px auto;
  align-items: center;
  gap: 16px;
  overflow: hidden;
  border: 1px solid #e5eaec;
  border-radius: 12px;
  padding: 15px 16px 15px 22px;
  background:
    linear-gradient(135deg, transparent 0 82%, rgb(24 201 239 / 2.8%) 82% 100%),
    #f6f8f8;
  color: #344054;
  text-align: left;
  transition: border-color 180ms ease, box-shadow 220ms ease, transform 180ms ease;
}

.review-request-item::before {
  position: absolute;
  top: 19px;
  bottom: 19px;
  left: 7px;
  width: 3px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgb(24 201 239 / 18%), var(--review-signal), rgb(24 201 239 / 22%));
  box-shadow: 0 0 10px rgb(24 201 239 / 30%);
  content: "";
  opacity: 0;
  transform: scaleY(0.62);
  transition: opacity 180ms ease, transform 220ms ease;
}

.review-request-item:hover,
.review-request-item:focus-within {
  border-color: rgb(24 201 239 / 22%);
  box-shadow:
    7px 7px 15px -13px rgb(24 201 239 / 72%),
    0 12px 22px -19px rgb(24 201 239 / 68%);
  transform: translateY(-1px);
}

.review-request-item:hover::before,
.review-request-item:focus-within::before {
  opacity: 1;
  transform: scaleY(1);
}

.request-state span {
  display: inline-flex;
  border-radius: 4px;
  padding: 4px 8px;
  background: #edf3ff;
  color: #175cd3;
  font-size: 10px;
  font-weight: 700;
}

.request-state span.is-applied {
  background: #ecfdf3;
  color: #027a48;
}

.request-state span.is-rejected {
  background: #fef3f2;
  color: #b42318;
}

.request-state span.is-stale {
  background: #fff7e6;
  color: #b54708;
}

.request-summary {
  min-width: 0;
}

.request-summary strong,
.request-summary p {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-summary p {
  margin: 5px 0;
  color: #475467;
  font-size: 12px;
}

.request-summary small {
  color: #98a2b3;
  font-size: 10px;
}

.review-request-item dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 0;
}

.review-request-item dl div {
  display: grid;
  justify-items: center;
  border-left: 1px solid #e5eaf1;
}

.review-request-item dt {
  color: #98a2b3;
  font-size: 9px;
}

.review-request-item dd {
  margin: 3px 0 0;
  font-weight: 700;
}

.review-enter-button {
  min-height: 42px;
  justify-content: center;
  padding: 0 15px;
  border: 1px solid #dfe6e9;
  border-radius: 8px;
  background: #fff;
  color: var(--review-ink);
  cursor: pointer;
  font-weight: 600;
  transition: transform 180ms ease, box-shadow 220ms ease, border-color 180ms ease;
}

.review-enter-button span {
  margin-left: 11px;
  color: var(--review-signal);
  transition: transform 180ms ease;
}

.review-enter-button:hover,
.review-enter-button:focus-visible {
  border-color: rgb(24 201 239 / 18%);
  box-shadow:
    7px 7px 14px -9px rgb(24 201 239 / 68%),
    13px 12px 24px -17px rgb(24 201 239 / 58%),
    0 10px 18px -13px rgb(24 201 239 / 54%);
  outline: none;
  transform: translateY(-2px);
}

.review-enter-button:hover span,
.review-enter-button:focus-visible span {
  transform: translateX(2px);
}

.review-list-shell > .el-pagination {
  justify-self: center;
  padding-top: 10px;
}

.review-detail-shell {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
}

.review-detail-header {
  display: flex;
  min-height: 112px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 14px 20px 14px 24px;
  border-bottom: 1px solid #e1e7ef;
  background: #fff;
  position: relative;
}

.review-detail-header::before {
  position: absolute;
  top: 22px;
  bottom: 22px;
  left: 0;
  width: 3px;
  border-radius: 0 4px 4px 0;
  background: linear-gradient(180deg, rgb(24 201 239 / 12%), var(--review-signal) 34%, var(--review-signal) 72%, rgb(24 201 239 / 12%));
  box-shadow: 0 0 10px rgb(24 201 239 / 30%);
  content: "";
}

.review-detail-header > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.review-detail-header > .review-detail-heading {
  display: grid;
  gap: 7px;
}

.review-detail-heading > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
}

.review-detail-titleline h1 {
  overflow: hidden;
  margin: 0;
  color: var(--review-ink);
  font-size: clamp(22px, 1.7vw, 31px);
  font-weight: 650;
  letter-spacing: -.035em;
  line-height: 1.08;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-detail-meta {
  color: #87949a;
  font: 10px/1.4 "IBM Plex Mono", Consolas, monospace;
}

.review-detail-heading > p {
  color: #168ba5;
  font-family: "IBM Plex Mono", Consolas, monospace;
  letter-spacing: .14em;
}

.review-detail-header button {
  border: 0;
  padding: 0;
  background: transparent;
  color: #168ba5;
  cursor: pointer;
}

.review-detail-header strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-detail-status {
  border: 1px solid #cdeee5;
  border-radius: 4px;
  padding: 3px 7px;
  background: #eaf9f4;
  color: #167b63;
  font-size: 10px;
  white-space: nowrap;
}

.review-detail-context {
  display: flex;
  align-items: center;
  gap: 12px;
}

.review-detail-linked,
.review-detail-action {
  position: relative;
  min-height: 48px;
  border: 1px solid #dfe6e9 !important;
  border-radius: 9px;
  padding: 0 18px !important;
  background: #fff !important;
  color: var(--review-ink) !important;
  white-space: nowrap;
  font-weight: 600;
  transition: border-color 180ms ease, box-shadow 220ms ease, transform 180ms ease;
}

.review-detail-linked span,
.review-detail-action span {
  margin-left: 8px;
  color: var(--review-signal);
  transition: transform 180ms ease;
}

.review-detail-action.is-primary {
  border-color: var(--review-ink) !important;
  background: var(--review-ink) !important;
  color: #fff !important;
}

.review-detail-linked:hover,
.review-detail-linked:focus-visible,
.review-detail-action:hover:not(:disabled),
.review-detail-action:focus-visible:not(:disabled) {
  border-color: rgb(24 201 239 / 18%) !important;
  box-shadow:
    7px 7px 14px -9px rgb(24 201 239 / 72%),
    13px 12px 24px -17px rgb(24 201 239 / 60%),
    0 10px 18px -13px rgb(24 201 239 / 56%);
  outline: none;
  transform: translateY(-2px);
}

.review-detail-linked:hover span,
.review-detail-linked:focus-visible span,
.review-detail-action:hover span,
.review-detail-action:focus-visible span {
  transform: translate(2px, -1px);
}

.review-detail-action:disabled {
  cursor: not-allowed;
  opacity: .48;
}

.review-empty-operations {
  display: grid;
  min-width: 0;
  gap: 18px;
  padding: 20px;
}

.review-empty-operations > header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, .7fr);
  gap: 24px;
}

.review-empty-operations h2 {
  margin: 8px 0 6px;
  color: #101828;
  font-size: 22px;
}

.review-empty-operations p {
  margin: 0;
  color: #667085;
  line-height: 1.65;
}

.review-empty-operations dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
  margin: 0;
}

.review-empty-operations dl div {
  min-width: 0;
}

.review-empty-operations dt {
  color: #98a2b3;
  font-size: 12px;
}

.review-empty-operations dd {
  overflow: hidden;
  margin: 3px 0 0;
  color: #344054;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-request-evidence {
  display: grid;
  gap: 10px;
}

.review-request-evidence-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #344054;
}

.review-request-evidence-title span {
  color: #667085;
  font-variant-numeric: tabular-nums;
}

.review-request-evidence article {
  display: grid;
  gap: 5px;
  border: 1px solid #e4e7ec;
  border-radius: 10px;
  padding: 13px 15px;
  background: #fff;
}

.review-request-evidence article strong {
  color: #101828;
}

.review-request-evidence article small {
  color: #667085;
}

.review-four-area {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-columns:
    minmax(360px, 31fr)
    minmax(520px, 45fr)
    minmax(320px, 24fr);
  overflow: hidden;
}

.review-four-area.is-inspector-collapsed {
  grid-template-columns:
    minmax(320px, .9fr)
    minmax(480px, 1.5fr)
    42px;
}

@media (max-width: 1100px) {
  .review-topbar {
    grid-template-columns: auto minmax(0, 1fr) auto;
    gap: 14px;
    padding: 0 22px;
  }

  .review-account {
    right: 22px;
  }

  .review-brand-beta {
    display: none;
  }

  .review-navigation-item {
    padding: 0 12px;
  }

  .review-list-header {
    align-items: stretch;
    flex-direction: column;
  }

  .review-list-tools {
    width: 100%;
  }

  .review-empty-operations > header {
    grid-template-columns: 1fr;
  }

  .review-four-area {
    grid-template-columns: minmax(300px, .9fr) minmax(430px, 1.3fr) 300px;
  }
}

@media (max-width: 820px), (orientation: portrait) {
  .review-page-shell {
    min-height: 100svh;
    height: auto;
    grid-template-rows: auto minmax(0, 1fr);
    overflow: visible;
  }

  .review-topbar {
    grid-template-columns: 1fr auto;
    min-height: 68px;
    padding: 0 18px;
  }

  .review-account {
    top: 15px;
    right: 18px;
  }

  .review-navigation {
    grid-column: 1 / -1;
    grid-row: 2;
    width: calc(100vw - 36px);
    overflow-x: auto;
    margin-bottom: 10px;
  }

  .review-navigation-item {
    flex: 1 0 auto;
  }

  .review-page-main {
    padding: 24px 14px 30px;
    overflow: visible;
  }

  .review-list-shell {
    padding: 26px 18px 18px;
  }

  .review-list-tools {
    grid-template-columns: 1fr;
  }

  .review-status-tabs {
    overflow-x: auto;
  }

  .review-status-tabs button {
    flex: 1 0 auto;
    padding: 0 13px;
  }

  .review-list-body {
    overflow: visible;
  }

  .review-request-item {
    grid-template-columns: 1fr auto;
    gap: 13px;
  }

  .request-state,
  .request-summary {
    grid-column: 1;
  }

  .review-request-item dl {
    grid-column: 1;
    width: min(230px, 100%);
  }

  .review-enter-button {
    grid-column: 2;
    grid-row: 1 / span 3;
  }

  .review-detail-shell {
    min-height: 720px;
  }

  .review-detail-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .review-detail-context {
    width: 100%;
    justify-content: space-between;
  }

  .review-four-area,
  .review-four-area.is-inspector-collapsed {
    grid-template-columns: minmax(0, 1fr);
    overflow: visible;
  }
}

.review-page-shell button:focus-visible,
.review-page-shell a:focus-visible,
.review-page-shell input:focus-visible,
.review-page-shell textarea:focus-visible {
  outline: 2px solid var(--review-signal);
  outline-offset: 2px;
}

:global(.review-apply-dialog) {
  overflow: hidden;
  border: 1px solid #dfe7e9;
  border-radius: 12px;
  box-shadow: 0 28px 70px rgb(12 29 36 / 22%);
}

:global(.review-apply-dialog::before) {
  position: absolute;
  top: 28px;
  left: 0;
  width: 3px;
  height: 48px;
  border-radius: 0 4px 4px 0;
  background: linear-gradient(180deg, rgb(24 201 239 / 20%), #18c9ef 48%, rgb(24 201 239 / 24%));
  box-shadow: 4px 0 14px rgb(24 201 239 / 26%);
  content: '';
}

:global(.review-apply-dialog .el-dialog__header) {
  margin: 0;
  padding: 27px 30px 22px;
  border-bottom: 1px solid #e5ebed;
}

:global(.review-apply-dialog .el-dialog__headerbtn) {
  top: 22px;
  right: 22px;
}

:global(.review-apply-dialog .el-dialog__headerbtn:hover .el-dialog__close) {
  color: #090c0e;
}

:global(.review-dialog-heading small) {
  display: block;
  margin-bottom: 8px;
  color: #168ba5;
  font: 600 9px Consolas, monospace;
  letter-spacing: .14em;
}

:global(.review-dialog-heading h2) {
  margin: 0;
  color: #10191d;
  font-size: 21px;
  line-height: 1.25;
}

:global(.review-dialog-heading p) {
  margin: 7px 0 0;
  color: #7a878c;
  font-size: 12px;
}

:global(.review-apply-dialog .el-dialog__body) {
  padding: 22px 30px 24px;
}

:global(.review-dialog-summary) {
  display: grid;
  grid-template-columns: 1fr 1fr;
  border: 1px solid #e1e8ea;
  border-radius: 8px;
  background: #f7f9f9;
}

:global(.review-dialog-summary > div) {
  display: grid;
  min-height: 88px;
  align-content: center;
  grid-template-columns: auto 1fr;
  column-gap: 9px;
  padding: 14px 18px;
}

:global(.review-dialog-summary > div + div) {
  border-left: 1px solid #e1e8ea;
}

:global(.review-dialog-summary small) {
  grid-column: 1 / -1;
  margin-bottom: 5px;
  color: #859298;
  font: 600 9px Consolas, monospace;
  letter-spacing: .1em;
}

:global(.review-dialog-summary strong) {
  color: #10191d;
  font-size: 18px;
  line-height: 1.2;
}

:global(.review-dialog-summary span) {
  align-self: end;
  color: #77848a;
  font-size: 11px;
}

:global(.review-dialog-safety) {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  gap: 10px;
  margin-top: 14px;
  border-left: 2px solid #18c9ef;
  padding: 12px 14px;
  background: #f0fafc;
}

:global(.review-dialog-safety > span) {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: 50%;
  background: #dff7f1;
  color: #168060;
  font-size: 11px;
  font-weight: 700;
}

:global(.review-dialog-safety p) {
  margin: 0;
  color: #647178;
  font-size: 12px;
  line-height: 1.65;
}

:global(.review-dialog-safety strong) {
  display: block;
  margin-bottom: 2px;
  color: #172126;
  font-size: 12px;
}

:global(.review-apply-dialog .el-dialog__footer) {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 0 30px 28px;
}

:global(.review-dialog-cancel),
:global(.review-dialog-confirm) {
  min-height: 46px;
  border-radius: 8px;
  padding: 0 20px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: border-color 180ms ease, box-shadow 220ms ease, color 180ms ease, transform 180ms ease;
}

:global(.review-dialog-cancel) {
  border: 1px solid #dfe7e9;
  background: #fff;
  color: #69767c;
}

:global(.review-dialog-confirm) {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border: 1px solid #090c0e;
  background: #090c0e;
  color: #fff;
}

:global(.review-dialog-confirm > span) {
  color: #18c9ef;
  font-size: 15px;
  transition: transform 180ms ease;
}

:global(.review-dialog-cancel:hover),
:global(.review-dialog-cancel:focus-visible),
:global(.review-dialog-confirm:hover),
:global(.review-dialog-confirm:focus-visible) {
  border-color: rgb(24 201 239 / 24%);
  box-shadow: 7px 7px 14px -9px rgb(24 201 239 / 78%), 13px 12px 24px -17px rgb(24 201 239 / 64%);
  outline: none;
  transform: translateY(-2px);
}

:global(.review-dialog-confirm:hover > span),
:global(.review-dialog-confirm:focus-visible > span) {
  transform: translate(2px, -1px);
}

:global(.review-dialog-confirm:disabled) {
  cursor: wait;
  opacity: .6;
}

@media (prefers-reduced-motion: reduce) {
  .review-navigation-item,
  .review-request-item,
  .review-request-item::before,
  .review-enter-button,
  .review-enter-button span,
  .review-detail-linked,
  .review-detail-linked span {
    transition: none;
  }
}
</style>
