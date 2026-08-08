<template>
  <el-dialog
    v-model="visible"
    width="540px"
    destroy-on-close
    append-to-body
    class="workspace-create-dialog"
  >
    <template #header>
      <div class="workspace-dialog-heading">
        <small>WORKSPACE / CREATE</small>
        <h2>创建工作区</h2>
        <p>连接代码仓库，建立新的团队协作空间</p>
      </div>
    </template>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      @keyup.enter="submit"
    >
      <div class="workspace-form-section">
        <span class="workspace-form-index">01</span>
        <div>
          <small>BASIC INFORMATION</small>
          <strong>基本信息</strong>
        </div>
      </div>
      <el-form-item label="工作区名称" prop="name">
        <el-input
          v-model.trim="form.name"
          maxlength="100"
          placeholder="例如：实验室知识库"
          show-word-limit
        />
      </el-form-item>

      <div class="workspace-form-section is-repository">
        <span class="workspace-form-index">02</span>
        <div>
          <small>REPOSITORY SOURCE</small>
          <strong>仓库来源</strong>
        </div>
      </div>
      <el-form-item label="GitHub 仓库地址" prop="repositoryUrl">
        <el-input
          v-model.trim="form.repositoryUrl"
          placeholder="例如：https://github.com/octocat/Hello-World.git"
        />
      </el-form-item>

      <el-form-item label="分支名称" prop="branch">
        <el-input
          v-model.trim="form.branch"
          maxlength="200"
          placeholder="例如：main"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <button class="workspace-dialog-cancel" type="button" @click="visible = false">
        取消
      </button>
      <button
        class="workspace-dialog-confirm"
        type="button"
        :disabled="submitting"
        @click="submit"
      >
        {{ submitting ? '正在创建' : '创建并导入' }}
        <span aria-hidden="true">↗</span>
      </button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus';
import { reactive, ref, watch } from 'vue';

interface WorkspaceForm {
  name: string;
  repositoryUrl: string;
  branch: string;
}

const visible = defineModel<boolean>({
  required: true,
});

const emit = defineEmits<{
  create: [payload: WorkspaceForm];
}>();

const formRef = ref<FormInstance>();
const submitting = ref(false);

const form = reactive<WorkspaceForm>({
  name: '',
  repositoryUrl: '',
  branch: 'main',
});

const rules: FormRules<WorkspaceForm> = {
  name: [
    {
      required: true,
      message: '请输入工作区名称',
      trigger: 'blur',
    },
    {
      max: 100,
      message: '工作区名称不能超过 100 个字符',
      trigger: 'blur',
    },
  ],

  repositoryUrl: [
    {
      required: true,
      message: '请输入 GitHub 仓库地址',
      trigger: 'blur',
    },
  ],

  branch: [
    {
      required: true,
      message: '请输入分支名称',
      trigger: 'blur',
    },
    {
      max: 200,
      message: '分支名称不能超过 200 个字符',
      trigger: 'blur',
    },
  ],
};

watch(visible, (isVisible) => {
  if (!isVisible) {
    form.name = '';
    form.repositoryUrl = '';
    form.branch = 'main';
    formRef.value?.clearValidate();
  }
});

