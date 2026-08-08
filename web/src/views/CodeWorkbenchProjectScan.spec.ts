import { flushPromises, shallowMount } from '@vue/test-utils';
import { ElMessageBox } from 'element-plus';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createAgentRun,
  createAgentJob,
  getAgentJob,
  listAgentJobUnits,
} from '@/api/agent';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';
import CodeWorkbenchView from './CodeWorkbenchView.vue';

const replace = vi.fn();
const push = vi.fn();
let pinia: Pinia;

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { workspaceId: 'workspace-1' },
    query: { repositoryId: 'repository-1' },
  }),
  useRouter: () => ({ replace, push }),
}));

vi.mock('@/api/agent', async importOriginal => {
  const original = await importOriginal<typeof import('@/api/agent')>();
  return {
    ...original,
    createAgentRun: vi.fn(),
    createAgentJob: vi.fn(),
    getAgentJob: vi.fn(),
    listAgentJobUnits: vi.fn(),
  };
});

vi.mock('@/api/workspace', () => ({
  getWorkspace: vi.fn().mockResolvedValue({
    id: 'workspace-1',
    name: 'Workspace',
    currentUserRole: 'ADMIN',
  }),
}));

vi.mock('@/api/git', () => ({
  listGitRepositories: vi.fn().mockResolvedValue([{
    id: 'repository-1',
    name: 'devcollab',
    defaultBranch: 'main',
    lastSyncedCommit: 'abc',
    syncStatus: 'SYNCED',
  }]),
  listGitRepositoryFiles: vi.fn().mockResolvedValue([]),
  listGitChanges: vi.fn().mockResolvedValue([]),
  getGitRepositorySource: vi.fn(),
  syncGitRepository: vi.fn(),
}));

vi.mock('@/api/document', () => ({
  listDocumentTree: vi.fn().mockResolvedValue([]),
  getDocument: vi.fn(),
  listDocumentVersions: vi.fn().mockResolvedValue([]),
}));

vi.mock('@/api/documentChange', () => ({
  getPendingDocumentChangeCount: vi.fn().mockResolvedValue(0),
}));

vi.mock('@/composables/useDocumentCollaboration', () => ({
  useDocumentCollaboration: () => ({
    connected: { value: false },
    members: { value: [] },
    editingStates: { value: [] },
    latestRemoteBlock: { value: null },
    startEditing: vi.fn(),
    stopEditing: vi.fn(),
    updateContent: vi.fn(),
  }),
}));

