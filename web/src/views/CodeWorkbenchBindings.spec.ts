import { flushPromises, shallowMount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';

import {
  createLinkedWorkbenchSnapshot,
  resetLinkedWorkbenchNavigationMemoryForTests,
  useLinkedWorkbenchNavigation,
} from '@/composables/useLinkedWorkbenchNavigation';
import CodeWorkbenchView from './CodeWorkbenchView.vue';

const mocks = vi.hoisted(() => ({
  getSource: vi.fn(),
  queryBindings: vi.fn(),
  listCodeBindingsContext: vi.fn(),
  resolveBlockFileContext: vi.fn(),
  listFiles: vi.fn(),
  replace: vi.fn(),
  routeQuery: { repositoryId: 'repository-1' } as Record<string, string>,
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { workspaceId: 'workspace-1' },
    query: mocks.routeQuery,
  }),
  useRouter: () => ({ replace: mocks.replace, push: vi.fn() }),
}));

vi.mock('@/api/agent', () => ({
  createAgentJob: vi.fn(),
  getAgentJob: vi.fn(),
  listAgentJobUnits: vi.fn(),
  readableAgentError: (_error: unknown, fallback: string) => fallback,
}));

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
    lastSyncedCommit: 'revision-1',
    syncStatus: 'READY',
  }]),
  listGitRepositoryFiles: mocks.listFiles,
  listGitChanges: vi.fn().mockResolvedValue([]),
  getGitRepositorySource: mocks.getSource,
  queryCodeBindings: mocks.queryBindings,
  listCodeBindingsContext: mocks.listCodeBindingsContext,
  resolveBlockFileContext: mocks.resolveBlockFileContext,
  syncGitRepository: vi.fn(),
}));

