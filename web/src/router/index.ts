import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import axios from 'axios';

import { csrfHeader, getAccessToken, setAccessToken } from '@/api/http';
import { useAuthStore } from '@/stores/auth';

export const appRoutes: RouteRecordRaw[] = [
    {
      path: '/',
      name: 'landing',
      component: () => import('@/views/LandingView.vue'),
    },
    {
      path: '/workspaces',
      name: 'workspaces',
      meta: {
        requiresAuth: true,
      },
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/workspaces/:workspaceId',
      name: 'workspace-root',
      meta: {
        requiresAuth: true,
        sidebarVariant: 'LINKED_WORKBENCH',
      },
      redirect: (to) => ({
        name: 'workspace-code',
        params: { workspaceId: to.params.workspaceId },
        query: to.query,
      }),
    },
    {
      path: '/workspaces/:workspaceId/code',
      name: 'workspace-code',
      meta: {
        requiresAuth: true,
        sidebarVariant: 'LINKED_WORKBENCH',
      },
      component: () => import('@/views/CodeWorkbenchView.vue'),
    },
    {
      path: '/workspaces/:workspaceId/reviews/:status(pending|applied|rejected|stale)',
      name: 'workspace-reviews',
      meta: {
        requiresAuth: true,
        sidebarVariant: 'LINKED_WORKBENCH',
      },
      component: () => import('@/views/ReviewWorkbenchView.vue'),
    },
    {
      path: '/workspaces/:workspaceId/reviews/:status(pending|applied|rejected|stale)/:requestId',
      name: 'workspace-review-detail',
      meta: {
        requiresAuth: true,
        sidebarVariant: 'LINKED_WORKBENCH',
      },
      component: () => import('@/views/ReviewWorkbenchView.vue'),
    },
    {
      path: '/w/:workspaceId/docs/:documentId',
      name: 'document-workbench',
      meta: {
        requiresAuth: true,
      },
      component: () => import('@/views/DocumentWorkbenchView.vue'),
    },
    {
      path: '/login',
      name: 'login',
      meta: {
        guestOnly: true,
      },
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/register',
      name: 'register',
      meta: {
        guestOnly: true,
      },
      component: () => import('@/views/RegisterView.vue'),
    },
];

const router = createRouter({
  history: createWebHistory(),
  routes: appRoutes,
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();

  if (!getAccessToken() && !authStore.initialized) {
    try {
      const { data } = await axios.post<{ accessToken: string }>(
        '/api/v1/auth/refresh',
        undefined,
        {
          withCredentials: true,
          headers: csrfHeader(),
        },
      );
      setAccessToken(data.accessToken);
    } catch {
      setAccessToken(null);
    }
  }

  if (getAccessToken() && !authStore.initialized) {
    await authStore.loadCurrentUser();
  }

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return {
      name: 'login',
      query: {
        redirect: to.fullPath,
      },
    };
  }

  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return { name: 'workspaces' };
  }

  return true;
});

export default router;
