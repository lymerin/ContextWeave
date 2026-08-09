<template>
  <MorphingNavigation />
  <RouterView />
</template>

<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue';
import { useRoute } from 'vue-router';

import MorphingNavigation from '@/components/layout/MorphingNavigation.vue';
import { useAuthStore } from '@/stores/auth';
import { useBackgroundActivityStore } from '@/stores/backgroundActivity';

const route = useRoute();
const authStore = useAuthStore();
const backgroundActivity = useBackgroundActivityStore();

watch(
  () => authStore.currentUser?.userId ?? null,
  (userId) => {
    if (userId) void backgroundActivity.startPolling(userId);
    else backgroundActivity.stopPolling();
  },
  { immediate: true },
);

watch(
  () => typeof route.params.workspaceId === 'string'
    ? route.params.workspaceId
    : null,
  workspaceId => backgroundActivity.setActiveWorkspace(workspaceId),
  { immediate: true },
);

onBeforeUnmount(() => backgroundActivity.stopPolling(false));
</script>
