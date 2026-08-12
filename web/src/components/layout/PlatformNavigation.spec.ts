import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it } from 'vitest';

import PlatformNavigation from './PlatformNavigation.vue';
import { setAccessToken } from '@/api/http';

function wait(duration: number) {
  return new Promise(resolve => window.setTimeout(resolve, duration));
}

function testRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'landing', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/workspaces', name: 'workspaces', component: { template: '<div />' }, meta: { requiresAuth: true } },
    ],
  });
  router.beforeEach((to) => {
    if (to.meta.requiresAuth) return { name: 'login' };
    return true;
  });
  return router;
}

describe('PlatformNavigation', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    setAccessToken(null);
  });

  it('restores the login active state when unauthenticated navigation is redirected back', async () => {
    const router = testRouter();
    await router.push('/login');
    await router.isReady();

    const wrapper = mount(PlatformNavigation, {
      props: { active: 'account' },
      global: { plugins: [router] },
    });

    await wrapper.findAll('button')[1].trigger('click');
    expect(wrapper.findAll('button')[1].classes()).toContain('is-active');

    await wait(230);
    await wrapper.vm.$nextTick();

    expect(router.currentRoute.value.name).toBe('login');
    expect(wrapper.findAll('button')[2].classes()).toContain('is-active');
    expect(wrapper.findAll('button')[1].classes()).not.toContain('is-active');
  });
});
