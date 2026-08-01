import { setActivePinia, createPinia } from 'pinia';
import { ElMessage } from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getAgentJob, type AgentJob, type AgentJobStatus } from '@/api/agent';
import { getPendingDocumentChangeCount } from '@/api/documentChange';
import { useBackgroundActivityStore } from './backgroundActivity';

vi.mock('@/api/agent', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/agent')>(),
  getAgentJob: vi.fn(),
}));
vi.mock('@/api/documentChange', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/documentChange')>(),
  getPendingDocumentChangeCount: vi.fn(),
}));

let visibilityState: DocumentVisibilityState = 'visible';

describe('background activity store', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    sessionStorage.clear();
    visibilityState = 'visible';
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibilityState,
    });
    setActivePinia(createPinia());
    vi.mocked(getPendingDocumentChangeCount).mockResolvedValue(0);
    vi.mocked(getAgentJob).mockResolvedValue(agentJob('RUNNING'));
    vi.spyOn(ElMessage, 'success').mockReturnValue({ close: vi.fn() });
    vi.spyOn(ElMessage, 'warning').mockReturnValue({ close: vi.fn() });
    vi.spyOn(ElMessage, 'error').mockReturnValue({ close: vi.fn() });
  });

  afterEach(() => {
    useBackgroundActivityStore().stopPolling(false);
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('loads the current workspace count and preserves the last good value on failure', async () => {
    vi.mocked(getPendingDocumentChangeCount)
      .mockResolvedValueOnce(3)
      .mockRejectedValueOnce(new Error('offline'));
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');

    await store.startPolling('user-1');
    expect(store.pendingReviewCount).toBe(3);

    await store.refreshPendingReviewCount();
    expect(store.pendingReviewCount).toBe(3);
  });

  it('deduplicates concurrent count requests', async () => {
    const pending = deferred<number>();
    vi.mocked(getPendingDocumentChangeCount).mockReturnValue(pending.promise);
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');
    const first = store.refreshPendingReviewCount();
    const second = store.refreshPendingReviewCount();

    expect(getPendingDocumentChangeCount).toHaveBeenCalledTimes(1);
    pending.resolve(4);
    await Promise.all([first, second]);
    expect(store.pendingReviewCount).toBe(4);
  });

  it('uses one 30 second timer while idle and a 4 second timer for active jobs', async () => {
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');
    await store.startPolling('user-1');
    expect(vi.getTimerCount()).toBe(1);

    store.registerJob(watchedJob());
    await vi.runOnlyPendingTimersAsync();
    expect(vi.getTimerCount()).toBe(1);

    const calls = vi.mocked(getAgentJob).mock.calls.length;
    await vi.advanceTimersByTimeAsync(3_999);
    expect(getAgentJob).toHaveBeenCalledTimes(calls);
    await vi.advanceTimersByTimeAsync(1);
    expect(getAgentJob).toHaveBeenCalledTimes(calls + 1);
  });

  it('keeps a single timer when application lifecycle starts polling twice', async () => {
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');

    await store.startPolling('user-1');
    const timerCount = vi.getTimerCount();
    await store.startPolling('user-1');

    expect(vi.getTimerCount()).toBe(timerCount);
  });

  it('does not overlap watched job requests', async () => {
    const pending = deferred<AgentJob>();
    vi.mocked(getAgentJob).mockReturnValue(pending.promise);
    const store = useBackgroundActivityStore();
    store.registerJob(watchedJob());

    const first = store.refreshWatchedJobs();
    const second = store.refreshWatchedJobs();
    expect(getAgentJob).toHaveBeenCalledTimes(1);
    pending.resolve(agentJob('RUNNING'));
    await Promise.all([first, second]);
  });

  it('pauses while hidden and refreshes once when visible and focused together', async () => {
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');
    await store.startPolling('user-1');

    visibilityState = 'hidden';
    await store.handleVisibilityChange();
    expect(vi.getTimerCount()).toBe(0);

    const countCalls = vi.mocked(getPendingDocumentChangeCount).mock.calls.length;
    visibilityState = 'visible';
    await Promise.all([
      store.handleVisibilityChange(),
      store.handleWindowFocus(),
    ]);
    expect(getPendingDocumentChangeCount).toHaveBeenCalledTimes(countCalls + 1);
    expect(vi.getTimerCount()).toBe(1);
  });

  it('restores unfinished jobs from sessionStorage and removes inaccessible jobs', async () => {
    sessionStorage.setItem('devcollab.background-activity:user-1', JSON.stringify({
      watchedJobs: [watchedJob()],
      notifiedTerminalKeys: [],
    }));
    vi.mocked(getAgentJob).mockRejectedValue({
      isAxiosError: true,
      response: { status: 404 },
    });
    const store = useBackgroundActivityStore();

    await store.startPolling('user-1');

    expect(getAgentJob).toHaveBeenCalledWith('job-1');
    expect(store.runningJobCount).toBe(0);
    expect(sessionStorage.getItem('devcollab.background-activity:user-1'))
      .toContain('"watchedJobs":[]');
  });

  it('notifies a terminal job once, refreshes count and signals the review page', async () => {
    vi.mocked(getAgentJob).mockResolvedValue(agentJob('COMPLETED', {
      result: 'REVIEW_SUBMITTED',
      reviewRequestIds: ['review-1'],
    }));
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');
    store.registerJob(watchedJob());

    await store.startPolling('user-1');

    expect(ElMessage.success).toHaveBeenCalledWith(
      'Agent 处理完成，已生成待审批变更。',
    );
    expect(store.reviewRefreshVersion('workspace-1')).toBe(1);
    expect(store.runningJobCount).toBe(0);

    await store.refreshWatchedJobs();
    expect(ElMessage.success).toHaveBeenCalledTimes(1);
  });

  it('reports a completed job with no review as NO_CHANGE without a false badge claim', async () => {
    vi.mocked(getAgentJob).mockResolvedValue(agentJob('COMPLETED', {
      result: 'NO_CHANGE',
    }));
    const store = useBackgroundActivityStore();
    store.registerJob(watchedJob());

    await store.startPolling('user-1');

    expect(ElMessage.success).toHaveBeenCalledWith(
      'Agent 处理完成，未生成新的待审批变更。',
    );
  });

  it.each([
    ['PARTIALLY_COMPLETED', 'warning', 'Agent 处理部分完成，请检查待审批内容和任务详情。'],
    ['FAILED', 'error', 'Agent 处理失败，请查看任务详情。'],
    ['CANCELLED', 'warning', 'Agent 处理已取消。'],
  ] as const)('uses the correct toast for %s', async (status, method, message) => {
    vi.mocked(getAgentJob).mockResolvedValue(agentJob(status));
    const store = useBackgroundActivityStore();
    store.registerJob(watchedJob());

    await store.startPolling('user-1');

    expect(ElMessage[method]).toHaveBeenCalledWith(message);
  });

  it('does not repeat a persisted terminal notification after reload', async () => {
    sessionStorage.setItem('devcollab.background-activity:user-1', JSON.stringify({
      watchedJobs: [watchedJob()],
      notifiedTerminalKeys: [['job-1', 'COMPLETED:2026-07-30T00:00:05Z']],
    }));
    vi.mocked(getAgentJob).mockResolvedValue(agentJob('COMPLETED', {
      result: 'REVIEW_SUBMITTED',
      reviewRequestIds: ['review-1'],
    }));
    const store = useBackgroundActivityStore();

    await store.startPolling('user-1');

    expect(ElMessage.success).not.toHaveBeenCalled();
  });

  it('clears count, jobs, timer and session data on logout', async () => {
    vi.mocked(getPendingDocumentChangeCount).mockResolvedValue(6);
    const store = useBackgroundActivityStore();
    store.setActiveWorkspace('workspace-1');
    store.registerJob(watchedJob());
    await store.startPolling('user-1');

    store.stopPolling();

    expect(store.pendingReviewCount).toBe(0);
    expect(store.runningJobCount).toBe(0);
    expect(sessionStorage.getItem('devcollab.background-activity:user-1')).toBeNull();
    const calls = vi.mocked(getAgentJob).mock.calls.length;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(getAgentJob).toHaveBeenCalledTimes(calls);
  });
});

