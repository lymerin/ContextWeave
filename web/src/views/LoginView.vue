<template>
  <main
    class="login-page"
    data-ark-theme="ark"
    data-ark-depth="moderate"
  >
    <header class="login-topbar">
      <button
        class="login-brand"
        type="button"
        aria-label="返回 ContextWeave 首页"
        @click="navigate('/')"
      >
        <span class="login-logo" aria-hidden="true"><span /></span>
        <span class="login-brand-name">ContextWeave</span>
        <span class="login-beta">BETA</span>
      </button>

      <PlatformNavigation active="account" />

      <div class="login-topbar-status" aria-label="当前为安全登录页面">
        <span class="login-secure-copy"><span aria-hidden="true" />安全登录</span>
        <span class="login-account-mark" aria-hidden="true">↗</span>
      </div>
    </header>

    <section class="login-stage" aria-labelledby="login-title">
      <article class="login-introduction">
        <div>
          <p class="login-kicker">
            <span aria-hidden="true" />
            KNOWLEDGE × AGENT COLLABORATION
          </p>
          <h1>回到团队知识现场<br>继续协作</h1>
          <p class="login-description">
            登录后回到你的目标页面，继续管理工作区、编辑团队文档并处理 Agent 审阅
          </p>
        </div>

        <div class="login-capabilities" aria-label="协作能力">
          <span>工作区 / WORKSPACE</span>
          <span>文档 / DOCS</span>
          <span>审阅 / REVIEW</span>
        </div>
      </article>

      <section class="login-form-panel">
        <div class="login-form-heading">
          <p>ACCESS / LOGIN</p>
          <h2 id="login-title">欢迎回来</h2>
          <span>使用你的账号继续访问 ContextWeave</span>
        </div>

        <el-form
          ref="formRef"
          class="login-form"
          :model="form"
          :rules="rules"
          label-position="top"
          @keyup.enter="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model.trim="form.username"
              autocomplete="username"
              placeholder="请输入用户名"
              size="large"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
              size="large"
              type="password"
            />
          </el-form-item>

          <el-button
            class="login-submit"
            size="large"
            :loading="submitting"
            @click="submit"
          >
            <span>{{ submitting ? '正在登录' : '登录' }}</span>
            <span aria-hidden="true">↗</span>
          </el-button>
        </el-form>

        <p class="login-switch">
          <span>还没有账号</span>
          <RouterLink to="/register">
            创建账号 <span aria-hidden="true">↗</span>
          </RouterLink>
        </p>

        <p class="login-footnote">
          登录即表示你将进入当前账号可访问的协作空间
        </p>
      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus';
import { ElMessage } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAuthStore } from '@/stores/auth';
import { readableError } from '@/utils/error';
import PlatformNavigation from '@/components/layout/PlatformNavigation.vue';

interface LoginForm {
  username: string;
  password: string;
}


const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();
const formRef = ref<FormInstance>();
const submitting = ref(false);

const form = reactive<LoginForm>({
  username: '',
  password: '',
});

const rules: FormRules<LoginForm> = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 32, message: '用户名不能超过 32 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 72, message: '密码不能超过 72 个字符', trigger: 'blur' },
  ],
};

async function navigate(target: string) {
  await router.push(target);
}

