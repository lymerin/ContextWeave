<template>
  <nav
    class="platform-navigation"
    :style="{ '--active-index': activeIndex }"
    aria-label="平台导航"
  >
    <span v-if="visualActive" class="platform-navigation-indicator" aria-hidden="true" />
    <button
      v-for="item in items"
      :key="item.key"
      type="button"
      :class="{ 'is-active': visualActive === item.key }"
      :disabled="item.disabled"
      :aria-current="visualActive === item.key ? 'page' : undefined"
      :title="item.disabled ? '接入设置将在后端接口就绪后开放' : undefined"
      @click="navigate(item.key, item.target)"
    >
      {{ item.label }}
      <span v-if="visualActive === item.key" aria-hidden="true" />
    </button>
  </nav>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAuthStore } from '@/stores/auth';

type PlatformNavigationKey = 'home' | 'spaces' | 'account' | 'settings';

const props = defineProps<{ active?: PlatformNavigationKey | null }>();

const router = useRouter();
const authStore = useAuthStore();
const visualActive = ref<PlatformNavigationKey | null>(props.active ?? null);
const activeIndex = computed(() => ({ home: 0, spaces: 1, account: 2, settings: 3 })[visualActive.value ?? 'home']);
const items = computed(() => [
  { key: 'home' as const, label: '首页', target: '/', disabled: false },
  { key: 'spaces' as const, label: '协作空间', target: '/workspaces', disabled: false },
  {
    key: 'account' as const,
    label: authStore.isAuthenticated ? '账户' : '登录',
    target: authStore.isAuthenticated ? '/workspaces?panel=profile' : '/login',
    disabled: false,
  },
  { key: 'settings' as const, label: '设置', target: '', disabled: true },
]);

async function navigate(key: PlatformNavigationKey, target: string) {
  if (!target || key === visualActive.value) return;
  visualActive.value = key;
  await new Promise(resolve => window.setTimeout(resolve, 190));
  await router.push(target);
}
watch(() => props.active, value => { visualActive.value = value ?? null; });
</script>

<style scoped>
.platform-navigation { --item-width:112px; position:absolute; z-index:4; top:50%; left:50%; display:grid; width:max-content; grid-template-columns:repeat(4,var(--item-width)); align-items:center; border:1px solid #e3e8eb; border-radius:999px; padding:5px; background:#f0f2f3; transform:translate(-50%,-50%); }
.platform-navigation-indicator { position:absolute; z-index:0; top:5px; left:5px; width:var(--item-width); height:calc(100% - 10px); border-radius:999px; background:#090c0e; box-shadow:0 6px 14px rgb(8 15 20 / 17%); transform:translateX(calc(var(--active-index) * var(--item-width))); transition:transform 190ms cubic-bezier(.22,.8,.24,1); }
button { position:relative; z-index:1; min-height:40px; border:0; border-radius:999px; padding:0 14px; background:transparent; color:#78838c; cursor:pointer; font:inherit; font-size:14px; transition:color .18s ease,transform .18s ease; }
button:hover:not(:disabled) { color:#090c0e; }
button:active:not(:disabled) { transform:translateY(1px); }
button.is-active,button.is-active:hover { color:#fff; }
button.is-active span { display:inline-block; width:6px; height:6px; margin-left:7px; border-radius:50%; background:#18c9ef; vertical-align:2px; }
button:disabled { cursor:not-allowed; opacity:.42; }
button:focus-visible { outline:2px solid #18c9ef; outline-offset:2px; }
@media(min-width:821px) and (orientation:landscape){.platform-navigation{display:none}}
@media(max-width:820px),(orientation:portrait){.platform-navigation{--item-width:96px;position:static;grid-column:1/-1;grid-row:2;max-width:100%;overflow-x:auto;transform:none}.platform-navigation button{font-size:12px;padding-inline:8px}}
@media(prefers-reduced-motion:reduce){button,.platform-navigation-indicator{transition:none}}
</style>
