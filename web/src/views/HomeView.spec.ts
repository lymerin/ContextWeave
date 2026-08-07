import ElementPlus from 'element-plus';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Workspace } from '@/api/workspace';
import WorkspaceRenameDialog from '@/components/workspace/WorkspaceRenameDialog.vue';

const mocks = vi.hoisted(() => ({
  listWorkspaces: vi.fn(),
  createWorkspace: vi.fn(),
  deleteWorkspace: vi.fn(),
  renameWorkspace: vi.fn(),
  listDocumentTree: vi.fn(),
  getPendingDocumentChangeCount: vi.fn(),
  registerGitRepository: vi.fn(),
  routerPush: vi.fn(),
  logout: vi.fn(),
  routeQuery: {},
}));

vi.mock('@/api/workspace', () => ({
  listWorkspaces: mocks.listWorkspaces,
  createWorkspace: mocks.createWorkspace,
  deleteWorkspace: mocks.deleteWorkspace,
  renameWorkspace: mocks.renameWorkspace,
}));

vi.mock('@/api/git', () => ({
  registerGitRepository: mocks.registerGitRepository,
}));

vi.mock('@/api/document', () => ({
  listDocumentTree: mocks.listDocumentTree,
}));

vi.mock('@/api/documentChange', () => ({
  getPendingDocumentChangeCount: mocks.getPendingDocumentChangeCount,
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({
    query: mocks.routeQuery,
  }),
  useRouter: () => ({
    push: mocks.routerPush,
  }),
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    currentUser: {
      username: 'admin',
      displayName: '管理员',
    },
    logout: mocks.logout,
  }),
}));

import HomeView from './HomeView.vue';

const workspace: Workspace = {
  id: 'workspace-1',
  name: '订单协作空间',
  currentUserRole: 'ADMIN',
  createdAt: '2026-07-23T08:00:00Z',
  updatedAt: '2026-07-23T08:00:00Z',
};

async function mountHome(): Promise<VueWrapper> {
  mocks.listWorkspaces.mockResolvedValue([workspace]);
  const wrapper = mount(HomeView, {
    attachTo: document.body,
    global: {
      plugins: [ElementPlus],
      stubs: {
        AppSidebar: true,
        NotificationCenter: true,
        WorkspaceCreateDialog: true,
      },
    },
  });
  await flushPromises();
  return wrapper;
}

async function openDeleteDialog(wrapper: VueWrapper) {
  await wrapper.get('.workspace-card-menu').trigger('click');
  await flushPromises();
  const deleteItem = Array.from(
    document.body.querySelectorAll<HTMLElement>(
      '.el-dropdown-menu__item',
    ),
  ).find(item => item.textContent?.includes('删除工作区'));
  expect(deleteItem?.textContent).toContain('删除工作区');
  deleteItem?.click();
  await flushPromises();
}

async function openRenameDialog(wrapper: VueWrapper) {
  await wrapper.get('.workspace-card-menu').trigger('click');
  await flushPromises();
  const renameItem = Array.from(
    document.body.querySelectorAll<HTMLElement>(
      '.el-dropdown-menu__item',
    ),
  ).find(item => item.textContent?.includes('重命名工作区'));
  expect(renameItem).toBeDefined();
  renameItem?.click();
  await flushPromises();
}

function confirmationInput() {
  return document.body.querySelector<HTMLInputElement>(
    '#workspace-delete-confirmation',
  );
}

function confirmButton() {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    .find(button => button.textContent?.includes('确认删除'));
}