async function submit() {
  if (submitting.value) {
    return;
  }

  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }

  submitting.value = true;
  try {
    await authStore.login(form);
    ElMessage.success('登录成功');
    const redirect = typeof route.query.redirect === 'string'
      ? route.query.redirect
      : '/';
    await router.push(redirect);
  } catch (error) {
    ElMessage.error(readableError(error, '登录失败，请检查用户名和密码'));
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.login-page {
  --login-ink: #090c0e;
  --login-navy: #10203e;
  --login-paper: #fff;
  --login-cyan: #18c7ea;
  --login-muted: #69777e;
  position: relative;
  min-height: 100svh;
  overflow: hidden;
  background:
    radial-gradient(circle at 14% 18%, rgb(179 232 241 / 34%), transparent 30%),
    radial-gradient(circle at 82% 86%, rgb(201 230 255 / 34%), transparent 32%),
    linear-gradient(135deg, #eef3f5, #f8f9fa 48%, #edf3f6);
  color: var(--login-ink);
  font-family: "Noto Sans SC", "Source Han Sans SC", "PingFang SC", system-ui, sans-serif;
}

.login-page::before,
.login-page::after {
  position: absolute;
  z-index: 0;
  pointer-events: none;
  content: "";
}

.login-page::before {
  inset: 72px 0 0;
  opacity: .42;
  background-image: radial-gradient(circle, rgb(42 86 100 / 23%) 1px, transparent 1.2px);
  background-size: 22px 22px;
  mask-image: linear-gradient(to bottom, #000, rgb(0 0 0 / 54%) 62%, transparent);
  animation: login-grid-drift 20s linear infinite alternate;
}

.login-page::after {
  right: -160px;
  bottom: -220px;
  width: 440px;
  height: 440px;
  border: 1px solid rgb(24 199 234 / 16%);
  border-radius: 50%;
  box-shadow: 0 0 0 72px rgb(24 199 234 / 3%), 0 0 0 144px rgb(24 199 234 / 2%);
}

@keyframes login-grid-drift {
  from { transform: translate3d(-8px, -5px, 0); }
  to { transform: translate3d(18px, 8px, 0); }
}

.login-topbar {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-columns: minmax(250px, 1fr) auto minmax(190px, 1fr);
  align-items: center;
  min-height: 72px;
  padding: 0 36px;
  border-bottom: 1px solid rgb(15 26 31 / 8%);
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 10px 32px rgb(38 56 67 / 6%);
}

.login-brand,
.login-navigation,
.login-nav-item,
.login-topbar-status,
.login-secure-copy,
.login-capabilities,
.login-submit,
.login-switch {
  display: flex;
  align-items: center;
}

.login-brand {
  width: max-content;
  gap: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.login-logo {
  position: relative;
  width: 38px;
  height: 38px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--login-ink);
}

.login-logo::before,
.login-logo::after,
.login-logo span {
  position: absolute;
  left: 9px;
  width: 20px;
  height: 2px;
  content: "";
  background: var(--login-cyan);
  transform: skewX(-28deg);
}

.login-logo::before { top: 11px; }
.login-logo span { top: 18px; width: 13px; }
.login-logo::after { top: 25px; }
.login-brand-name { font-weight: 700; letter-spacing: -.03em; }

.login-beta {
  padding: 4px 8px;
  border: 1px solid #e0e5e8;
  border-radius: 5px;
  background: #f0f3f5;
  color: #566066;
  font-size: 10px;
  letter-spacing: .12em;
}

.login-navigation {
  gap: 4px;
  padding: 5px;
  border: 1px solid #e4e9ec;
  border-radius: 999px;
  background: #eef1f3;
}

.login-nav-item {
  flex: 0 0 auto;
  justify-content: center;
  gap: 7px;
  min-height: 38px;
  padding: 0 17px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #6d777c;
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition: color 180ms ease, background 180ms ease, box-shadow 180ms ease;
}

.login-nav-item:hover { color: var(--login-ink); }
.login-nav-item.is-active {
  background: var(--login-ink);
  color: white;
  box-shadow: 0 5px 12px rgb(8 12 14 / 17%);
}

.login-nav-item.is-active > span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--login-cyan);
}

.login-nav-item[aria-disabled="true"] {
  color: #a5adb1;
  cursor: not-allowed;
}

.login-topbar-status {
  justify-content: flex-end;
  gap: 12px;
  color: #6d777c;
  font-size: 13px;
}

.login-secure-copy { gap: 7px; }
.login-secure-copy > span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #18c58b;
  box-shadow: 0 0 0 5px rgb(24 197 139 / 10%);
}

