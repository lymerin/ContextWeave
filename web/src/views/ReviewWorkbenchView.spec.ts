import { mount, flushPromises } from '@vue/test-utils';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { createRouter, createMemoryHistory, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { ref } from 'vue';

import ReviewWorkbenchView from './ReviewWorkbenchView.vue';
import * as documentChangeApi from '@/api/documentChange';
import * as blockApi from '@/api/block';
import * as gitApi from '@/api/git';
import * as workspaceApi from '@/api/workspace';
import {
  createLinkedWorkbenchSnapshot,
  resetLinkedWorkbenchNavigationMemoryForTests,
  useLinkedWorkbenchNavigation,
} from '@/composables/useLinkedWorkbenchNavigation';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';

// Mock dependencies
vi.mock('@/api/documentChange');
vi.mock('@/api/block');
vi.mock('@/api/git');
vi.mock('@/api/workspace');
vi.mock('@/components/layout/AppSidebar.vue', () => ({ default: { template: '<div>Sidebar</div>' } }));
vi.mock('@/components/linked-workbench/LinkedRepositoryContext.vue', () => ({ default: { template: '<div>Context</div>' } }));
vi.mock('@/components/review/ReviewCodeEvidencePane.vue', () => ({ default: { template: '<div>CodePane</div>', props: ['evidence'] } }));
vi.mock('@/components/review/ReviewDocumentPane.vue', () => ({ default: { template: '<div>DocPane</div>', props: ['blocks'] } }));
vi.mock('@/components/review/ReviewInspector.vue', () => ({ default: { template: '<div>Inspector</div>' } }));

function createDeferred<T = any>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: any) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe('ReviewWorkbenchView asynchronous state and race conditions', () => {
  let router: Router;
  let pinia: Pinia;

  beforeEach(async () => {
    vi.resetAllMocks();
    sessionStorage.clear();
    resetLinkedWorkbenchNavigationMemoryForTests();
    
    // Default successful mocks
    vi.mocked(workspaceApi.getWorkspace).mockResolvedValue({ id: 'w1', name: 'Workspace 1' } as any);
    vi.mocked(gitApi.listGitRepositories).mockResolvedValue([]);
    vi.mocked(gitApi.listGitRepositoryFiles).mockResolvedValue([]);
    vi.mocked(documentChangeApi.listDocumentChanges).mockResolvedValue({ items: [], totalElements: 0, page: 0, size: 20, totalPages: 0 });
    vi.mocked(documentChangeApi.getPendingDocumentChangeCount).mockResolvedValue(0);
    pinia = createPinia();
    setActivePinia(pinia);

    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/workspaces/:workspaceId/reviews/:status/:requestId?',
          name: 'workspace-review-detail',
          component: ReviewWorkbenchView,
        },
        {
          path: '/workspaces/:workspaceId/code',
          name: 'workspace-code',
          component: { template: '<div>Code</div>' },
        },
      ]
    });
    
    // Mock localStorage
    vi.spyOn(Storage.prototype, 'getItem').mockReturnValue('false');
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  const mountView = async (initialPath: string) => {
    router.push(initialPath);
    await router.isReady();
    const wrapper = mount(ReviewWorkbenchView, {
      global: {
        plugins: [router, pinia, ElementPlus],
        stubs: {
          AppSidebar: true,
          LinkedRepositoryContext: true,
          ReviewCodeEvidencePane: true,
          ReviewDocumentPane: true,
          ReviewInspector: true,
        },
      },
    });
    await flushPromises();
    return wrapper;
  };

  const mockRequestDetail = (id: string, ops: any[] = [], requestEvidence: any[] = []) => ({
    request: {
      id,
      workspaceId: 'w1',
      status: 'PENDING',
      summary: 'Test Request',
      rationale: 'Review rationale',
      sourceType: 'MCP',
      submittedBy: { id: 'user-1', displayName: 'Reviewer' },
      createdAt: '2026-07-29T08:00:00Z',
      reviewedBy: null,
      reviewedAt: null,
      rejectionReason: null,
    },
    operations: ops,
    bindingProposals: [],
    requestEvidence,
    replayed: false,
  });

  it('distinguishes a missing request from an existing request without operations', async () => {
    vi.mocked(documentChangeApi.getDocumentChange).mockRejectedValue({
      isAxiosError: true,
      response: { status: 404 },
    });

    const wrapper = await mountView('/workspaces/w1/reviews/pending/missing-request');

    expect(wrapper.text()).toContain('评审请求不存在');
    expect(wrapper.text()).not.toContain('没有可执行 Operation');
  });

  it('shows request metadata and Evidence when an existing request has no operations', async () => {
    const requestEvidence = [{
      id: 'evidence-1',
      repository: { id: 'repository-1', name: 'Repository' },
      filePath: 'agent-service/app/context/budget.py',
      commitHash: 'revision-1234567890',
      startLine: 10,
      endLine: 20,
      description: '预算上下文证据',
      excerptText: 'context budget',
    }];
    vi.mocked(gitApi.listGitRepositories).mockResolvedValue([{
      id: 'repository-1',
      name: 'Repository',
      defaultBranch: 'main',
      lastSyncedCommit: 'revision-1234567890',
    }] as any);
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(
      mockRequestDetail('request-empty', [], requestEvidence) as any,
    );

    const wrapper = await mountView('/workspaces/w1/reviews/pending/request-empty');
    const emptyState = wrapper.get('[data-test="review-empty-operations"]');

    expect(emptyState.text()).toContain('Test Request');
    expect(emptyState.text()).toContain('该评审当前没有可执行 Operation');
    expect(emptyState.text()).toContain('agent-service/app/context/budget.py');
    expect(emptyState.text()).toContain('预算上下文证据');
    expect(wrapper.text()).not.toContain('评审请求不存在');

    await (wrapper.vm as any).loadDetail();
    await flushPromises();
    expect((wrapper.vm as any).detail.request.id).toBe('request-empty');
    expect((wrapper.vm as any).detail.request.status).toBe('PENDING');
    expect(documentChangeApi.applyDocumentChange).not.toHaveBeenCalled();
  });

  it('keeps the normal approval surface for a request with operations', async () => {
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(
      mockRequestDetail('request-ready', [{
        operationId: 'operation-1',
        clientOperationId: 'client-1',
        sequenceNumber: 1,
        operationType: 'CREATE_DOCUMENT',
        target: { documentId: null },
        proposal: {},
        conflict: { conflicted: false },
        evidence: [],
      }]) as any,
    );

    const wrapper = await mountView('/workspaces/w1/reviews/pending/request-ready');

    expect(wrapper.find('.review-four-area').exists()).toBe(true);
    expect(wrapper.find('[data-test="review-empty-operations"]').exists()).toBe(false);
  });

  it('passes the list item id unchanged through the route to the detail API', async () => {
    vi.mocked(documentChangeApi.listDocumentChanges).mockResolvedValue({
      items: [{
        id: 'request-from-list',
        summary: 'List request',
        status: 'PENDING',
        sourceType: 'MCP',
        submittedByDisplayName: 'Reviewer',
        createdAt: '2026-07-29T08:00:00Z',
        reviewedAt: null,
        operationCount: 0,
        bindingProposalCount: 0,
        evidenceCount: 3,
        affectedDocumentTitles: [],
      }],
      totalElements: 1,
      page: 0,
      size: 20,
      totalPages: 1,
    });
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(
      mockRequestDetail('request-from-list') as any,
    );
    const wrapper = await mountView('/workspaces/w1/reviews/pending');

    await wrapper.get('.review-request-item').trigger('click');
    await flushPromises();

    expect(router.currentRoute.value.params.requestId).toBe('request-from-list');
    expect(documentChangeApi.getDocumentChange).toHaveBeenCalledWith(
      'w1',
      'request-from-list',
    );
    expect((wrapper.vm as any).detail.request.status).toBe('PENDING');
  });

  it('1. 详情请求乱序: B arrives before A, page should show B', async () => {
    const deferredA = createDeferred();
    const deferredB = createDeferred();
    
    vi.mocked(documentChangeApi.getDocumentChange).mockImplementation(async (_w, id) => {
      if (id === 'rA') return deferredA.promise;
      if (id === 'rB') return deferredB.promise;
      return null;
    });

    const wrapper = await mountView('/workspaces/w1/reviews/pending/rA');
    
    // Switch to rB before rA resolves
    router.push('/workspaces/w1/reviews/pending/rB');
    await flushPromises();

    // rB resolves first
    deferredB.resolve(mockRequestDetail('rB'));
    await flushPromises();

    // rA resolves later (stale)
    deferredA.resolve(mockRequestDetail('rA'));
    await flushPromises();

    // The current request should be rB
    const text = wrapper.text();
    expect(text).toContain('Test Request'); // From B
    expect((wrapper.vm as any).detail?.request.id).toBe('rB');
  });

  it('2. 详情旧错误响应: A fails after B succeeds, error should not overwrite B', async () => {
    const deferredA = createDeferred();
    const deferredB = createDeferred();
    
    vi.mocked(documentChangeApi.getDocumentChange).mockImplementation(async (_w, id) => {
      if (id === 'rA') return deferredA.promise;
      if (id === 'rB') return deferredB.promise;
      return null;
    });

    const wrapper = await mountView('/workspaces/w1/reviews/pending/rA');
    
    router.push('/workspaces/w1/reviews/pending/rB');
    await flushPromises();

    deferredB.resolve(mockRequestDetail('rB'));
    await flushPromises();

    // rA fails
    deferredA.reject(new Error('Network error'));
    await flushPromises();

    expect((wrapper.vm as any).errorMessage).toBe(''); // Error should be ignored
    expect((wrapper.vm as any).detail?.request.id).toBe('rB');
  });

  it('3. Operation 文档请求乱序: Op2 resolves before Op1, Op2 should win', async () => {
    const ops = [
      { operationId: 'op1', target: { documentId: 'doc1' }, operationType: 'UPDATE_BLOCK' },
      { operationId: 'op2', target: { documentId: 'doc2' }, operationType: 'UPDATE_BLOCK' }
    ];
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(mockRequestDetail('r1', ops) as any);
    
    const deferredDoc1 = createDeferred();
    const deferredDoc2 = createDeferred();
    
    vi.mocked(blockApi.listBlocks).mockImplementation(async (id) => {
      if (id === 'doc1') return deferredDoc1.promise;
      if (id === 'doc2') return deferredDoc2.promise;
      return [];
    });

    const wrapper = await mountView('/workspaces/w1/reviews/pending/r1?operationId=op1');
    
    // Switch to op2
    router.push({ query: { operationId: 'op2' } });
    await flushPromises();

    // Doc2 resolves first
    deferredDoc2.resolve([{ id: 'block2' }]);
    await flushPromises();

    // Doc1 resolves later
    deferredDoc1.resolve([{ id: 'block1' }]);
    await flushPromises();

    expect((wrapper.vm as any).documentBlocks).toHaveLength(1);
    expect((wrapper.vm as any).documentBlocks[0].id).toBe('block2');
  });

  it('4. workspaceId 切换: w1 stale request resolves after switching to w2', async () => {
    const deferredW1 = createDeferred();
    vi.mocked(documentChangeApi.getDocumentChange).mockImplementation(async (w, _id) => {
      if (w === 'w1') return deferredW1.promise;
      if (w === 'w2') return mockRequestDetail('r2') as any;
      return null;
    });

    const wrapper = await mountView('/workspaces/w1/reviews/pending/r1');
    
    router.push('/workspaces/w2/reviews/pending/r2');
    await flushPromises();

    deferredW1.resolve(mockRequestDetail('r1'));
    await flushPromises();

    // w2 should be active, w1 result ignored
    expect((wrapper.vm as any).workspaceId).toBe('w2');
    expect((wrapper.vm as any).detail?.request.id).toBe('r2');
  });

  it('5. 无 Evidence: Should not call file API and should clear path', async () => {
    const ops = [{ operationId: 'op1', target: { documentId: 'doc1' }, operationType: 'UPDATE_BLOCK' }];
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(mockRequestDetail('r1', ops) as any);
    
    const wrapper = await mountView('/workspaces/w1/reviews/pending/r1');
    
    expect((wrapper.vm as any).selectedFilePath).toBe('');
    expect((wrapper.vm as any).selectedRepositoryId).toBe('');
    expect(gitApi.getGitRepositorySource).not.toHaveBeenCalled();
    expect(gitApi.listGitRepositoryFiles).toHaveBeenCalledTimes(0);
  });

  it('6. 快速连续切换: Select Op1, Op2, Op3, Op3 should win', async () => {
    const ops = [
      { operationId: 'op1', target: { documentId: 'doc1' }, operationType: 'UPDATE_BLOCK' },
      { operationId: 'op2', target: { documentId: 'doc2' }, operationType: 'UPDATE_BLOCK' },
      { operationId: 'op3', target: { documentId: 'doc3' }, operationType: 'UPDATE_BLOCK' }
    ];
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(mockRequestDetail('r1', ops) as any);
    
    const d1 = createDeferred();
    const d2 = createDeferred();
    const d3 = createDeferred();
    
    vi.mocked(blockApi.listBlocks).mockImplementation(async (id) => {
      if (id === 'doc1') return d1.promise;
      if (id === 'doc2') return d2.promise;
      if (id === 'doc3') return d3.promise;
      return [];
    });

    const wrapper = await mountView('/workspaces/w1/reviews/pending/r1?operationId=op1');
    
    router.push({ query: { operationId: 'op2' } });
    await flushPromises();
    
    router.push({ query: { operationId: 'op3' } });
    await flushPromises();

    // Resolve out of order
    d2.resolve([{ id: 'block2' }]);
    d1.resolve([{ id: 'block1' }]);
    d3.resolve([{ id: 'block3' }]);
    
    await flushPromises();

    expect((wrapper.vm as any).documentBlocks[0].id).toBe('block3');
  });

  it('keeps A during apply and only navigates to B after explicit view-result action', async () => {
    const scope = {
      workspaceId: 'w1',
      repositoryId: 'repository-1',
      revision: 'revision-1',
    };
    const navigation = useLinkedWorkbenchNavigation(ref(scope));
    const snapshotA = createLinkedWorkbenchSnapshot(scope, 'src/A.java', 'document-a');
    navigation.updateCurrent(snapshotA);

    vi.mocked(gitApi.listGitRepositories).mockResolvedValue([{
      id: 'repository-1',
      name: 'repository',
      defaultBranch: 'main',
      lastSyncedCommit: 'revision-1',
    }] as any);
    vi.mocked(gitApi.queryCodeBindings).mockResolvedValue({
      workspaceId: 'w1',
      repositoryId: 'repository-1',
      filePath: 'src/B.java',
      fileHasBindings: true,
      bindings: [{
        bindingId: 'binding-b',
        workspaceId: 'w1',
        repositoryId: 'repository-1',
        revision: 'revision-1',
        anchorKind: 'FILE',
        symbolKey: null,
        startLine: null,
        endLine: null,
        documentId: 'document-b',
        blockId: null,
        targetKey: 'DOCUMENT',
        pathPattern: 'src/B.java',
        documentTitle: 'Document B',
        matchedFilePath: 'src/B.java',
        blockExists: false,
      }],
      truncated: false,
      omittedBindingCount: 0,
    });
    const applied = {
      ...mockRequestDetail('r1', [{
        operationId: 'operation-1',
        operationType: 'UPDATE_BLOCK',
        target: { documentId: 'document-b' },
      }]),
      request: {
        id: 'r1',
        status: 'APPLIED',
        summary: 'Applied request',
      },
      bindingProposals: [{
        bindingProposalId: 'proposal-1',
        clientBindingProposalId: 'client-1',
        sequenceNumber: 1,
        action: 'UPSERT_BINDING',
        repository: { id: 'repository-1', name: 'repository' },
        filePath: 'src/B.java',
        documentTarget: {
          documentId: 'document-b',
          documentTitle: 'Document B',
          blockId: null,
          blockType: null,
        },
        bindingId: null,
        reason: '',
      }],
    } as any;
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(applied);
    vi.mocked(documentChangeApi.applyDocumentChange).mockResolvedValue(applied);
    vi.mocked(blockApi.listBlocks).mockResolvedValue([]);

    const wrapper = await mountView('/workspaces/w1/reviews/applied/r1');
    expect(navigation.restoreCurrent()).toEqual(snapshotA);
    expect((wrapper.vm as any).appliedNavigationTargets).toHaveLength(1);

    vi.mocked(documentChangeApi.getPendingDocumentChangeCount).mockClear();
    await (wrapper.vm as any).applyRequest();
    expect(documentChangeApi.getPendingDocumentChangeCount).toHaveBeenCalledWith('w1');
    expect(navigation.restoreCurrent()).toEqual(snapshotA);

    await (wrapper.vm as any).viewAppliedTarget(
      (wrapper.vm as any).appliedNavigationTargets[0],
    );
    expect(navigation.restoreCurrent()).toEqual(createLinkedWorkbenchSnapshot(
      scope,
      'src/B.java',
      'document-b',
    ));
    expect(navigation.state.value.backStack).toEqual([snapshotA]);
  });

  it('returns through the linked navigation entry without replacing the saved reading target', async () => {
    const scope = {
      workspaceId: 'w1',
      repositoryId: 'repository-1',
      revision: 'revision-1',
    };
    const navigation = useLinkedWorkbenchNavigation(ref(scope));
    const snapshotA = createLinkedWorkbenchSnapshot(scope, 'src/A.java', 'document-a');
    navigation.updateCurrent(snapshotA);
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(
      mockRequestDetail('r1') as any,
    );

    const wrapper = await mountView('/workspaces/w1/reviews/pending/r1');
    await (wrapper.vm as any).openLinkedWorkbench();
    await flushPromises();

    expect(router.currentRoute.value.name).toBe('workspace-code');
    expect(router.currentRoute.value.query).toEqual({});
    expect(navigation.restoreCurrent()).toEqual(snapshotA);
  });

  it('refreshes the current review list when the global job signal changes', async () => {
    const wrapper = await mountView('/workspaces/w1/reviews/pending');
    vi.mocked(documentChangeApi.listDocumentChanges).mockClear();

    useBackgroundActivityStore().requestReviewRefresh('w1');
    await flushPromises();

    expect(documentChangeApi.listDocumentChanges).toHaveBeenCalledWith(
      'w1',
      expect.objectContaining({ status: 'PENDING' }),
    );
    expect(router.currentRoute.value.params.requestId).toBeFalsy();
    wrapper.unmount();
  });

  it('keeps the selected detail open when the global job signal refreshes counts', async () => {
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(
      mockRequestDetail('request-kept') as any,
    );
    const wrapper = await mountView('/workspaces/w1/reviews/pending/request-kept');
    vi.mocked(documentChangeApi.getDocumentChange).mockClear();

    useBackgroundActivityStore().requestReviewRefresh('w1');
    await flushPromises();

    expect(router.currentRoute.value.params.requestId).toBe('request-kept');
    expect((wrapper.vm as any).detail.request.id).toBe('request-kept');
    expect(documentChangeApi.getDocumentChange).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('refreshes the real pending count after a successful rejection', async () => {
    const pending = mockRequestDetail('request-rejected') as any;
    const rejected = {
      ...pending,
      request: {
        ...pending.request,
        status: 'REJECTED',
        rejectionReason: '内容需要修订',
      },
    };
    vi.mocked(documentChangeApi.getDocumentChange).mockResolvedValue(pending);
    vi.mocked(documentChangeApi.rejectDocumentChange).mockResolvedValue(rejected);
    const wrapper = await mountView('/workspaces/w1/reviews/pending/request-rejected');
    vi.mocked(documentChangeApi.getPendingDocumentChangeCount).mockClear();
    (wrapper.vm as any).rejectReason = '内容需要修订';

    await (wrapper.vm as any).rejectRequest();
    await flushPromises();

    expect(documentChangeApi.rejectDocumentChange).toHaveBeenCalledWith(
      'w1',
      'request-rejected',
      '内容需要修订',
    );
    expect(documentChangeApi.getPendingDocumentChangeCount).toHaveBeenCalledWith('w1');
    wrapper.unmount();
  });
});