vi.mock('@/api/document', () => ({
  listDocumentTree: vi.fn().mockResolvedValue([
    documentNode('document-a', 'Document A'),
    documentNode('document-c', 'Document C'),
  ]),
  getDocument: vi.fn().mockImplementation((id: string) => Promise.resolve({
    id,
    workspaceId: 'workspace-1',
    title: id === 'document-a' ? 'Document A' : 'Document C',
    documentType: 'BACKEND',
    reviewStatus: 'DRAFT',
  })),
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

describe('CodeWorkbenchView formal bindings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    resetLinkedWorkbenchNavigationMemoryForTests();
    Object.keys(mocks.routeQuery).forEach(key => delete mocks.routeQuery[key]);
    mocks.routeQuery.repositoryId = 'repository-1';
    mocks.listFiles.mockResolvedValue([
      file('src/A.java'),
      file('src/B.java'),
      file('src/C.java'),
    ]);
    mocks.getSource.mockImplementation(
      (_workspaceId: string, _repositoryId: string, path: string) =>
        Promise.resolve(source(path)),
    );
    mocks.queryBindings.mockImplementation(
      (_workspaceId: string, _repositoryId: string, _revision: string, path: string) =>
        Promise.resolve(bindingResult(path)),
    );
    mocks.listCodeBindingsContext.mockResolvedValue([]);
  });

  it('loads A, empty B, C and A again without reusing another file bindings', async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/A.java',
      documentIds: 'document-a',
    });

    await selectFile(wrapper, 'src/B.java');
    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/B.java',
      documentIds: '',
    });

    await selectFile(wrapper, 'src/C.java');
    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/C.java',
      documentIds: 'document-c',
    });

    await selectFile(wrapper, 'src/A.java');
    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/A.java',
      documentIds: 'document-a',
    });
    expect(mocks.queryBindings).toHaveBeenLastCalledWith(
      'workspace-1',
      'repository-1',
      'revision-1',
      'src/A.java',
    );
    wrapper.unmount();
  });

  it('ignores a late A response after B becomes the selected file', async () => {
    const lateA = deferred<ReturnType<typeof source>>();
    mocks.getSource.mockImplementation(
      (_workspaceId: string, _repositoryId: string, path: string) =>
        path === 'src/A.java' ? lateA.promise : Promise.resolve(source(path)),
    );
    const wrapper = mountView();
    await flushPromises();

    await selectFile(wrapper, 'src/B.java');
    lateA.resolve(source('src/A.java'));
    await flushPromises();

    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/B.java',
      documentIds: '',
    });
    expect(mocks.queryBindings).not.toHaveBeenCalledWith(
      'workspace-1',
      'repository-1',
      'revision-1',
      'src/A.java',
    );
    wrapper.unmount();
  });

  it('restores a saved file and rejects a stale saved document not present in formal bindings', async () => {
    const navigation = testNavigation();
    navigation.updateCurrent(createLinkedWorkbenchSnapshot(
      scope,
      'src/C.java',
      'document-a',
    ));

    const wrapper = mountView();
    await flushPromises();

    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/C.java',
      documentIds: 'document-c',
    });
    expect(navigation.restoreCurrent()).toEqual(createLinkedWorkbenchSnapshot(
      scope,
      'src/C.java',
      'document-c',
      'binding-src/C.java',
      null,
    ));
    wrapper.unmount();
  });

  it('degrades a deleted saved file to the first readable file and persists the repaired target', async () => {
    const navigation = testNavigation();
    navigation.updateCurrent(createLinkedWorkbenchSnapshot(
      scope,
      'src/Deleted.java',
      'document-a',
    ));

    const wrapper = mountView();
    await flushPromises();

    expect(snapshot(wrapper).sourcePath).toBe('src/A.java');
    expect(navigation.restoreCurrent()?.filePath).toBe('src/A.java');
    wrapper.unmount();
  });

  it('uses reading history buttons without reusing stale file bindings', async () => {
    const wrapper = mountView();
    await flushPromises();
    await selectFile(wrapper, 'src/C.java');
    expect(snapshot(wrapper).sourcePath).toBe('src/C.java');

    await wrapper.get('[data-test="linked-history-back"]').trigger('click');
    await flushPromises();
    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/A.java',
      documentIds: 'document-a',
    });

    await wrapper.get('[data-test="linked-history-forward"]').trigger('click');
    await flushPromises();
    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/C.java',
      documentIds: 'document-c',
    });
    wrapper.unmount();
  });

  it('restores the saved current before considering the repository default file', async () => {
    mocks.listFiles.mockResolvedValue([
      file('.mvn/wrapper/maven-wrapper.properties'),
      file('agent-service/app/context/budget.py'),
    ]);
    const navigation = testNavigation();
    navigation.updateCurrent(createLinkedWorkbenchSnapshot(
      scope,
      'agent-service/app/context/budget.py',
      'document-a',
    ));

    const wrapper = mountView();
    await flushPromises();

    expect(snapshot(wrapper).sourcePath).toBe('agent-service/app/context/budget.py');
    expect(mocks.getSource).toHaveBeenCalledTimes(1);
    expect(mocks.getSource).not.toHaveBeenCalledWith(
      'workspace-1',
      'repository-1',
      '.mvn/wrapper/maven-wrapper.properties',
    );
    wrapper.unmount();
  });

  it('gives an explicit result target priority over an older saved current', async () => {
    const navigation = testNavigation();
    navigation.updateCurrent(createLinkedWorkbenchSnapshot(
      scope,
      'src/A.java',
      'document-a',
    ));
    mocks.routeQuery.filePath = 'src/C.java';
    mocks.routeQuery.documentId = 'document-c';

    const wrapper = mountView();
    await flushPromises();

    expect(snapshot(wrapper)).toEqual({
      sourcePath: 'src/C.java',
      documentIds: 'document-c',
    });
    expect(mocks.getSource).not.toHaveBeenCalledWith(
      'workspace-1',
      'repository-1',
      'src/A.java',
    );
    wrapper.unmount();
  });

  it('restores current without adding history or clearing the existing forward stack', async () => {
    const navigation = testNavigation();
    const snapshotA = createLinkedWorkbenchSnapshot(scope, 'src/A.java', 'document-a');
    const snapshotC = createLinkedWorkbenchSnapshot(scope, 'src/C.java', 'document-c');
    navigation.updateCurrent(snapshotA);
    navigation.navigateTo(snapshotC);
    navigation.goBack();
    const before = structuredClone(navigation.state.value);

    const wrapper = mountView();
    await flushPromises();

    expect(navigation.state.value.current).toMatchObject({
      ...before.current,
      bindingId: 'binding-src/A.java',
    });
    expect(navigation.state.value.backStack).toEqual(before.backStack);
    expect(navigation.state.value.forwardStack).toEqual(before.forwardStack);
    expect(navigation.canGoForward.value).toBe(true);
    expect(mocks.getSource).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('repairs an invalid saved file without selecting hidden infrastructure first', async () => {
    mocks.listFiles.mockResolvedValue([
      file('.mvn/wrapper/maven-wrapper.properties'),
      file('agent-service/app/context/budget.py'),
    ]);
    const navigation = testNavigation();
    navigation.updateCurrent(createLinkedWorkbenchSnapshot(
      scope,
      'deleted/path.py',
      'document-a',
    ));

    const wrapper = mountView();
    await flushPromises();

    expect(snapshot(wrapper).sourcePath).toBe('agent-service/app/context/budget.py');
    expect(navigation.restoreCurrent()?.filePath).toBe('agent-service/app/context/budget.py');
    expect(mocks.getSource).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('keeps two formal bindings for one file and switches the active binding', async () => {
    mocks.queryBindings.mockResolvedValue({
      ...bindingResult('src/A.java'),
      bindings: [
        preciseBinding('binding-a', 'src/A.java', 'block-a', 2, 3),
        preciseBinding('binding-overview', 'src/A.java', 'block-overview', 2, 3),
      ],
    });
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.get('[data-test="next-binding"]').text()).toBe('下一关联');
    await wrapper.get('[data-test="next-binding"]').trigger('click');
    await flushPromises();
    expect(testNavigation().restoreCurrent()?.bindingId).toBe('binding-overview');
    expect(testNavigation().state.value.backStack).toEqual([]);
    wrapper.unmount();
  });

  it('queries block file context when a document Block is selected', async () => {
    mocks.queryBindings.mockResolvedValue({
      ...bindingResult('src/A.java'),
      bindings: [preciseBinding('binding-a', 'src/A.java', 'block-a', 2, 3)],
    });
    mocks.resolveBlockFileContext.mockResolvedValue({
      workspaceId: 'workspace-1',
      repositoryId: 'repository-1',
      documentId: 'document-a',
      blockId: 'block-a',
      filePath: 'src/A.java',
      preferredBindingId: 'binding-a',
      bindings: [preciseBinding('binding-a', 'src/A.java', 'block-a', 2, 3)],
    });
    const wrapper = mountView();
    await flushPromises();

    await wrapper.get('[data-test="select-block"]').trigger('click');
    await flushPromises();

    expect(mocks.resolveBlockFileContext).toHaveBeenCalledWith('document-a', {
      blockId: 'block-a',
      revision: 'revision-1',
      includeLegacy: true,
    });
    expect(testNavigation().restoreCurrent()).toMatchObject({
      bindingId: 'binding-a',
      blockId: 'block-a',
    });
    wrapper.unmount();
  });
});

function mountView() {
  return shallowMount(CodeWorkbenchView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        AppSidebar: { template: '<aside><slot name="workspace-panel" /></aside>' },
        LinkedRepositoryContext: {
          name: 'LinkedRepositoryContext',
          props: ['documents'],
          emits: ['select-file'],
          template: `
            <div>
              <span data-test="document-ids">{{ documents.map(item => item.id).join(',') }}</span>
              <button data-test="select-a" @click="$emit('select-file', 'src/A.java')" />
              <button data-test="select-b" @click="$emit('select-file', 'src/B.java')" />
              <button data-test="select-c" @click="$emit('select-file', 'src/C.java')" />
            </div>
          `,
        },
        LinkedWorkbenchShell: {
          props: ['sourcePath', 'document'],
          emits: ['select-block', 'blocks-loaded'],
          methods: {
            focusAnchor: vi.fn(),
            focusBlock: vi.fn(),
            clearBlockFocus: vi.fn(),
            confirmDocumentLeave: vi.fn().mockResolvedValue(true),
          },
          watch: {
            document: {
              immediate: true,
              handler(value: { id?: string } | null) {
                if (!value?.id) return;
                this.$emit('blocks-loaded', [
                  block('block-a', value.id),
                  block('block-b', value.id),
                  block('block-overview', value.id),
                ]);
              },
            },
          },
          template: '<section><span data-test="source-path">{{ sourcePath }}</span><button data-test="select-block" @click="$emit(\'select-block\', \'block-a\')" /><slot name="header-actions" /></section>',
        },
        NotificationCenter: true,
        ElAlert: true,
        ElSkeleton: true,
        ElDrawer: true,
        ElButton: {
          props: ['disabled'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        ElButtonGroup: { template: '<div><slot /></div>' },
      },
    },
  });
}

