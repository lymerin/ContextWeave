<template>
  <nav
    class="morph-navigation"
    :data-mode="displayMode"
    :data-phase="phase"
    :style="{
      '--active-slot': visualSlot,
      '--active-order': visualOrder,
    }"
    :aria-label="displayMode === 'platform' ? '平台导航' : '当前工作区导航'"
    :aria-busy="transitioning"
  >
    <span class="morph-navigation-window">
      <span class="morph-navigation-layer">
        <span class="morph-navigation-shell" aria-hidden="true" />
        <span class="morph-navigation-indicator" aria-hidden="true" />

        <button
          v-for="(item, order) in items"
          :key="item.key"
          type="button"
          :style="{ '--slot': item.slot, '--order': order }"
          :class="{ 'is-active': visualSlot === item.slot }"
          :disabled="transitioning || item.disabled"
          :aria-current="visualSlot === item.slot ? 'page' : undefined"
          :title="item.title"
          @click="navigate(item)"
        >
          {{ item.label }}
          <span v-if="visualSlot === item.slot" aria-hidden="true" />
        </button>
      </span>
    </span>
  </nav>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  useRoute,
  useRouter,
  type RouteLocationNormalized,
  type RouteLocationNormalizedLoaded,
} from 'vue-router';

import { listDocumentTree, type DocumentTreeNode } from '@/api/document';
import { useAuthStore } from '@/stores/auth';

type NavigationMode = 'platform' | 'workspace';
type TransitionPhase = 'open' | 'closing' | 'closed' | 'opening';
type NavigationLocation = RouteLocationNormalized | RouteLocationNormalizedLoaded;

type NavigationItem = {
  key: string;
  label: string;
  slot: number;
  target: string;
  targetMode: NavigationMode;
  disabled?: boolean;
  title?: string;
};

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const displayMode = ref<NavigationMode>(modeForRoute(route));
const phase = ref<TransitionPhase>('open');
const transitioning = ref(false);
const visualSlot = ref(activeSlotForRoute(route));
const firstDocumentId = ref('');
const documentsLoading = ref(false);
let removeNavigationGuard: (() => void) | undefined;
let transitionSequence = 0;

const workspaceId = computed(() => String(route.params.workspaceId || ''));
const items = computed<NavigationItem[]>(() => {
  if (displayMode.value === 'platform') {
    return [
      { key: 'home', label: '首页', slot: 0, target: '/', targetMode: 'platform' },
      { key: 'spaces', label: '协作空间', slot: 1, target: '/workspaces', targetMode: 'platform' },
      {
        key: 'account',
        label: authStore.isAuthenticated ? '账户' : '登录',
        slot: 2,
        target: authStore.isAuthenticated ? '/workspaces?panel=profile' : '/login',
        targetMode: 'platform',
      },
      {
        key: 'settings',
        label: '设置',
        slot: 3,
        target: '',
        targetMode: 'platform',
        disabled: true,
        title: '接入设置将在后端接口就绪后开放',
      },
    ];
  }

  return [
    { key: 'spaces', label: '协作空间', slot: 1, target: '/workspaces', targetMode: 'platform' },
    { key: 'linked', label: '关联工作台', slot: 2, target: `/workspaces/${workspaceId.value}/code`, targetMode: 'workspace' },
    { key: 'reviews', label: '审阅中心', slot: 3, target: `/workspaces/${workspaceId.value}/reviews/pending`, targetMode: 'workspace' },
    {
      key: 'documents',
      label: '团队文档',
      slot: 4,
      target: firstDocumentId.value ? `/w/${workspaceId.value}/docs/${firstDocumentId.value}` : '',
      targetMode: 'workspace',
      disabled: documentsLoading.value || !firstDocumentId.value,
      title: !documentsLoading.value && !firstDocumentId.value ? '当前工作区还没有文档' : undefined,
    },
  ];
});
const visualOrder = computed(() => items.value.findIndex(item => item.slot === visualSlot.value));

