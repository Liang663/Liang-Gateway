import type { Field, Column, ApiKey } from "./types";
import { integer, parseJson, date, money } from "./utils";

export interface Resource {
  title: string;
  description: string;
  path: string;
  fields: Field[];
  columns: Column[];
  defaults: Record<string, any>;
  deletable?: boolean;
  encode?: (form: Record<string, any>, editing: boolean) => unknown;
  decode?: (row: Record<string, any>) => Record<string, any>;
}
const name: Field = { key: "name", label: "名称", required: true };
const enabled: Field = { key: "enabled", label: "启用", type: "checkbox" };
const expiry: Field = {
  key: "expireTime",
  label: "有效期（上海时间）",
  type: "datetime-local",
  hint: "留空表示长期有效",
};
const state: Column = { key: "enabled", label: "状态" };
const normalizeDate = (f: Record<string, any>): Record<string, any> => ({
  ...f,
  expireTime: f.expireTime || null,
});
export function modelResource(keys: ApiKey[]): Resource {
  return {
    title: "模型管理",
    description: "连接模型与上游密钥，维护输入、输出计费价格。",
    path: "/admin/llm/models",
    fields: [
      name,
      { key: "provider", label: "提供商", required: true },
      {
        key: "apikeyCode",
        label: "上游密钥",
        type: "select",
        required: true,
        options: keys.map((k) => ({
          value: k.code,
          label: `${k.name} · ${k.prefix}`,
        })),
      },
      {
        key: "inputPriceFenPerMillion",
        label: "输入价格（分 / 百万 Token）",
        type: "number",
        required: true,
        min: 0,
      },
      {
        key: "outputPriceFenPerMillion",
        label: "输出价格（分 / 百万 Token）",
        type: "number",
        required: true,
        min: 0,
      },
      enabled,
    ],
    columns: [
      { key: "name", label: "模型名称" },
      { key: "provider", label: "提供商" },
      {
        key: "apikeyCode",
        label: "上游密钥",
        format: (v) => keys.find((k) => k.code === v)?.name || String(v),
      },
      {
        key: "inputPriceFenPerMillion",
        label: "输入 / 百万 Token",
        format: money,
      },
      {
        key: "outputPriceFenPerMillion",
        label: "输出 / 百万 Token",
        format: money,
      },
      state,
    ],
    defaults: {
      name: "",
      provider: "",
      apikeyCode: "",
      inputPriceFenPerMillion: 0,
      outputPriceFenPerMillion: 0,
      enabled: true,
    },
    encode: (f) => ({
      ...f,
      inputPriceFenPerMillion: integer(f.inputPriceFenPerMillion, "输入价格"),
      outputPriceFenPerMillion: integer(f.outputPriceFenPerMillion, "输出价格"),
    }),
  };
}
export const keyResource: Resource = {
  title: "上游密钥",
  description: "配置模型供应商连接。密钥明文仅在创建或替换时输入，列表不回显。",
  path: "/admin/llm/apikeys",
  fields: [
    name,
    { key: "provider", label: "提供商", required: true },
    {
      key: "baseUrl",
      label: "Base URL",
      required: true,
      hint: "例：https://api.example.com/v1",
    },
    {
      key: "secret",
      label: "API Key",
      type: "password",
      hint: "创建时必填；编辑时留空保留原密钥",
    },
    expiry,
    enabled,
  ],
  columns: [
    { key: "name", label: "名称" },
    { key: "provider", label: "提供商" },
    { key: "baseUrl", label: "Base URL" },
    { key: "prefix", label: "密钥前缀" },
    { key: "expireTime", label: "有效期", format: date },
    state,
  ],
  defaults: {
    name: "",
    provider: "",
    baseUrl: "",
    secret: "",
    expireTime: "",
    enabled: true,
  },
  decode: (r) => ({ ...r, secret: "" }),
  encode: (f, editing) => {
    const data = normalizeDate(f);
    if (!data.secret) {
      if (!editing) throw new Error("创建上游密钥时需要填写 API Key");
      delete data.secret;
    }
    return data;
  },
};
export const userResource: Resource = {
  title: "用户与令牌",
  description: "先选择用户，再管理其访问令牌、模型权限与周期额度。",
  path: "/admin/users",
  fields: [
    name,
    {
      key: "authority",
      label: "用户标识 / authority",
      required: true,
      hint: "沿用后端用户字段，不参与后台角色分配",
    },
    enabled,
  ],
  columns: [
    { key: "name", label: "用户名称" },
    { key: "code", label: "用户编码" },
    { key: "authority", label: "Authority" },
    state,
  ],
  defaults: { name: "", authority: "user", enabled: true },
};
export const serverResource: Resource = {
  title: "MCP 服务",
  description: "将已有 HTTP 接口发布为可发现、可调用的 MCP 工具。",
  path: "/admin/mcp/servers",
  deletable: true,
  fields: [
    name,
    {
      key: "path",
      label: "服务路径",
      required: true,
      hint: "单段路径，例如 orders，对应 /mcp/orders",
    },
    { key: "version", label: "服务版本", required: true },
    { key: "description", label: "描述", type: "textarea" },
    enabled,
  ],
  columns: [
    { key: "name", label: "服务名称" },
    { key: "path", label: "访问路径", format: (v) => `/mcp/${v}` },
    { key: "version", label: "服务版本" },
    { key: "description", label: "描述" },
    state,
  ],
  defaults: {
    name: "",
    path: "",
    version: "1.0.0",
    description: "",
    enabled: true,
  },
};
export function toolResource(serverCode: string): Resource {
  return {
    title: "工具管理",
    description: "维护 HTTP 映射；参数定义与网关工具映射保持一致。",
    path: `/admin/mcp/servers/${encodeURIComponent(serverCode)}/tools`,
    deletable: true,
    fields: [
      name,
      { key: "description", label: "描述", type: "textarea", required: true },
      {
        key: "httpMethod",
        label: "HTTP 方法",
        type: "select",
        required: true,
        options: ["GET", "POST", "PUT", "DELETE"].map((v) => ({
          value: v,
          label: v,
        })),
      },
      { key: "httpUrl", label: "HTTP URL", required: true },
      {
        key: "timeoutMs",
        label: "超时（毫秒）",
        type: "number",
        min: 1,
        required: true,
      },
      enabled,
      {
        key: "httpHeaders",
        label: "请求头 JSON",
        type: "json",
        required: true,
        hint: "对象，键和值均为字符串；包含凭据时请谨慎操作",
      },
      {
        key: "args",
        label: "参数映射 JSON",
        type: "json",
        required: true,
        hint: '数组，例如 [{"name":"id","value_type":"string","required":true,"position":"path"}]',
      },
    ],
    columns: [
      { key: "name", label: "工具名称" },
      { key: "httpMethod", label: "方法" },
      { key: "httpUrl", label: "HTTP URL" },
      { key: "timeoutMs", label: "超时 / ms" },
      state,
    ],
    defaults: {
      name: "",
      description: "",
      httpMethod: "GET",
      httpUrl: "",
      timeoutMs: 30000,
      httpHeaders: "{}",
      args: "[]",
      enabled: true,
    },
    decode: (r) => ({
      ...r,
      httpHeaders: JSON.stringify(r.httpHeaders || {}, null, 2),
      args: JSON.stringify(r.args || [], null, 2),
    }),
    encode: (f) => {
      const headers = parseJson(f.httpHeaders, "object", "请求头");
      if (Object.values(headers).some((v) => typeof v !== "string"))
        throw new Error("请求头值需为字符串");
      return {
        ...f,
        timeoutMs: integer(f.timeoutMs, "超时"),
        httpHeaders: headers,
        args: parseJson(f.args, "array", "参数映射"),
      };
    },
  };
}