.login-account-mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 50%;
  background: var(--login-ink);
  color: var(--login-cyan);
  font-size: 16px;
}

.login-stage {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(380px, .88fr);
  width: min(1280px, calc(100% - 64px));
  min-height: min(690px, calc(100svh - 124px));
  margin: 26px auto;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 72%);
  border-radius: 24px;
  background: rgb(255 255 255 / 82%);
  box-shadow: 0 26px 70px rgb(35 60 74 / 10%);
  backdrop-filter: blur(18px);
}

.login-introduction {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
  padding: clamp(54px, 6vw, 86px);
  overflow: hidden;
  border-right: 1px solid #e8edef;
  background: linear-gradient(116deg, rgb(255 255 255 / 92%), rgb(251 253 254 / 88%) 56%, rgb(219 244 250 / 62%));
}

.login-introduction::before {
  position: absolute;
  top: 74px;
  left: 0;
  width: 3px;
  height: 58px;
  border-radius: 0 4px 4px 0;
  background: linear-gradient(to bottom, transparent, var(--login-cyan) 34%, var(--login-cyan) 68%, transparent);
  box-shadow: 4px 0 15px rgb(24 199 234 / 28%);
  content: "";
}

.login-introduction::after {
  position: absolute;
  right: -54px;
  bottom: -120px;
  width: 350px;
  height: 350px;
  border: 1px solid rgb(24 199 234 / 12%);
  border-radius: 50%;
  content: "";
}

.login-kicker {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 0 38px;
  color: #2a6b7d;
  font-family: "IBM Plex Mono", Consolas, monospace;
  font-size: 10px;
  letter-spacing: .14em;
}

.login-kicker > span { width: 22px; height: 1px; background: var(--login-cyan); }

.login-introduction h1 {
  margin: 0;
  color: var(--login-navy);
  font-size: clamp(44px, 5vw, 68px);
  font-weight: 800;
  letter-spacing: -.065em;
  line-height: 1.02;
}

.login-description {
  max-width: 510px;
  margin: 28px 0 0;
  color: var(--login-muted);
  font-size: 16px;
  line-height: 1.9;
}

.login-capabilities {
  position: relative;
  z-index: 1;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 48px;
}

.login-capabilities span {
  padding: 8px 11px;
  border: 1px solid #e1e8eb;
  border-radius: 5px;
  background: rgb(255 255 255 / 72%);
  color: #65737a;
  font-family: "IBM Plex Mono", Consolas, monospace;
  font-size: 9px;
  letter-spacing: .09em;
}

.login-form-panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: clamp(48px, 5vw, 78px);
  background: rgb(255 255 255 / 95%);
}

.login-form-heading > p {
  margin: 0 0 12px;
  color: #2a6b7d;
  font-family: "IBM Plex Mono", Consolas, monospace;
  font-size: 10px;
  letter-spacing: .16em;
}

.login-form-heading h2 {
  margin: 0;
  font-size: clamp(30px, 3vw, 40px);
  letter-spacing: -.055em;
}

.login-form-heading > span {
  display: block;
  margin-top: 10px;
  color: var(--login-muted);
  font-size: 13px;
}

.login-form { margin-top: 38px; }
.login-form :deep(.el-form-item) { margin-bottom: 24px; }
.login-form :deep(.el-form-item__label) {
  padding-bottom: 9px;
  color: #303a3f;
  font-size: 13px;
  font-weight: 650;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 50px;
  border: 1px solid #dfe6e9;
  border-radius: 7px;
  background: #f6f8f9;
  box-shadow: none;
  transition: border-color 180ms ease, background 180ms ease, box-shadow 180ms ease;
}