function modeForRoute(location: NavigationLocation): NavigationMode {
  return location.name === 'workspace-code'
    || location.name === 'workspace-reviews'
    || location.name === 'workspace-review-detail'
    || location.name === 'document-workbench'
    ? 'workspace'
    : 'platform';
}

function activeSlotForRoute(location: NavigationLocation): number {
  if (location.name === 'workspace-code') return 2;
  if (location.name === 'workspace-reviews' || location.name === 'workspace-review-detail') return 3;
  if (location.name === 'document-workbench') return 4;
  if (location.name === 'workspaces') return 1;
  if (location.name === 'login' || location.name === 'register') return 2;
  return 0;
}

function firstId(nodes: DocumentTreeNode[]): string {
  for (const node of nodes) return node.id || firstId(node.children || []);
  return '';
}

async function loadFirstDocument() {
  if (!workspaceId.value) {
    firstDocumentId.value = '';
    return;
  }
  if (route.name === 'document-workbench' && route.params.documentId) {
    firstDocumentId.value = String(route.params.documentId);
    return;
  }
  documentsLoading.value = true;
  try {
    firstDocumentId.value = firstId(await listDocumentTree(workspaceId.value));
  } catch {
    firstDocumentId.value = '';
  } finally {
    documentsLoading.value = false;
  }
}

function wait(duration: number) {
  return new Promise(resolve => window.setTimeout(resolve, duration));
}

