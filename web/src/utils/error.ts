import { AxiosError } from 'axios';

import type { ApiErrorResponse } from '@/api/auth';

const MESSAGE_BY_CODE: Record<string, string> = {
  AUTH_ACCESS_INVALID: '登录状态已过期，请重新登录',
  AUTH_REFRESH_INVALID: '登录会话已失效，请重新登录',
  AUTH_CSRF_INVALID: '会话安全校验失败，请刷新页面后重试',
  AUTH_INVALID_CREDENTIALS: '用户名或密码不正确',
  AUTH_USERNAME_ALREADY_EXISTS: '用户名已存在，请换一个用户名',
  WORKSPACE_NOT_FOUND: '工作区不存在或已被删除',
  WORKSPACE_ACCESS_DENIED: '你没有访问该工作区的权限',
  WORKSPACE_USER_NOT_FOUND: '用户不存在，请检查用户名',
  WORKSPACE_MEMBER_NOT_FOUND: '工作区成员不存在或已被移除',
  WORKSPACE_MEMBER_EXISTS: '该用户已经是工作区成员',
  WORKSPACE_LAST_ADMIN: '工作区至少需要保留一名管理员',
  DOCUMENT_NOT_FOUND: '文档不存在或已被删除',
  DOCUMENT_PARENT_CYCLE: '不能将文档移动到自身或自己的子文档下',
  DOCUMENT_BLOCK_NOT_FOUND: '内容块不存在或已被删除',
  DOCUMENT_BLOCK_VERSION_CONFLICT: '内容已被其他操作修改，请刷新后再编辑',
  VALIDATION_FAILED: '请求内容不完整，请检查后重试',
};

export function readableError(error: unknown, fallback: string): string {
  if (error instanceof AxiosError) {
    const response = error.response?.data as ApiErrorResponse | undefined;
    const code = response?.code;

    if (code && MESSAGE_BY_CODE[code]) {
      return MESSAGE_BY_CODE[code];
    }

    if (error.response?.status === 401) {
      return MESSAGE_BY_CODE.AUTH_ACCESS_INVALID;
    }

    if (error.response?.status === 403) {
      return response?.message || '没有审批权限';
    }

    if (error.response?.status === 409) {
      return MESSAGE_BY_CODE.DOCUMENT_BLOCK_VERSION_CONFLICT;
    }

    return response?.message || fallback;
  }

  if (error instanceof Error) {
    return error.message;
  }

  return fallback;
}

export function isConflictError(error: unknown): boolean {
  if (!(error instanceof AxiosError)) {
    return false;
  }

  const response = error.response?.data as ApiErrorResponse | undefined;
  return (
    error.response?.status === 409
    || response?.code === 'DOCUMENT_BLOCK_VERSION_CONFLICT'
  );
}
