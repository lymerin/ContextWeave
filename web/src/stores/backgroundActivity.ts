import axios from 'axios';
import { ElMessage } from 'element-plus';
import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import { getAgentJob, type AgentJob, type AgentJobStatus } from '@/api/agent';
import { getPendingDocumentChangeCount } from '@/api/documentChange';

const ACTIVE_POLL_DELAY = 4_000;
const IDLE_POLL_DELAY = 30_000;
const FOCUS_DEDUPLICATION_WINDOW = 500;
const MAX_PERSISTED_ENTRIES = 20;
const STORAGE_PREFIX = 'devcollab.background-activity:';

const TERMINAL_STATUSES = new Set<AgentJobStatus>([
  'COMPLETED',
  'PARTIALLY_COMPLETED',
  'FAILED',
  'CANCELLED',
]);

export interface WatchedAgentJob {
  jobId: string;
  workspaceId: string;
  repositoryId: string;
  label: string | null;
  filePath: string | null;
  createdAt: string;
  lastKnownStatus: AgentJobStatus;
}

interface PersistedBackgroundActivity {
  watchedJobs: WatchedAgentJob[];
  notifiedTerminalKeys: Array<[string, string]>;
}

export const useBackgroundActivityStore = defineStore('backgroundActivity', () => {
  const activeUserId = ref<string | null>(null);
  const activeWorkspaceId = ref<string | null>(null);
  const pendingReviewCounts = ref<Record<string, number>>({});
  const watchedJobs = ref<Record<string, WatchedAgentJob>>({});
  const jobContexts = ref<Record<string, WatchedAgentJob>>({});
  const jobSnapshots = ref<Record<string, AgentJob>>({});
  const reviewRefreshVersions = ref<Record<string, number>>({});
  const notifiedTerminalKeys = ref<Record<string, string>>({});

  let pollTimer: number | null = null;
  let started = false;
  let jobRefreshInFlight = false;
  let immediateRefreshInFlight = false;
  let lastImmediateRefreshAt = 0;
  const countRequestsInFlight = new Map<string, Promise<number | null>>();

  const pendingReviewCount = computed(() => {
    const workspaceId = activeWorkspaceId.value;
    return workspaceId ? pendingReviewCounts.value[workspaceId] ?? 0 : 0;
  });
  const runningJobCount = computed(() => Object.values(watchedJobs.value)
    .filter(job => !TERMINAL_STATUSES.has(job.lastKnownStatus)).length);

  async function startPolling(userId: string) {
    if (started && activeUserId.value === userId) {
      scheduleNextPoll();
      return;
    }
    if (started) stopPolling(true);
    activeUserId.value = userId;
    restoreSession();
    started = true;
    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('focus', handleWindowFocus);
    await refreshNow();
  }

  function stopPolling(clearUserState = true) {
    started = false;
    clearPollTimer();
    document.removeEventListener('visibilitychange', handleVisibilityChange);
    window.removeEventListener('focus', handleWindowFocus);
    jobRefreshInFlight = false;
    immediateRefreshInFlight = false;
    countRequestsInFlight.clear();
    if (!clearUserState) return;

    removeStoredSession();
    activeUserId.value = null;
    activeWorkspaceId.value = null;
    pendingReviewCounts.value = {};
    watchedJobs.value = {};
    jobContexts.value = {};
    jobSnapshots.value = {};
    reviewRefreshVersions.value = {};
    notifiedTerminalKeys.value = {};
  }

  function setActiveWorkspace(workspaceId: string | null) {
    if (activeWorkspaceId.value === workspaceId) return;
    activeWorkspaceId.value = workspaceId;
    if (started && workspaceId && document.visibilityState !== 'hidden') {
      void refreshPendingReviewCount(workspaceId);
    }
  }

  function setPendingReviewCount(workspaceId: string, count: number) {
    pendingReviewCounts.value = {
      ...pendingReviewCounts.value,
      [workspaceId]: Math.max(0, count),
    };
  }

  async function refreshPendingReviewCount(
    workspaceId = activeWorkspaceId.value,
  ): Promise<number | null> {
    if (!workspaceId) return null;
    const existing = countRequestsInFlight.get(workspaceId);
    if (existing) return existing;

    const request = getPendingDocumentChangeCount(workspaceId)
      .then((count) => {
        setPendingReviewCount(workspaceId, count);
        return count;
      })
      .catch(() => null)
      .finally(() => countRequestsInFlight.delete(workspaceId));
    countRequestsInFlight.set(workspaceId, request);
    return request;
  }

  function registerJob(input: WatchedAgentJob) {
    watchedJobs.value = {
      ...watchedJobs.value,
      [input.jobId]: {
        ...input,
        label: input.label || null,
        filePath: input.filePath || null,
      },
    };
    jobContexts.value = trimJobContexts({
      ...jobContexts.value,
      [input.jobId]: input,
    });
    persistSession();
    if (started && document.visibilityState !== 'hidden') {
      clearPollTimer();
      void refreshWatchedJobs().finally(() => scheduleNextPoll());
      return;
    }
  }

  function findActiveJob(input: {
    workspaceId: string;
    repositoryId: string;
    filePath?: string | null;
  }) {
    const matches = (job: WatchedAgentJob) => (
      job.workspaceId === input.workspaceId
      && job.repositoryId === input.repositoryId
      && (input.filePath === undefined || job.filePath === input.filePath)
    );
    return Object.values(watchedJobs.value).reverse().find(matches)
      ?? Object.values(jobContexts.value).reverse().find(job => (
        matches(job) && Boolean(jobSnapshots.value[job.jobId])
      ))
      ?? null;
  }

  function getJobSnapshot(jobId: string | null) {
    return jobId ? jobSnapshots.value[jobId] ?? null : null;
  }

  async function refreshWatchedJobs() {
    if (jobRefreshInFlight) return;
    const jobs = Object.values(watchedJobs.value);
    if (jobs.length === 0) return;

    jobRefreshInFlight = true;
    try {
      const results = await Promise.allSettled(jobs.map(async (watched) => {
        try {
          return { watched, job: await getAgentJob(watched.jobId) };
        } catch (error) {
          if (isExpiredOrInaccessible(error)) removeWatchedJob(watched.jobId);
          return null;
        }
      }));

      for (const result of results) {
        if (result.status !== 'fulfilled' || !result.value) continue;
        const { watched, job } = result.value;
        jobSnapshots.value = { ...jobSnapshots.value, [job.jobId]: job };
        watchedJobs.value = {
          ...watchedJobs.value,
          [job.jobId]: { ...watched, lastKnownStatus: job.status },
        };
        if (TERMINAL_STATUSES.has(job.status)) {
          await handleTerminalJob(watched, job);
          removeWatchedJob(job.jobId);
        }
      }
      persistSession();
    } finally {
      jobRefreshInFlight = false;
    }
  }

  function requestReviewRefresh(workspaceId: string) {
    reviewRefreshVersions.value = {
      ...reviewRefreshVersions.value,
      [workspaceId]: (reviewRefreshVersions.value[workspaceId] ?? 0) + 1,
    };
  }

  function reviewRefreshVersion(workspaceId: string) {
    return reviewRefreshVersions.value[workspaceId] ?? 0;
  }

  async function handleVisibilityChange() {
    if (!started) return;
    if (document.visibilityState === 'hidden') {
      clearPollTimer();
      return;
    }
    await requestImmediateRefresh();
  }

  async function handleWindowFocus() {
    if (!started || document.visibilityState === 'hidden') return;
    await requestImmediateRefresh();
  }

  async function requestImmediateRefresh() {
    const now = Date.now();
    if (
      immediateRefreshInFlight
      || now - lastImmediateRefreshAt < FOCUS_DEDUPLICATION_WINDOW
    ) return;
    lastImmediateRefreshAt = now;
    immediateRefreshInFlight = true;
    clearPollTimer();
    try {
      await Promise.all([
        refreshWatchedJobs(),
        refreshPendingReviewCount(),
      ]);
    } finally {
      immediateRefreshInFlight = false;
      scheduleNextPoll();
    }
  }

  async function refreshNow() {
    clearPollTimer();
    try {
      await Promise.all([
        refreshWatchedJobs(),
        refreshPendingReviewCount(),
      ]);
    } finally {
      scheduleNextPoll();
    }
  }

  function scheduleNextPoll(delay?: number) {
    if (!started || document.visibilityState === 'hidden') return;
    clearPollTimer();
    const nextDelay = delay ?? (runningJobCount.value > 0
      ? ACTIVE_POLL_DELAY
      : IDLE_POLL_DELAY);
    pollTimer = window.setTimeout(async () => {
      pollTimer = null;
      await refreshNow();
    }, nextDelay);
  }

  function clearPollTimer() {
    if (pollTimer === null) return;
    window.clearTimeout(pollTimer);
    pollTimer = null;
  }

  async function handleTerminalJob(watched: WatchedAgentJob, job: AgentJob) {
    await refreshPendingReviewCount(watched.workspaceId);
    requestReviewRefresh(watched.workspaceId);
    const terminalKey = `${job.status}:${job.updatedAt}`;
    if (notifiedTerminalKeys.value[job.jobId] === terminalKey) return;

    notifiedTerminalKeys.value = trimRecord({
      ...notifiedTerminalKeys.value,
      [job.jobId]: terminalKey,
    });
    persistSession();
    notifyTerminalJob(job);
  }

  function notifyTerminalJob(job: AgentJob) {
    if (job.status === 'PARTIALLY_COMPLETED') {
      ElMessage.warning('Agent 处理部分完成，请检查待审批内容和任务详情。');
      return;
    }
    if (job.status === 'FAILED') {
      ElMessage.error('Agent 处理失败，请查看任务详情。');
      return;
    }
    if (job.status === 'CANCELLED') {
      ElMessage.warning('Agent 处理已取消。');
      return;
    }
    if (job.result === 'REVIEW_SUBMITTED' || job.reviewRequestIds.length > 0) {
      ElMessage.success('Agent 处理完成，已生成待审批变更。');
      return;
    }
    if (job.result === 'NO_CHANGE') {
      ElMessage.success('Agent 处理完成，未生成新的待审批变更。');
      return;
    }
    ElMessage.success('Agent 处理完成。');
  }

  function removeWatchedJob(jobId: string) {
    const { [jobId]: _removed, ...remaining } = watchedJobs.value;
    watchedJobs.value = remaining;
    persistSession();
  }

  function restoreSession() {
    const key = storageKey();
    if (!key) return;
    try {
      const raw = sessionStorage.getItem(key);
      if (!raw) return;
      const parsed = JSON.parse(raw) as PersistedBackgroundActivity;
      watchedJobs.value = Object.fromEntries(
        (parsed.watchedJobs ?? [])
          .slice(-MAX_PERSISTED_ENTRIES)
          .map(job => [job.jobId, job]),
      );
      jobContexts.value = { ...watchedJobs.value };
      notifiedTerminalKeys.value = Object.fromEntries(
        (parsed.notifiedTerminalKeys ?? []).slice(-MAX_PERSISTED_ENTRIES),
      );
    } catch {
      sessionStorage.removeItem(key);
      watchedJobs.value = {};
      jobContexts.value = {};
      notifiedTerminalKeys.value = {};
    }
  }

  function persistSession() {
    const key = storageKey();
    if (!key) return;
    const jobs = Object.values(watchedJobs.value)
      .slice(-MAX_PERSISTED_ENTRIES);
    const notified = Object.entries(notifiedTerminalKeys.value)
      .slice(-MAX_PERSISTED_ENTRIES);
    sessionStorage.setItem(key, JSON.stringify({
      watchedJobs: jobs,
      notifiedTerminalKeys: notified,
    } satisfies PersistedBackgroundActivity));
  }

  function removeStoredSession() {
    const key = storageKey();
    if (key) sessionStorage.removeItem(key);
  }

  function storageKey() {
    return activeUserId.value ? `${STORAGE_PREFIX}${activeUserId.value}` : null;
  }

  return {
    activeWorkspaceId,
    pendingReviewCount,
    pendingReviewCounts,
    watchedJobs,
    jobSnapshots,
    runningJobCount,
    startPolling,
    stopPolling,
    setActiveWorkspace,
    setPendingReviewCount,
    refreshPendingReviewCount,
    registerJob,
    findActiveJob,
    getJobSnapshot,
    refreshWatchedJobs,
    requestReviewRefresh,
    reviewRefreshVersion,
    handleVisibilityChange,
    handleWindowFocus,
  };
});

function trimRecord(values: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(values).slice(-MAX_PERSISTED_ENTRIES),
  );
}

function trimJobContexts(values: Record<string, WatchedAgentJob>) {
  return Object.fromEntries(
    Object.entries(values).slice(-MAX_PERSISTED_ENTRIES),
  );
}

function isExpiredOrInaccessible(error: unknown) {
  return axios.isAxiosError(error)
    && [401, 403, 404].includes(error.response?.status ?? 0);
}