async function submit() {
  if (submitting.value) {
    return;
  }

  let valid = false;

  try {
    valid = (await formRef.value?.validate()) ?? false;
  } catch {
    return;
  }

  if (!valid) {
    return;
  }

  submitting.value = true;

  try {
    emit('create', {
      name: form.name,
      repositoryUrl: form.repositoryUrl,
      branch: form.branch,
    });
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
:global(.workspace-create-dialog) {
  overflow: hidden;
  border: 1px solid #dfe7e9;
  border-radius: 12px;
  box-shadow: 0 28px 70px rgb(12 29 36 / 22%);
}

:global(.workspace-create-dialog::before) {
  position: absolute;
  top: 29px;
  left: 0;
  width: 3px;
  height: 52px;
  border-radius: 0 4px 4px 0;
  background: linear-gradient(180deg, rgb(24 201 239 / 18%), #18c9ef 48%, rgb(24 201 239 / 22%));
  box-shadow: 4px 0 14px rgb(24 201 239 / 26%);
  content: '';
}

:global(.workspace-create-dialog .el-dialog__header) {
  margin: 0;
  padding: 28px 32px 23px;
  border-bottom: 1px solid #e5ebed;
}

:global(.workspace-create-dialog .el-dialog__headerbtn) {
  top: 22px;
  right: 22px;
}

.workspace-dialog-heading small,
.workspace-form-section small {
  display: block;
  color: #168ba5;
  font: 600 9px Consolas, monospace;
  letter-spacing: .14em;
}

.workspace-dialog-heading h2 {
  margin: 8px 0 0;
  color: #10191d;
  font-size: 22px;
  line-height: 1.2;
}

.workspace-dialog-heading p {
  margin: 7px 0 0;
  color: #7a878c;
  font-size: 12px;
}

:global(.workspace-create-dialog .el-dialog__body) {
  padding: 22px 32px 8px;
}

.workspace-form-section {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 14px;
}

.workspace-form-section.is-repository {
  margin-top: 22px;
  border-top: 1px solid #e7ecee;
  padding-top: 20px;
}

.workspace-form-index {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 6px;
  background: #090c0e;
  color: #18c9ef;
  font: 600 10px Consolas, monospace;
}

.workspace-form-section strong {
  display: block;
  margin-top: 3px;
  color: #182126;
  font-size: 13px;
}

:global(.workspace-create-dialog .el-form-item) {
  margin-bottom: 16px;
}

:global(.workspace-create-dialog .el-form-item__label) {
  height: auto;
  margin-bottom: 7px;
  color: #4f5c62;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

:global(.workspace-create-dialog .el-input__wrapper) {
  min-height: 44px;
  border: 1px solid #dfe7e9;
  border-radius: 7px;
  padding: 0 13px;
  background: #fbfcfc;
  box-shadow: none;
  transition: border-color 180ms ease, box-shadow 180ms ease, background 180ms ease;
}

:global(.workspace-create-dialog .el-input__wrapper:hover) {
  border-color: #b8c7cc;
}

:global(.workspace-create-dialog .el-input__wrapper.is-focus) {
  border-color: #18c9ef;
  background: #fff;
  box-shadow: 0 0 0 3px rgb(24 201 239 / 10%);
}

:global(.workspace-create-dialog .el-input__inner) {
  color: #172126;
  font-size: 13px;
}

:global(.workspace-create-dialog .el-dialog__footer) {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 8px 32px 28px;
}

.workspace-dialog-cancel,
.workspace-dialog-confirm {
  min-height: 46px;
  border-radius: 8px;
  padding: 0 21px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: border-color 180ms ease, box-shadow 220ms ease, transform 180ms ease;
}

.workspace-dialog-cancel {
  border: 1px solid #dfe7e9;
  background: #fff;
  color: #69767c;
}

.workspace-dialog-confirm {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border: 1px solid #090c0e;
  background: #090c0e;
  color: #fff;
}

.workspace-dialog-confirm span {
  color: #18c9ef;
  font-size: 15px;
  transition: transform 180ms ease;
}

.workspace-dialog-cancel:hover,
.workspace-dialog-cancel:focus-visible,
.workspace-dialog-confirm:hover,
.workspace-dialog-confirm:focus-visible {
  border-color: rgb(24 201 239 / 24%);
  box-shadow: 7px 7px 14px -9px rgb(24 201 239 / 78%), 13px 12px 24px -17px rgb(24 201 239 / 64%);
  outline: none;
  transform: translateY(-2px);
}

.workspace-dialog-confirm:hover span,
.workspace-dialog-confirm:focus-visible span {
  transform: translate(2px, -1px);
}

.workspace-dialog-confirm:disabled {
  cursor: wait;
  opacity: .6;
}

@media (max-width: 620px) {
  :global(.workspace-create-dialog) {
    width: calc(100vw - 28px) !important;
  }

  :global(.workspace-create-dialog .el-dialog__header),
  :global(.workspace-create-dialog .el-dialog__body) {
    padding-right: 22px;
    padding-left: 22px;
  }

  :global(.workspace-create-dialog .el-dialog__footer) {
    padding-right: 22px;
    padding-left: 22px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workspace-dialog-cancel,
  .workspace-dialog-confirm,
  .workspace-dialog-confirm span {
    transition: none;
  }
}
</style>