.login-form :deep(.el-input__wrapper:hover) { border-color: #b9c7cc; }
.login-form :deep(.el-input__wrapper.is-focus) {
  border-color: var(--login-cyan);
  background: white;
  box-shadow: 0 6px 18px rgb(24 199 234 / 10%);
}

.login-submit {
  position: relative;
  justify-content: space-between;
  width: 100%;
  min-height: 52px;
  margin-top: 7px;
  border: 0;
  border-radius: 7px;
  background: var(--login-ink);
  color: white;
  font-size: 15px;
  font-weight: 700;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.login-submit:hover,
.login-submit:focus-visible {
  border: 0;
  background: var(--login-ink);
  color: white;
  transform: translateY(-1px);
  box-shadow: 5px 5px 0 rgb(24 199 234 / 30%), 0 12px 25px rgb(24 199 234 / 12%);
}

.login-submit > span:last-child { color: var(--login-cyan); transition: transform 180ms ease; }
.login-submit:hover > span:last-child { transform: translate(2px, -2px); }

.login-switch {
  justify-content: space-between;
  margin: 25px 0 0;
  color: #78858b;
  font-size: 13px;
}

.login-switch a {
  color: #5f6b70;
  font-weight: 650;
  text-decoration: none;
  transition: color 180ms ease, transform 180ms ease;
}

.login-switch a:hover { color: var(--login-ink); transform: translateX(2px); }
.login-switch a span { color: var(--login-cyan); }

.login-footnote {
  margin: 36px 0 0;
  padding-top: 18px;
  border-top: 1px solid #edf0f2;
  color: #96a0a5;
  font-size: 11px;
  line-height: 1.6;
}

.login-brand:focus-visible,
.login-nav-item:focus-visible,
.login-switch a:focus-visible,
.login-submit:focus-visible {
  outline: 2px solid var(--login-cyan);
  outline-offset: 3px;
}

@media (max-width: 1080px) {
  .login-topbar { grid-template-columns: auto 1fr auto; padding: 0 20px; }
  .login-brand-name,
  .login-beta { display: none; }
  .login-nav-item { padding: 0 10px; }
  .login-stage { grid-template-columns: minmax(0, 1fr) minmax(360px, .92fr); }
}

@media (max-width: 820px), (orientation: portrait) {
  .login-page { overflow: auto; }
  .login-topbar {
    grid-template-columns: auto auto;
    gap: 12px;
    min-height: 68px;
    padding: 12px 16px;
  }
  .login-navigation {
    grid-column: 1 / -1;
    order: 3;
    min-width: 0;
    width: 100%;
    justify-content: flex-start;
    overflow-x: auto;
    border-radius: 12px;
    scrollbar-width: none;
  }
  .login-navigation::-webkit-scrollbar { display: none; }
  .login-topbar-status { justify-self: end; }
  .login-secure-copy { display: none; }
  .login-stage {
    grid-template-columns: 1fr;
    width: min(100% - 28px, 680px);
    min-height: auto;
    margin: 18px auto 28px;
    border-radius: 18px;
  }
  .login-introduction {
    min-height: 390px;
    padding: 42px 34px;
    border-right: 0;
    border-bottom: 1px solid #e8edef;
  }
  .login-introduction h1 { font-size: clamp(38px, 11vw, 56px); }
  .login-form-panel { padding: 42px 34px; }
}

@media (max-width: 520px) {
  .login-nav-item { min-height: 34px; padding: 0 9px; font-size: 12px; }
  .login-stage { width: min(100% - 20px, 460px); }
  .login-introduction { min-height: 350px; padding: 36px 24px; }
  .login-kicker { margin-bottom: 28px; font-size: 9px; }
  .login-introduction h1 { font-size: 38px; }
  .login-description { font-size: 14px; line-height: 1.8; }
  .login-capabilities { margin-top: 34px; }
  .login-form-panel { padding: 36px 24px; }
  .login-form { margin-top: 30px; }
}

@media (prefers-reduced-motion: reduce) {
  .login-page::before { animation: none; }
  .login-nav-item,
  .login-form :deep(.el-input__wrapper),
  .login-submit,
  .login-submit > span:last-child,
  .login-switch a { transition: none; }
}
</style>
