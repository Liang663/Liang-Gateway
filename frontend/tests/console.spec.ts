import { test, expect, type Page } from "@playwright/test";
async function fixtures(page: Page) {
  const models: any[] = [
    {
      code: "model-1",
      name: "test-chat",
      provider: "test",
      apikeyCode: "key-1",
      inputPriceFenPerMillion: 100,
      outputPriceFenPerMillion: 200,
      enabled: true,
    },
  ];
  const users: any[] = [
    { code: "user-1", name: "测试用户", authority: "user", enabled: true },
  ];
  const keys: any[] = [
    {
      code: "key-1",
      name: "测试上游",
      provider: "test",
      baseUrl: "http://example.test/v1",
      prefix: "test-",
      enabled: true,
    },
  ];
  const tokens: any[] = [
    {
      code: "token-1",
      userCode: "user-1",
      accessToken: "test-access",
      qpmLimit: 60,
      models: ["test-chat"],
      limits: [{ limitType: 1, usage: 1000, used: 100 }],
      enabled: true,
    },
  ];
  const servers: any[] = [
    {
      code: "server-1",
      name: "订单服务",
      path: "orders",
      description: "订单",
      version: "1.0",
      enabled: true,
    },
  ];
  const tools: any[] = [];
  let used = 100;
  await page.route("**/admin/**", async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname,
      method = req.method();
    if (req.headers()["x-admin-token"] !== "test-admin") {
      await route.fulfill({
        status: 401,
        json: { error: { message: "unauthorized" } },
      });
      return;
    }
    const json = (body: unknown) => route.fulfill({ json: body });
    if (path === "/admin/llm/stats")
      return json({
        total: 12,
        successCount: 11,
        successRate: 11 / 12,
        averageFirstTokenMs: 250,
        trend: [
          { time: "2026-09-18T09:00:00", count: 4 },
          { time: "2026-09-18T10:00:00", count: 8 },
        ],
        models: [{ model: "test-chat", count: 12 }],
      });
    if (path === "/admin/usage/stats")
      return json({
        totalRecords: 11,
        promptTokens: 100,
        completionTokens: 200,
        totalTokens: 300,
        amountFen: 123,
      });
    if (path === "/admin/llm/call-logs")
      return json({
        items:
          url.searchParams.get("model") === "none"
            ? []
            : [
                {
                  code: "log-1",
                  model: "test-chat",
                  llmApikeyCode: "key-1",
                  success: true,
                  firstTokenMs: 250,
                  totalDurationMs: 400,
                  createTime: "2026-09-18T10:00:00",
                },
              ],
        total: 1,
        page: 1,
        pageSize: 20,
      });
    if (path === "/admin/usage/records")
      return json({ items: [], total: 0, page: 1, pageSize: 20 });
    if (path.endsWith("/quota/reset")) {
      used = 0;
      return route.fulfill({ status: 200, body: "" });
    }
    if (path.endsWith("/quota"))
      return json({
        fiveHour: {
          used,
          limit: 1000,
          windowStart: "2026-09-18T01:00:00Z",
          windowEnd: "2026-09-18T06:00:00Z",
        },
        week: { used: 0, limit: 0, windowStart: null, windowEnd: null },
      });
    if (path.endsWith("/tools:import")) {
      tools.push({
        code: "tool-1",
        name: "get_order",
        httpMethod: "GET",
        httpUrl: "http://example.test/orders",
        timeoutMs: 30000,
        enabled: true,
      });
      return json(tools);
    }
    const resources: Record<string, any[]> = {
      "/admin/llm/models": models,
      "/admin/llm/apikeys": keys,
      "/admin/users": users,
      "/admin/users/user-1/tokens": tokens,
      "/admin/mcp/servers": servers,
      "/admin/mcp/servers/server-1/tools": tools,
    };
    const base = Object.keys(resources)
      .sort((a, b) => b.length - a.length)
      .find((key) => path === key || path.startsWith(key + "/"));
    if (base) {
      const rows = resources[base]!;
      if (method === "GET") return json(rows);
      if (method === "POST") {
        const created = {
          ...req.postDataJSON(),
          code: "created-" + rows.length,
        };
        rows.push(created);
        return json(created);
      }
      if (method === "PUT") {
        const row = rows.find((r) => r.code === path.split("/").at(-1));
        Object.assign(row, req.postDataJSON());
        return json(row);
      }
      if (method === "DELETE") {
        const index = rows.findIndex((r) => r.code === path.split("/").at(-1));
        rows.splice(index, 1);
        return route.fulfill({ status: 200, body: "" });
      }
    }
    return route.fulfill({
      status: 404,
      json: { message: "Fixture not found" },
    });
  });
  return { models, tokens, tools };
}
async function signIn(page: Page) {
  await page.goto("./");
  await page.getByLabel("管理密钥", { exact: true }).fill("test-admin");
  await page.getByRole("button", { name: "进入管理后台" }).click();
  await expect(page.getByRole("heading", { name: "网关概览" })).toBeVisible();
}
test("desktop overview, routes and memory-only sign-in", async ({ page }) => {
  await fixtures(page);
  await signIn(page);
  await expect(page.getByText("¥1.23", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "../target/console-overview.png",
    fullPage: true,
  });
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBe(width);
  }
  for (const [path, title] of [
    ["/models", "模型管理"],
    ["/keys", "上游密钥"],
    ["/users", "用户与令牌"],
    ["/mcp", "MCP 服务"],
    ["/logs", "日志与用量"],
    ["/playground", "在线调试"],
  ]) {
    await page.goto(`./#${path}`);
    await expect(
      page.getByRole("heading", { name: title, exact: true }),
    ).toBeVisible();
  }
  await page.reload();
  await expect(
    page.getByRole("button", { name: "进入管理后台" }),
  ).toBeVisible();
  expect(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
  ).toBe(0);
});