describe('HomeView workspace deletion', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.routeQuery = {};
    mocks.deleteWorkspace.mockResolvedValue(undefined);
    mocks.listDocumentTree.mockResolvedValue([
      { id: 'document-1', title: '项目说明', children: [] },
    ]);
    mocks.getPendingDocumentChangeCount.mockResolvedValue(2);
    mocks.renameWorkspace.mockResolvedValue({
      ...workspace,
      name: '重命名后的工作区',
      updatedAt: '2026-07-23T09:30:00Z',
    });
  });

  it('renders the landing-style collaboration shell with real workspace stats', async () => {
    const wrapper = await mountHome();

    expect(wrapper.find('.workspace-selection-page').exists()).toBe(true);
    expect(wrapper.find('.app-shell').exists()).toBe(false);
    expect(wrapper.get('.workspace-selection-nav-item.is-active').text())
      .toContain('协作空间');
    expect(wrapper.text()).not.toContain('文档总览');
    expect(wrapper.get('.workspace-card-stats').text()).toContain('1 篇文档');
    expect(wrapper.get('.workspace-card-stats').text()).toContain('2 项待审批');
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('keeps card navigation but stops the menu click from navigating', async () => {
    const wrapper = await mountHome();

    await wrapper.get('.workspace-card').trigger('click');
    expect(mocks.routerPush).toHaveBeenCalledWith({
      name: 'workspace-code',
      params: { workspaceId: workspace.id },
    });

    mocks.routerPush.mockClear();
    await wrapper.get('.workspace-card-menu').trigger('click');
    await flushPromises();
    expect(mocks.routerPush).not.toHaveBeenCalled();
  });

  it('shows rename before delete and stops rename from navigating', async () => {
    const wrapper = await mountHome();
    await wrapper.get('.workspace-card-menu').trigger('click');
    await flushPromises();

    const items = Array.from(
      document.body.querySelectorAll<HTMLElement>(
        '.el-dropdown-menu__item',
      ),
    );
    expect(items
      .map(item => item.textContent?.trim())
      .filter(text => text === '重命名工作区' || text === '删除工作区'))
      .toEqual([
      '重命名工作区',
      '删除工作区',
    ]);

    items[0]?.click();
    await flushPromises();
    expect(mocks.routerPush).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('重命名工作区');
  });

  it('replaces only the renamed workspace with the server response', async () => {
    const wrapper = await mountHome();
    await openRenameDialog(wrapper);

    const input = document.body.querySelector<HTMLInputElement>(
      '#workspace-rename-name',
    )!;
    expect(input.value).toBe(workspace.name);
    input.value = '  重命名后的工作区  ';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();

    const saveButton = Array.from(
      document.body.querySelectorAll<HTMLButtonElement>('button'),
    ).find(button => button.textContent?.includes('保存'));
    saveButton?.click();
    await flushPromises();
    await new Promise(resolve => setTimeout(resolve, 350));
    await flushPromises();

    expect(mocks.renameWorkspace).toHaveBeenCalledTimes(1);
    expect(mocks.renameWorkspace).toHaveBeenCalledWith(
      workspace.id,
      { name: '重命名后的工作区' },
    );
    expect(wrapper.get('.workspace-card h2').text())
      .toBe('重命名后的工作区');
    expect(wrapper.findComponent(WorkspaceRenameDialog).props('modelValue'))
      .toBe(false);
  });

  it('keeps the original card and dialog when rename fails', async () => {
    mocks.renameWorkspace.mockRejectedValueOnce(
      new Error('重命名服务暂时不可用'),
    );
    const wrapper = await mountHome();
    await openRenameDialog(wrapper);

    const input = document.body.querySelector<HTMLInputElement>(
      '#workspace-rename-name',
    )!;
    input.value = '失败后的名称';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();

    const saveButton = Array.from(
      document.body.querySelectorAll<HTMLButtonElement>('button'),
    ).find(button => button.textContent?.includes('保存'));
    saveButton?.click();
    await flushPromises();

    expect(wrapper.get('.workspace-card h2').text()).toBe(workspace.name);
    expect(document.body.querySelector<HTMLInputElement>(
      '#workspace-rename-name',
    )?.value).toBe('失败后的名称');
  });

  it('deletes only after exact confirmation and updates the count', async () => {
    const wrapper = await mountHome();
    await openDeleteDialog(wrapper);

    expect(document.body.textContent).toContain(workspace.name);
    expect(confirmButton()?.disabled).toBe(true);

    confirmationInput()!.value = workspace.name;
    confirmationInput()!.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    expect(confirmButton()?.disabled).toBe(false);

    confirmButton()?.click();
    await flushPromises();

    expect(mocks.deleteWorkspace).toHaveBeenCalledTimes(1);
    expect(mocks.deleteWorkspace).toHaveBeenCalledWith(workspace.id);
    expect(wrapper.find('.workspace-card').exists()).toBe(false);
    expect(wrapper.text()).toContain('还没有工作区');
  });

  it('keeps the card and dialog when deletion fails', async () => {
    mocks.deleteWorkspace.mockRejectedValueOnce(
      new Error('删除服务暂时不可用'),
    );
    const wrapper = await mountHome();
    await openDeleteDialog(wrapper);

    confirmationInput()!.value = workspace.name;
    confirmationInput()!.dispatchEvent(new Event('input', { bubbles: true }));
    await flushPromises();
    confirmButton()?.click();
    await flushPromises();

    expect(wrapper.find('.workspace-card').exists()).toBe(true);
    expect(wrapper.get('.workspace-card h2').text()).toBe(workspace.name);
    expect(confirmationInput()).not.toBeNull();
  });
});
