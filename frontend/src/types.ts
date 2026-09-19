export interface Entity {
  code: string;
  enabled: boolean;
  createTime?: string;
  updateTime?: string;
}
export interface Model extends Entity {
  name: string;
  provider: string;
  apikeyCode: string;
  inputPriceFenPerMillion: number;
  outputPriceFenPerMillion: number;
}
export interface ApiKey extends Entity {
  name: string;
  provider: string;
  baseUrl: string;
  prefix: string;
  expireTime: string | null;
}
export interface User extends Entity {
  name: string;
  authority: string;
}
export interface Token extends Entity {
  userCode: string;
  accessToken: string;
  qpmLimit: number;
  models: string[];
  limits: { limitType: number; usage: number; used: number }[];
  expireTime: string | null;
}
export interface Server extends Entity {
  name: string;
  path: string;
  description: string;
  version: string;
}
export interface Tool extends Entity {
  serverCode: string;
  name: string;
  description: string;
  httpUrl: string;
  httpMethod: string;
  httpHeaders: Record<string, string>;
  timeoutMs: number;
  args: Record<string, unknown>[];
}
export interface QuotaWindow {
  used: number;
  limit: number;
  windowStart: string | null;
  windowEnd: string | null;
}
export interface Quota {
  fiveHour: QuotaWindow;
  week: QuotaWindow;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
export interface CallLog {
  code: string;
  llmApikeyCode: string;
  model: string;
  success: boolean;
  message: string;
  firstTokenMs: number | null;
  totalDurationMs: number;
  createTime: string;
}
export interface UsageRecord {
  code: string;
  userCode: string;
  tokenCode: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  amountFen: number;
  model: string;
  requestId: string;
  createTime: string;
}
export interface LlmStats {
  total: number;
  successCount: number;
  successRate: number;
  averageFirstTokenMs: number | null;
  trend: { time: string; count: number }[];
  models: { model: string; count: number }[];
}
export interface UsageStats {
  totalRecords: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  amountFen: number;
}
export interface Field {
  key: string;
  label: string;
  type?:
    | "text"
    | "password"
    | "number"
    | "datetime-local"
    | "textarea"
    | "json"
    | "select"
    | "checkbox";
  required?: boolean;
  hint?: string;
  options?: { label: string; value: string }[];
  min?: number;
  step?: string;
}
export interface Column {
  key: string;
  label: string;
  format?: (value: unknown) => string;
}