function watchedJob() {
  return {
    jobId: 'job-1',
    workspaceId: 'workspace-1',
    repositoryId: 'repository-1',
    label: 'Example',
    filePath: 'src/Example.java',
    createdAt: '2026-07-30T00:00:00Z',
    lastKnownStatus: 'QUEUED' as const,
  };
}

function agentJob(
  status: AgentJobStatus,
  overrides: Partial<AgentJob> = {},
): AgentJob {
  return {
    jobId: 'job-1',
    scopeType: 'CURRENT_FILE',
    scopePayload: { type: 'CURRENT_FILE', filePath: 'src/Example.java' },
    status,
    result: null,
    phase: status === 'RUNNING' ? 'MODEL_RUNNING' : null,
    revision: 'abc',
    totalUnits: 1,
    completedUnits: 0,
    failedUnits: 0,
    reviewRequestIds: [],
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-07-30T00:00:00Z',
    startedAt: '2026-07-30T00:00:01Z',
    completedAt: TERMINAL_FOR_TEST.has(status) ? '2026-07-30T00:00:05Z' : null,
    updatedAt: '2026-07-30T00:00:05Z',
    discoveredFileCount: 0,
    supportedCodeCount: 0,
    skippedFileCount: 0,
    skippedReasonCounts: {},
    metadataParsedCount: 0,
    metadataFailedCount: 0,
    boundFileCount: 0,
    unboundFileCount: 0,
    analysisUnitCount: 0,
    overlappingFileCount: 0,
    plannerStatus: null,
    plannedUnitCount: 0,
    pendingUnitCount: 0,
    runningUnitCount: 0,
    completedUnitCount: 0,
    failedUnitCount: 0,
    noChangeUnitCount: 0,
    reviewSubmittedUnitCount: 0,
    currentPhase: null,
    currentUnitNames: [],
    ...overrides,
  };
}

const TERMINAL_FOR_TEST = new Set<AgentJobStatus>([
  'READY_FOR_ANALYSIS',
  'COMPLETED',
  'PARTIALLY_COMPLETED',
  'FAILED',
  'CANCELLED',
]);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
