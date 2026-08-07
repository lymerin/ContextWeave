import { flushPromises, mount } from '@vue/test-utils';
import { ElMessage } from 'element-plus';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { defineComponent } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createAgentJob, getAgentJob, type AgentJobStatus } from '@/api/agent';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';
import LinkedCodePane from './LinkedCodePane.vue';

vi.mock('@/api/agent', () => ({
  createAgentJob: vi.fn(),
  getAgentJob: vi.fn(),
  readableAgentError: (_error: unknown, fallback: string) => fallback,
}));
vi.mock('@/api/documentChange', () => ({
  getPendingDocumentChangeCount: vi.fn().mockResolvedValue(0),
}));

let pinia: Pinia;

const ElButtonStub = defineComponent({
  inheritAttrs: false,
  props: {
    disabled: Boolean,
    loading: Boolean,
  },
  emits: ['click'],
  template: `
    <button
      v-bind="$attrs"
      :disabled="disabled"
      :data-loading="String(loading)"
      @click="$emit('click')"
    ><slot /></button>
  `,
});
const ElDialogStub = defineComponent({
  props: {
    modelValue: Boolean,
    title: String,
  },
  emits: ['update:modelValue', 'closed'],
  template: `
    <section v-if="modelValue" role="dialog">
      <h2>{{ title }}</h2>
      <slot />
      <slot name="footer" />
    </section>
  `,
});
const ElInputStub = defineComponent({
  inheritAttrs: false,
  props: {
    modelValue: String,
  },
  emits: ['update:modelValue'],
  template: `
    <textarea
      v-bind="$attrs"
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)"
    />
  `,
});

const baseProps = {
  workspaceId: 'workspace-1',
  repositoryId: 'repository-1',
  content: 'class Example {}',
  path: 'src/Example.java',
  language: 'Java',
  anchors: [],
  links: [],
  issues: [],
  activeLinkId: null,
  sourceLoaded: true,
};

function mountPane(overrides: Record<string, unknown> = {}) {
  return mount(LinkedCodePane, {
    props: {
      ...baseProps,
      ...overrides,
    },
    global: {
      stubs: {
        ElButton: ElButtonStub,
        ElDialog: ElDialogStub,
        ElInput: ElInputStub,
        ElTag: true,
        ElSkeleton: true,
        ElEmpty: true,
      },
      plugins: [pinia],
    },
  });
}

async function startCheck(wrapper: ReturnType<typeof mountPane>) {
  await wrapper.get('[data-testid="agent-check-button"]').trigger('click');
  await wrapper.get('[data-testid="agent-start-button"]').trigger('click');
  await flushPromises();
}

describe('LinkedCodePane Agent check', () => {
  beforeEach(async () => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    localStorage.clear();
    vi.spyOn(ElMessage, 'success').mockReturnValue({ close: vi.fn() });
    vi.spyOn(ElMessage, 'error').mockReturnValue({ close: vi.fn() });
    vi.mocked(createAgentJob).mockResolvedValue({
      jobId: 'job-1',
      status: 'QUEUED',
      createdAt: '2026-07-28T00:00:00Z',
    });
    pinia = createPinia();
    setActivePinia(pinia);
    const backgroundActivity = useBackgroundActivityStore();
    backgroundActivity.setActiveWorkspace('workspace-1');
    await backgroundActivity.startPolling('user-1');
  });

  afterEach(() => {
    useBackgroundActivityStore().stopPolling(false);
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('disables Agent check until a real source file is loaded', () => {
    const wrapper = mountPane({
      path: '',
      content: '',
      sourceLoaded: false,
    });

    expect(wrapper.get('[data-testid="agent-check-button"]').attributes('disabled')).toBeDefined();
  });

  it('submits a full project pipeline (single-file analysis deprecated) and stops polling on NO_CHANGE', async () => {
    vi.mocked(getAgentJob)
      .mockResolvedValueOnce({
        ...agentJob('RUNNING'),
        phase: 'MODEL_RUNNING',
      })
      .mockResolvedValueOnce({
        ...agentJob('COMPLETED'),
        result: 'NO_CHANGE',
      });
    const wrapper = mountPane();

    await wrapper.get('[data-testid="agent-check-button"]').trigger('click');
    await wrapper.get('[data-testid="agent-instruction-input"]').setValue('核对接口说明');
    await wrapper.get('[data-testid="agent-start-button"]').trigger('click');
    await flushPromises();

    expect(createAgentJob).toHaveBeenCalledWith({
      workspaceId: 'workspace-1',
      repositoryId: 'repository-1',
      scope: { type: 'PROJECT_INITIALIZATION' },
      userInstruction: '核对接口说明',
    });
    expect(getAgentJob).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBeGreaterThan(0);

    await vi.advanceTimersByTimeAsync(4000);
    await flushPromises();

    expect(getAgentJob).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(4000);
    expect(getAgentJob).toHaveBeenCalledTimes(2);
    expect(wrapper.get('[data-testid="agent-check-button"]').text()).toContain('Agent 检查');
  });

  it('stops polling and exposes the existing review navigation on REVIEW_SUBMITTED', async () => {
    vi.mocked(getAgentJob).mockResolvedValue({
      ...agentJob('COMPLETED'),
      result: 'REVIEW_SUBMITTED',
      reviewRequestIds: ['change-1'],
    });
    const wrapper = mountPane();

    await startCheck(wrapper);

    await vi.advanceTimersByTimeAsync(4000);
    expect(getAgentJob).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="agent-review-button"]').text()).toContain('查看评审');
    await wrapper.get('[data-testid="agent-review-button"]').trigger('click');
    expect(wrapper.emitted('open-agent-review')).toEqual([['change-1']]);
  });

  it('stops polling and keeps the entry reusable after FAILED', async () => {
    vi.mocked(getAgentJob).mockResolvedValue({
      ...agentJob('FAILED'),
      errorCode: 'MODEL_PROVIDER_ERROR',
      errorMessage: 'Agent 服务暂时不可用',
    });
    const wrapper = mountPane();

    await startCheck(wrapper);

    await vi.advanceTimersByTimeAsync(5000);
    expect(getAgentJob).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="agent-check-button"]').attributes('disabled')).toBeUndefined();
    expect(ElMessage.error).toHaveBeenCalledWith('Agent 处理失败，请查看任务详情。');
  });

  it('keeps the single global poll alive when the component is unmounted', async () => {
    vi.mocked(getAgentJob).mockResolvedValue({
      ...agentJob('RUNNING'),
      phase: 'MODEL_RUNNING',
    });
    const wrapper = mountPane();

    await startCheck(wrapper);
    expect(vi.getTimerCount()).toBeGreaterThan(0);

    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(4000);
    expect(getAgentJob).toHaveBeenCalledTimes(2);
  });
});

function agentJob(status: AgentJobStatus) {
  return {
    jobId: 'job-1',
    status,
    scopeType: 'PROJECT_INITIALIZATION' as const,
    scopePayload: { type: 'PROJECT_INITIALIZATION' as const },
    result: null,
    phase: null,
    revision: 'abc',
    totalUnits: 1,
    completedUnits: 0,
    failedUnits: 0,
    reviewRequestIds: [],
    errorCode: null,
    errorMessage: null,
    createdAt: '2026-07-28T00:00:00Z',
    startedAt: null,
    completedAt: null,
    updatedAt: '2026-07-28T00:00:00Z',
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
  };
}
