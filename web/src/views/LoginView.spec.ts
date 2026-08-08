import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  routerPush: vi.fn(),
  login: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: mocks.routerPush }),
}));

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    login: mocks.login,
  }),
}));

import LoginView from './LoginView.vue';

describe('LoginView', () => {
  beforeEach(() => {
    mocks.routerPush.mockReset();
    mocks.login.mockReset();
  });

  it('renders the platform navigation with login active', () => {
    const wrapper = mount(LoginView, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    });

    const items = wrapper.findAll('.platform-navigation button');
    expect(items.map(item => item.text().trim())).toEqual([
      '首页',
      '协作空间',
      '登录',
      '设置',
    ]);
    expect(items[2].classes()).toContain('is-active');
    expect(items[2].attributes('aria-current')).toBe('page');
  });

  it('sends protected navigation through the existing guarded routes', async () => {
    const wrapper = mount(LoginView, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    });
    const items = wrapper.findAll('.platform-navigation button');

    await items[1].trigger('click');
    await new Promise(resolve => setTimeout(resolve, 220));

    expect(mocks.routerPush).toHaveBeenNthCalledWith(1, '/workspaces');
    expect(mocks.routerPush).toHaveBeenCalledTimes(1);
  });

  it('keeps settings as a visible placeholder in this login-only slice', async () => {
    const wrapper = mount(LoginView, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    });
    const settings = wrapper.findAll('.platform-navigation button')[3];

    expect(settings.attributes()).toHaveProperty('disabled');
    await settings.trigger('click');
    expect(mocks.routerPush).not.toHaveBeenCalled();
  });
});
