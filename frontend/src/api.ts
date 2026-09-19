import { ref } from "vue";

// Credentials intentionally live only in this module's memory.
const adminSecret = ref("");
export const authenticated = () => !!adminSecret.value;
export function logout() {
  adminSecret.value = "";
}
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public body?: unknown,
  ) {
    super(message);
  }
}
export const errorText = (error: unknown) =>
  error instanceof Error ? error.message : "请求失败，请重试";
export function query(params: Record<string, unknown>) {
  const entries = Object.entries(params)
    .filter(([, v]) => v !== "" && v != null)
    .map(([k, v]) => [k, String(v)]);
  return new URLSearchParams(entries).toString();
}
async function responseBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
function messageOf(body: unknown, status: number) {
  const data = body as
    { error?: { message?: string }; message?: string } | undefined;
  return data?.error?.message || data?.message || `请求失败（HTTP ${status}）`;
}
export async function login(secret: string) {
  const response = await fetch("/admin/users", {
    headers: { "X-Admin-Token": secret },
    cache: "no-store",
    redirect: "error",
  });
  if (!response.ok)
    throw new ApiError(
      messageOf(await responseBody(response), response.status),
      response.status,
    );
  adminSecret.value = secret;
}
export async function admin<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  if (!path.startsWith("/admin/")) throw new Error("管理请求路径无效");
  if (!adminSecret.value) throw new ApiError("请先输入管理密钥", 401);
  const response = await fetch(path, {
    method,
    headers: {
      "X-Admin-Token": adminSecret.value,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
    redirect: "error",
  });
  const data = await responseBody(response);
  if (!response.ok) {
    if (response.status === 401) logout();
    throw new ApiError(messageOf(data, response.status), response.status);
  }
  return data as T;
}
export async function dataRequest(
  path: string,
  token: string,
  body: unknown,
  signal?: AbortSignal,
  headers: Record<string, string> = {},
) {
  if (!path.startsWith("/v1/") && !path.startsWith("/mcp/"))
    throw new Error("调试请求路径无效");
  const response = await fetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...headers,
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
    signal,
    redirect: "error",
  });
  if (!response.ok) {
    const data = await responseBody(response);
    throw new ApiError(messageOf(data, response.status), response.status, data);
  }
  return response;
}