test("invalid admin login and upstream limit errors remain actionable", async ({
  page,
}) => {
  await fixtures(page);
  await page.goto("./");
  await page.getByLabel("管理密钥", { exact: true }).fill("wrong-key");
  await page.getByRole("button", { name: "进入管理后台" }).click();
  await expect(page.getByRole("alert")).toContainText("unauthorized");
  await page.getByLabel("管理密钥", { exact: true }).fill("test-admin");
  await page.getByRole("button", { name: "进入管理后台" }).click();
  await page.getByRole("link", { name: "在线调试" }).click();
  await page.route("**/v1/chat/completions", (r) =>
    r.fulfill({ status: 429, json: { error: { message: "quota exceeded" } } }),
  );
  await page.getByLabel("用户访问令牌", { exact: true }).fill("test-access");
  await page.getByLabel("我理解真实调用可能产生费用或业务操作").check();
  await page.getByLabel("消息", { exact: true }).fill("hello");
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.getByRole("alert")).toContainText("quota exceeded");
  await expect(page.getByRole("button", { name: "发送请求" })).toBeEnabled();
  await expect(
    page.getByRole("button", { name: "退出", exact: true }),
  ).toBeVisible();
});

test("editing token persists explicit whitelist, QPM and window limits", async ({
  page,
}) => {
  const state = await fixtures(page);
  await signIn(page);
  await page.getByRole("link", { name: "用户与令牌" }).click();
  await page.getByRole("button", { name: "管理令牌", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ has: page.getByRole("cell", { name: "token-1", exact: true }) })
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("QPM 上限").fill("3");
  await dialog.getByLabel("5 小时额度（分）").fill("250");
  await dialog.getByLabel("test-chat", { exact: true }).uncheck();
  await dialog.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "未授权任何模型", exact: true }),
  ).toBeVisible();
  expect(state.tokens[0].qpmLimit).toBe(3);
  expect(state.tokens[0].limits).toEqual([{ limitType: 1, usage: 250 }]);
  expect(state.tokens[0].models).toEqual([]);
});
test("model creation, key replacement form and token quota reset", async ({
  page,
}) => {
  const state = await fixtures(page);
  await signIn(page);
  await page.getByRole("link", { name: "模型管理" }).click();
  await page.getByRole("button", { name: "新建" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("名称", { exact: false }).fill("new-model");
  await dialog.getByLabel("提供商").fill("test");
  await dialog.getByLabel("上游密钥").selectOption("key-1");
  await dialog.getByRole("button", { name: "保存", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "new-model", exact: true }),
  ).toBeVisible();
  expect(state.models).toHaveLength(2);
  await page.getByRole("link", { name: "上游密钥" }).click();
  await expect(
    page.getByRole("heading", { name: "上游密钥", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "编辑", exact: true }).click();
  await expect(dialog.getByLabel("API Key")).toHaveValue("");
  await dialog.getByRole("button", { name: "取消", exact: true }).click();
  await page.getByRole("link", { name: "用户与令牌" }).click();
  await page.getByRole("button", { name: "管理令牌", exact: true }).click();
  await page.getByRole("button", { name: "额度", exact: true }).click();
  await expect(dialog.getByText("¥1.00", { exact: false })).toBeVisible();
  await dialog.getByRole("button", { name: "重置此周期" }).click();
  await dialog.getByRole("button", { name: "确认重置", exact: true }).click();
  await expect(dialog.getByText("¥0.00", { exact: false })).toBeVisible();
});
test("OpenAPI import and log filters/export", async ({ page }) => {
  const state = await fixtures(page);
  await signIn(page);
  await page.getByRole("link", { name: "MCP 管理" }).click();
  await page.getByRole("button", { name: "管理工具" }).click();
  await page.getByRole("button", { name: "导入 OpenAPI", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("或粘贴 OpenAPI JSON").fill(
    JSON.stringify({
      openapi: "3.0.0",
      servers: [{ url: "http://example.test" }],
      paths: {
        "/orders": {
          get: {
            operationId: "get_order",
            responses: { 200: { description: "ok" } },
          },
        },
      },
    }),
  );
  await dialog.getByRole("button", { name: "解析并选择接口" }).click();
  await dialog.getByLabel("/orders", { exact: true }).check();
  await dialog.getByRole("button", { name: "导入 1 个路径" }).click();
  await expect(
    page.getByRole("cell", { name: "get_order", exact: true }),
  ).toBeVisible();
  expect(state.tools).toHaveLength(1);
  await page.getByRole("link", { name: "日志与用量" }).click();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出当前页 CSV" }).click();
  expect((await download).suggestedFilename()).toBe("calls-page-1.csv");
  await page.getByPlaceholder("模型名称，精确匹配").fill("none");
  await page.getByRole("button", { name: "查询", exact: true }).click();
  await expect(page.getByRole("cell", { name: "暂无数据" })).toBeVisible();
});
test("real request wiring for SSE and stateless MCP keeps credentials separate", async ({
  page,
}) => {
  await fixtures(page);
  await page.route("**/v1/chat/completions", async (r) => {
    expect(r.request().headers()["authorization"]).toBe("Bearer test-access");
    expect(r.request().headers()["x-admin-token"]).toBeUndefined();
    await r.fulfill({
      contentType: "text/event-stream",
      body: 'data: {"choices":[{"delta":{"content":"测试回答"}}]}\n\ndata: {"usage":{"total_tokens":12},"choices":[]}\n\ndata: [DONE]\n\n',
    });
  });
  const methods: string[] = [];
  await page.route("**/mcp/orders", async (r) => {
    const req = r.request(),
      body = req.postDataJSON();
    methods.push(body.method);
    expect(req.headers()["mcp-method"]).toBe(body.method);
    expect(req.headers()["mcp-protocol-version"]).toBe("2026-07-28");
    expect(req.headers()["authorization"]).toBe("Bearer test-access");
    expect(req.headers()["x-admin-token"]).toBeUndefined();
    await r.fulfill({
      json: {
        jsonrpc: "2.0",
        id: body.id,
        result:
          body.method === "tools/list"
            ? {
                tools: [{ name: "get_order", inputSchema: { type: "object" } }],
              }
            : { content: [{ type: "text", text: "ok" }] },
      },
    });
  });
  await signIn(page);
  await page.getByRole("link", { name: "在线调试" }).click();
  await page.getByLabel("用户访问令牌", { exact: true }).fill("test-access");
  await page.getByLabel("我理解真实调用可能产生费用或业务操作").check();
  await page.getByLabel("消息", { exact: true }).fill("hello");
  await page.getByRole("button", { name: "发送请求" }).click();
  await expect(page.getByText("测试回答", { exact: true })).toBeVisible();
  await expect(page.getByText("已完成", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "MCP 工具调用", exact: true }).click();
  await page.getByRole("button", { name: "发现服务与工具" }).click();
  await expect(page.getByLabel("选择 MCP 工具", { exact: true })).toHaveValue(
    "get_order",
  );
  await page.getByRole("button", { name: "调用工具", exact: false }).click();
  await page.getByRole("button", { name: "确认调用", exact: true }).click();
  await expect
    .poll(() => methods)
    .toEqual(["server/discover", "tools/list", "tools/call"]);
});
