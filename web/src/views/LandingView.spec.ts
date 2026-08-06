import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  isAuthenticated: false,
  listWorkspaces: vi.fn(),
  listDocumentTree: vi.fn(),
  getPendingDocumentChangeCount: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    get isAuthenticated() {
      return mocks.isAuthenticated;
    },
  }),
}));

vi.mock('@/api/workspace', () => ({
  listWorkspaces: mocks.listWorkspaces,
}));

vi.mock('@/api/document', () => ({
  listDocumentTree: mocks.listDocumentTree,
}));

vi.mock('@/api/documentChange', () => ({
  getPendingDocumentChangeCount: mocks.getPendingDocumentChangeCount,
}));

import LandingView from './LandingView.vue';

describe('LandingView protected entry points', () => {
  beforeEach(() => {
    mocks.routerPush.mockReset();
    mocks.isAuthenticated = false;
    mocks.listWorkspaces.mockReset();
    mocks.listDocumentTree.mockReset();
    mocks.getPendingDocumentChangeCount.mockReset();
    mocks.listWorkspaces.mockResolvedValue([]);
    mocks.listDocumentTree.mockResolvedValue([]);
    mocks.getPendingDocumentChangeCount.mockResolvedValue(0);
  });

  it('renders the approved public overview without a hero index', () => {
    const wrapper = mount(LandingView);
    expect(wrapper.text()).toContain('先沉淀团队知识');
    expect(wrapper.find('.landing-hero-panel').text()).not.toContain('01');
    expect(wrapper.findAll('.landing-metric-icon')).toHaveLength(3);
    expect(wrapper.text()).not.toContain('新建文档');
  });

  it('returns a guest to the homepage after login from public navigation', async () => {
    const wrapper = mount(LandingView);
    const documents = wrapper.findAll('.landing-nav-item')
      .find(button => button.text().includes('文档'))!;

    await documents.trigger('click');

    expect(mocks.routerPush).toHaveBeenCalledWith({
      name: 'login',
      query: { redirect: '/' },
    });
  });

  it('sends the avatar to login for guests', async () => {
    const wrapper = mount(LandingView);
    await wrapper.get('.landing-avatar').trigger('click');
    expect(mocks.routerPush).toHaveBeenCalledWith({
      name: 'login',
      query: { redirect: '/' },
    });
  });

  it('opens the protected target directly for authenticated users', async () => {
    mocks.isAuthenticated = true;
    const wrapper = mount(LandingView);
    await wrapper.get('.landing-primary-action').trigger('click');
    expect(mocks.routerPush).toHaveBeenCalledWith('/workspaces?action=create');
  });

  it('renders real recent workspaces and opens a workspace by id', async () => {
    mocks.isAuthenticated = true;
    mocks.listWorkspaces.mockResolvedValue([
      {
        id: 'workspace-1',
        name: '真实工作区一',
        currentUserRole: 'ADMIN',
        createdAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-06T08:30:00Z',
      },
      {
        id: 'workspace-2',
        name: '真实工作区二',
        currentUserRole: 'MEMBER',
        createdAt: '2026-08-02T00:00:00Z',
        updatedAt: '2026-08-05T08:30:00Z',
      },
    ]);
    mocks.listDocumentTree
      .mockResolvedValueOnce([{ id: 'd1', title: '文档', children: [] }])
      .mockResolvedValueOnce([]);
    mocks.getPendingDocumentChangeCount
      .mockResolvedValueOnce(2)
      .mockResolvedValueOnce(0);

    const wrapper = mount(LandingView);
    await flushPromises();

    const rows = wrapper.findAll('.landing-workspace-item');
    expect(rows).toHaveLength(2);
    expect(wrapper.find('.landing-workspace-mark').exists()).toBe(false);
    expect(rows[0].text()).toContain('真实工作区一');
    expect(rows[0].text()).toContain('1 个文档 · 2 个待审批');
    expect(wrapper.findAll('.landing-metric-copy strong')[2].text()).toBe('2');

    await rows[0].get('button').trigger('click');
    expect(mocks.routerPush).toHaveBeenCalledWith('/workspaces/workspace-1');
  });

  it('shows the matching create action when there are no workspaces', async () => {
    mocks.isAuthenticated = true;
    const wrapper = mount(LandingView);
    await flushPromises();

    expect(wrapper.text()).toContain('还没有工作区');
    const emptyCreate = wrapper.get('.landing-empty-create');
    await emptyCreate.trigger('click');
    expect(mocks.routerPush).toHaveBeenCalledWith('/workspaces?action=create');
  });

  it('routes view all to the collaboration workspace section', async () => {
    mocks.isAuthenticated = true;
    const wrapper = mount(LandingView);
    await flushPromises();

    await wrapper.get('.landing-text-action').trigger('click');
    expect(mocks.routerPush).toHaveBeenCalledWith(
      '/workspaces?section=collaboration',
    );
  });
});