async function selectFile(
  wrapper: ReturnType<typeof mountView>,
  path: 'src/A.java' | 'src/B.java' | 'src/C.java',
) {
  const key = path.slice(4, 5).toLowerCase();
  await wrapper.find(`[data-test="select-${key}"]`).trigger('click');
  await flushPromises();
}

function snapshot(wrapper: ReturnType<typeof mountView>) {
  return {
    sourcePath: wrapper.find('[data-test="source-path"]').text(),
    documentIds: wrapper.find('[data-test="document-ids"]').text(),
  };
}

function bindingResult(path: string) {
  const documentId = path === 'src/A.java'
    ? 'document-a'
    : path === 'src/C.java' ? 'document-c'
      : path === 'agent-service/app/context/budget.py' ? 'document-a' : null;
  return {
    workspaceId: 'workspace-1',
    repositoryId: 'repository-1',
    filePath: path,
    fileHasBindings: documentId !== null,
    bindings: documentId ? [{
      bindingId: `binding-${path}`,
      workspaceId: 'workspace-1',
      repositoryId: 'repository-1',
      revision: 'revision-1',
      anchorKind: 'FILE',
      symbolKey: null,
      startLine: null,
      endLine: null,
      documentId,
      blockId: null,
      pathPattern: path,
      documentTitle: documentId === 'document-a' ? 'Document A' : 'Document C',
      matchedFilePath: path,
      blockExists: false,
    }] : [],
    truncated: false,
    omittedBindingCount: 0,
  };
}