function reducedMotion() {
  return typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

async function navigate(item: NavigationItem) {
  if (!item.target || item.disabled || transitioning.value) return;
  if (item.targetMode === displayMode.value) {
    visualSlot.value = item.slot;
    await wait(reducedMotion() ? 0 : 190);
  }
  await router.push(item.target);
}

async function closeNavigation(nextMode: NavigationMode) {
  const sequence = ++transitionSequence;
  transitioning.value = true;
  visualSlot.value = 1;
  phase.value = 'closing';
  await wait(170);
  if (sequence !== transitionSequence) return;
  phase.value = 'closed';
  displayMode.value = nextMode;
  await nextTick();
}

async function openNavigation(location: NavigationLocation) {
  const sequence = transitionSequence;
  await nextTick();
  await new Promise<void>(resolve => window.requestAnimationFrame(() => resolve()));
  if (sequence !== transitionSequence) return;
  phase.value = 'opening';
  await wait(70);
  visualSlot.value = activeSlotForRoute(location);
  await wait(230);
  if (sequence !== transitionSequence) return;
  phase.value = 'open';
  transitioning.value = false;
}

onMounted(() => {
  removeNavigationGuard = router.beforeEach(async (to, from) => {
    const nextMode = modeForRoute(to);
    if (nextMode === modeForRoute(from) || reducedMotion()) {
      if (reducedMotion()) {
        displayMode.value = nextMode;
        visualSlot.value = activeSlotForRoute(to);
      }
      return true;
    }
    await closeNavigation(nextMode);
    return true;
  });
  void loadFirstDocument();
});

watch(
  () => route.fullPath,
  () => {
    const nextMode = modeForRoute(route);
    if (displayMode.value !== nextMode) displayMode.value = nextMode;
    if (transitioning.value && !reducedMotion()) void openNavigation(route);
    else visualSlot.value = activeSlotForRoute(route);
  },
);

watch(workspaceId, () => { void loadFirstDocument(); });

onBeforeUnmount(() => {
  transitionSequence += 1;
  removeNavigationGuard?.();
});
</script>

<style scoped>
.morph-navigation {
  --item-width: 112px;
  --shell-width: 460px;
  --stage-width: 572px;
  --navigation-ink: #090c0e;
  --navigation-paper: #f0f2f3;
  --navigation-muted: #78838c;
  --navigation-signal: #18c9ef;
  --window-left: 0px;
  --window-width: var(--shell-width);
  position: absolute;
  z-index: 80;
  top: 10px;
  left: calc(50% - 230px);
  width: var(--stage-width);
  height: 52px;
  overflow: hidden;
  isolation: isolate;
  font-family: "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", system-ui, sans-serif;
}

.morph-navigation[data-mode="workspace"] {
  --window-left: var(--item-width);
}

.morph-navigation[data-phase="closing"],
.morph-navigation[data-phase="closed"] {
  --window-left: var(--item-width);
  --window-width: 124px;
}

.morph-navigation-window {
  position: absolute;
  z-index: 0;
  top: 0;
  left: var(--window-left);
  display: block;
  width: var(--window-width);
  height: 52px;
  overflow: hidden;
  border-radius: 999px;
  background: #fff;
  transition: left 170ms cubic-bezier(.22, .8, .24, 1), width 170ms cubic-bezier(.22, .8, .24, 1);
}

.morph-navigation-layer {
  position: absolute;
  top: 0;
  left: calc(-1 * var(--window-left));
  display: block;
  width: var(--stage-width);
  height: 52px;
  transition: left 170ms cubic-bezier(.22, .8, .24, 1);
}

.morph-navigation-shell,
.morph-navigation-indicator {
  position: absolute;
  display: block;
}

.morph-navigation-shell {
  z-index: 0;
  top: 0;
  left: 0;
  width: var(--shell-width);
  height: 52px;
  border: 1px solid #e3e8eb;
  border-radius: 999px;
  background: var(--navigation-paper);
  transition: left 170ms cubic-bezier(.22, .8, .24, 1), width 170ms cubic-bezier(.22, .8, .24, 1);
}

.morph-navigation[data-mode="workspace"] .morph-navigation-shell {
  left: var(--item-width);
}

.morph-navigation-indicator {
  z-index: 1;
  top: 6px;
  left: 6px;
  width: var(--item-width);
  height: 40px;
  border-radius: 999px;
  background: var(--navigation-ink);
  box-shadow: 0 6px 14px rgb(8 15 20 / 17%), 6px 7px 14px -10px rgb(24 201 239 / 72%);
  transform: translateX(calc(var(--active-slot) * var(--item-width)));
  opacity: 1;
  transition: transform 190ms cubic-bezier(.22, .8, .24, 1), opacity 90ms ease;
}

.morph-navigation[data-phase="closing"] .morph-navigation-indicator,
.morph-navigation[data-phase="closed"] .morph-navigation-indicator,
.morph-navigation[data-phase="opening"] .morph-navigation-indicator {
  opacity: 0;
  visibility: hidden;
}

button {
  position: absolute;
  z-index: 2;
  top: 6px;
  left: calc(6px + var(--slot) * var(--item-width));
  width: var(--item-width);
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  border-radius: 999px;
  padding: 0 14px;
  background: transparent;
  color: var(--navigation-muted);
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  line-height: 1;
  white-space: nowrap;
  transition: color 180ms ease, transform 180ms ease;
}

button:hover:not(:disabled) { color: var(--navigation-ink); }
button:active:not(:disabled) { transform: translateY(1px); }
button.is-active,
button.is-active:hover { color: #fff; }

.morph-navigation[data-phase="closing"] button.is-active,
.morph-navigation[data-phase="closed"] button.is-active,
.morph-navigation[data-phase="opening"] button.is-active {
  color: var(--navigation-muted);
}

.morph-navigation[data-phase="closing"] button.is-active span,
.morph-navigation[data-phase="closed"] button.is-active span,
.morph-navigation[data-phase="opening"] button.is-active span {
  opacity: 0;
}
button.is-active span {
  display: block;
  flex: 0 0 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--navigation-signal);
}
button:disabled { cursor: not-allowed; opacity: .42; }
button:focus-visible { outline: 2px solid var(--navigation-signal); outline-offset: 2px; }

.morph-navigation[data-phase="closing"] .morph-navigation-shell,
.morph-navigation[data-phase="closed"] .morph-navigation-shell {
  left: var(--item-width);
  width: 124px;
}

@media (max-width: 820px), (orientation: portrait) {
  .morph-navigation { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .morph-navigation-shell,
  .morph-navigation-indicator,
  .morph-navigation-window,
  .morph-navigation-layer,
  button {
    transition: none;
  }
}
</style>
