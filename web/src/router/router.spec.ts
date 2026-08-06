import { createMemoryHistory, createRouter } from 'vue-router';
import { describe, expect, it } from 'vitest';

import { appRoutes } from './index';

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: appRoutes.map(route => route.component
      ? { ...route, component: { template: '<div />' } }
      : route),
  });
}

function waitForNavigation(router: ReturnType<typeof createTestRouter>) {
  return new Promise<void>((resolve) => {
    const remove = router.afterEach(() => {
      remove();
      resolve();
    });
  });
}

describe('workspace routes', () => {
  it('keeps the root route public and renders the landing page before authentication', async () => {
    const router = createTestRouter();
    await router.push('/');
    expect(router.currentRoute.value.name).toBe('landing');
    expect(router.currentRoute.value.meta.requiresAuth).not.toBe(true);
    expect(router.currentRoute.value.fullPath).toBe('/');
  });

  it('redirects the workspace root to the linked workbench and preserves query context', async () => {
    const router = createTestRouter();
    await router.push('/workspaces/w1?repositoryId=r1&documentId=d1');
    expect(router.currentRoute.value.name).toBe('workspace-code');
    expect(router.currentRoute.value.fullPath).toBe('/workspaces/w1/code?repositoryId=r1&documentId=d1');
    expect(router.currentRoute.value.matched.some(
      record => record.meta.sidebarVariant === 'LINKED_WORKBENCH',
    )).toBe(true);
  });

  it('uses one history entry for the redirect and does not loop through the old workspace view', async () => {
    const router = createTestRouter();
    await router.push('/workspaces');
    await router.push('/workspaces/w1');
    expect(router.currentRoute.value.fullPath).toBe('/workspaces/w1/code');

    const navigated = waitForNavigation(router);
    router.back();
    await navigated;
    expect(router.currentRoute.value.fullPath).toBe('/workspaces');
  });

  it('keeps non-workspace routes on the default shell contract', async () => {
    const router = createTestRouter();
    await router.push('/workspaces');
    expect(router.currentRoute.value.matched.some(
      record => record.meta.sidebarVariant === 'LINKED_WORKBENCH',
    )).toBe(false);
  });

  it('resolves the direct code URL without another redirect', async () => {
    const router = createTestRouter();
    let completedNavigations = 0;
    router.afterEach(() => { completedNavigations += 1; });
    await router.push('/workspaces/w1/code');
    expect(router.currentRoute.value.name).toBe('workspace-code');
    expect(completedNavigations).toBe(1);
  });

  it('keeps review list and detail routes inside the linked workspace shell', async () => {
    const router = createTestRouter();
    await router.push('/workspaces/w1/reviews/pending');
    expect(router.currentRoute.value.name).toBe('workspace-reviews');
    expect(router.currentRoute.value.matched.some(
      record => record.meta.sidebarVariant === 'LINKED_WORKBENCH',
    )).toBe(true);

    await router.push('/workspaces/w1/reviews/stale/r1?operationId=o1');
    expect(router.currentRoute.value.name).toBe('workspace-review-detail');
    expect(router.currentRoute.value.query.operationId).toBe('o1');
  });

  it('does not accept arbitrary review status segments', async () => {
    const router = createTestRouter();
    await router.push('/workspaces/w1/reviews/unknown');
    expect(router.currentRoute.value.matched).toHaveLength(0);
  });
});
