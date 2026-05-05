export type ApiResult<T> = {
  ok: boolean;
  data: T;
  message?: string;
};

export type AdminRequestOptions = RequestInit & {
  /**
   * 不触发全局“登录已失效”事件。
   * 用于 /auth/me 初始化探测、/auth/login 登录失败等场景，避免打开登录页就弹提示。
   */
  suppressAuthRequired?: boolean;
};

export const ADMIN_API_BASE = import.meta.env.VITE_ADMIN_API_BASE || '/api/admin';

export function adminApiUrl(path: string): string {
  const normalized = path.startsWith('/') ? path : `/${path}`;
  return `${ADMIN_API_BASE}${normalized}`;
}

export async function adminRequest<T>(path: string, options: AdminRequestOptions = {}): Promise<T> {
  const { suppressAuthRequired = false, ...requestOptions } = options;
  const headers = new Headers(requestOptions.headers || {});
  const init: RequestInit = {
    ...requestOptions,
    credentials: 'include',
    headers,
  };

  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const response = await fetch(adminApiUrl(path), init);
  let payload: ApiResult<T> | null = null;
  try {
    payload = (await response.json()) as ApiResult<T>;
  } catch {
    payload = null;
  }

  if (response.status === 401 && !suppressAuthRequired) {
    window.dispatchEvent(new Event('admin-auth-required'));
  }

  if (!response.ok || !payload?.ok) {
    throw new Error(payload?.message || `请求失败：HTTP ${response.status}`);
  }

  return payload.data;
}

export function buildQuery(params: Record<string, unknown>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return;
    query.set(key, String(value));
  });
  const text = query.toString();
  return text ? `?${text}` : '';
}