function preciseBinding(
  bindingId: string,
  path: string,
  blockId: string,
  startLine: number,
  endLine: number,
) {
  return {
    bindingId,
    workspaceId: 'workspace-1',
    repositoryId: 'repository-1',
    revision: 'revision-1',
    anchorKind: 'SYMBOL',
    symbolKey: `JAVA:${path}:run`,
    startLine,
    endLine,
    documentId: 'document-a',
    blockId,
    targetKey: `BLOCK:${blockId}`,
    pathPattern: path,
    documentTitle: 'Document A',
    matchedFilePath: path,
    blockExists: true,
  };
}

function file(path: string) {
  return {
    id: `file-${path}`,
    path,
    blobSha: `blob-${path}`,
    sizeBytes: 10,
    language: 'Java',
    readable: true,
  };
}

function block(id: string, documentId: string) {
  return {
    id,
    documentId,
    type: 'PARAGRAPH' as const,
    content: {
      text: id,
      schemaVersion: 1,
      document: {
        type: 'doc',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: id }] }],
      },
    },
    sortOrder: id === 'block-a' ? 0 : 1,
    version: 1,
    createdBy: 'user-a',
    createdAt: '2026-07-29T00:00:00Z',
    updatedAt: '2026-07-29T00:00:00Z',
  };
}

function source(path: string) {
  return {
    repositoryId: 'repository-1',
    commitSha: 'revision-1',
    path,
    blobSha: `blob-${path}`,
    sizeBytes: 10,
    language: 'Java',
    readable: true,
    content: `class ${path.slice(4, 5)} {}`,
    symbols: [],
  };
}

function documentNode(id: string, title: string) {
  return {
    id,
    workspaceId: 'workspace-1',
    parentId: null,
    title,
    depth: 0,
    reviewStatus: 'DRAFT',
    documentType: 'BACKEND',
    children: [],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => { resolve = resolver; });
  return { promise, resolve };
}

const scope = {
  workspaceId: 'workspace-1',
  repositoryId: 'repository-1',
  revision: 'revision-1',
};

function testNavigation() {
  return useLinkedWorkbenchNavigation(ref(scope));
}