describe('CodeWorkbenchView project scan', () => {
  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    vi.clearAllMocks();
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({} as never);
    vi.mocked(createAgentJob).mockResolvedValue({
      jobId: 'job-1',
      status: 'QUEUED',
      createdAt: '2026-07-28T00:00:00Z',
    });
    vi.mocked(createAgentRun).mockResolvedValue({ runId: 'run-1', status: 'QUEUED' });
    vi.mocked(getAgentJob).mockResolvedValue(readyJob());
    vi.mocked(listAgentJobUnits).mockResolvedValue({
      jobId: 'job-1',
      offset: 0,
      limit: 20,
      total: 0,
      units: [],
    });
    pinia = createPinia();
    setActivePinia(pinia);
    const backgroundActivity = useBackgroundActivityStore();
    backgroundActivity.setActiveWorkspace('workspace-1');
    await backgroundActivity.startPolling('user-1');
  });

  afterEach(() => useBackgroundActivityStore().stopPolling(false));

  it('creates PROJECT_INITIALIZATION and reports structure-only progress', async () => {
    const wrapper = mountView();
    await flushPromises();

    const button = wrapper.find('[data-test="project-scan"]');
    expect(button.exists()).toBe(true);
    await button.trigger('click');
    await flushPromises();

    expect(createAgentJob).toHaveBeenCalledWith({
      workspaceId: 'workspace-1',
      repositoryId: 'repository-1',
      scope: { type: 'PROJECT_INITIALIZATION' },
      userInstruction: null,
    });
    expect(getAgentJob).toHaveBeenCalledWith('job-1');
    expect(wrapper.text()).toContain('项目结构分析完成');
    expect(wrapper.text()).not.toContain('正在生成文档');
    expect(wrapper.text()).not.toContain('正在调用模型');
    wrapper.unmount();
  });

  it('restores a background project job from sessionStorage after repository load', async () => {
    const backgroundActivity = useBackgroundActivityStore();
    backgroundActivity.stopPolling(false);
    vi.mocked(getAgentJob).mockResolvedValue({
      ...readyJob(),
      jobId: 'restored-job',
    });
    sessionStorage.setItem('devcollab.background-activity:user-1', JSON.stringify({
      watchedJobs: [{
        jobId: 'restored-job',
        workspaceId: 'workspace-1',
        repositoryId: 'repository-1',
        label: 'devcollab',
        filePath: null,
        createdAt: '2026-07-28T00:00:00Z',
        lastKnownStatus: 'QUEUED',
      }],
      notifiedTerminalKeys: [],
    }));
    await backgroundActivity.startPolling('user-1');
    const wrapper = mountView();
    await flushPromises();

    expect(getAgentJob).toHaveBeenCalledWith('restored-job');
    expect(listAgentJobUnits).toHaveBeenCalledWith('restored-job', 0, 20);
    wrapper.unmount();
  });

  it('keeps module and Agent checks on separate real API contracts', async () => {
    const wrapper = mountView();
    await flushPromises();
    (wrapper.vm as unknown as { selectedFilePath: string }).selectedFilePath = 'src/App.java';
    await wrapper.find('[data-test="module-scan"]').trigger('click');
    await flushPromises();

    expect(createAgentJob).toHaveBeenLastCalledWith({
      workspaceId: 'workspace-1',
      repositoryId: 'repository-1',
      scope: { type: 'CURRENT_FILE', filePath: 'src/App.java' },
      userInstruction: null,
    });

    (wrapper.vm as unknown as { agentInstruction: string }).agentInstruction = '重点核对文档关联';
    await (wrapper.vm as unknown as { startAgentCheck: () => Promise<void> }).startAgentCheck();
    expect(createAgentRun).toHaveBeenCalledWith({
      workspaceId: 'workspace-1',
      repositoryId: 'repository-1',
      selectedPaths: ['src/App.java'],
      userInstruction: '重点核对文档关联',
    });
    wrapper.unmount();
  });
});

function mountView() {
  return shallowMount(CodeWorkbenchView, {
    global: {
      stubs: {
        AppSidebar: { template: '<aside><slot name="workspace-panel" /></aside>' },
        LinkedRepositoryContext: true,
        LinkedWorkbenchShell: {
          template: '<section><slot name="header-actions" /></section>',
        },
        NotificationCenter: true,
        ElAlert: true,
        ElSkeleton: true,
        ElDrawer: { template: '<div><slot /></div>' },
        ElTag: { template: '<span><slot /></span>' },
        ElProgress: true,
        ElEmpty: true,
        ElButton: {
          props: ['loading', 'disabled'],
          emits: ['click'],
          template: '<button @click="$emit(\'click\')"><slot /></button>',
        },
      },
      plugins: [pinia],
    },
  });
}

function readyJob() {
  return {
    jobId: 'job-1',
    scopeType: 'PROJECT_INITIALIZATION' as const,
    scopePayload: { type: 'PROJECT_INITIALIZATION' as const },
    status: 'READY_FOR_ANALYSIS' as const,
    result: null,
    phase: 'READY_FOR_ANALYSIS' as const,
    revision: 'abc',
    totalUnits: 3,
    completedUnits: 0,
    failedUnits: 0,
    reviewRequestIds: [],
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-07-28T00:00:00Z',
    startedAt: '2026-07-28T00:00:01Z',
    completedAt: '2026-07-28T00:00:02Z',
    updatedAt: '2026-07-28T00:00:02Z',
    discoveredFileCount: 20,
    supportedCodeCount: 12,
    skippedFileCount: 8,
    skippedReasonCounts: {},
    metadataParsedCount: 12,
    metadataFailedCount: 0,
    boundFileCount: 2,
    unboundFileCount: 10,
    analysisUnitCount: 3,
    overlappingFileCount: 2,
    plannerStatus: 'COMPLETED',
    plannedUnitCount: 3,
    pendingUnitCount: 0,
    runningUnitCount: 0,
    completedUnitCount: 3,
    failedUnitCount: 0,
    noChangeUnitCount: 1,
    reviewSubmittedUnitCount: 2,
    currentPhase: 'COMPLETED',
    currentUnitNames: [],
  };
}
