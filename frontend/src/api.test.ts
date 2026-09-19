import { afterEach, describe, it, expect, vi } from "vitest";
import { admin, login, logout, dataRequest, authenticated, query } from "./api";
import { mcpRequest, MCP_VERSION } from "./mcp";
afterEach(() => {
  logout();
  vi.unstubAllGlobals();
});
describe("credential isolation", () => {
  it("retains JSON-RPC error code and supported protocol versions on HTTP400", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({
              error: {
                code: -32600,
                message: "Unsupported version",
                data: { supported: [MCP_VERSION] },
              },
            }),
            { status: 400 },
          ),
        ),
    );
    await expect(
      mcpRequest("orders", "test", "server/discover"),
    ).rejects.toThrow(
      `MCP -32600: Unsupported version · {"supported":["${MCP_VERSION}"]}`,
    );
  });
  it("validates admin key then confines it to admin requests", async () => {
    const fetcher = vi
      .fn()
      .mockImplementation(() =>
        Promise.resolve(new Response("[]", { status: 200 })),
      );
    vi.stubGlobal("fetch", fetcher);
    await login("admin-test");
    await admin("/admin/users");
    await dataRequest("/v1/chat/completions", "access-test", {});
    expect(fetcher.mock.calls[1]![1].headers).toEqual({
      "X-Admin-Token": "admin-test",
    });
    expect(fetcher.mock.calls[2]![1].headers).toEqual({
      "Content-Type": "application/json",
      Authorization: "Bearer access-test",
    });
    await expect(admin("/v1/usage/quota")).rejects.toThrow("路径");
  });
  it("clears the admin session on unauthorized", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(new Response("[]"))
      .mockResolvedValueOnce(
        new Response('{"error":{"message":"unauthorized"}}', { status: 401 }),
      );
    vi.stubGlobal("fetch", fetcher);
    await login("admin-test");
    await expect(admin("/admin/users")).rejects.toThrow("unauthorized");
    expect(authenticated()).toBe(false);
  });
  it("uses stateless MCP discovery/call protocol headers", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response('{"result":{"tools":[]}}'));
    vi.stubGlobal("fetch", fetcher);
    await mcpRequest("orders", "user-token", "tools/call", {
      name: "get_order",
      arguments: { id: "1" },
    });
    const [path, options] = fetcher.mock.calls[0]!;
    expect(path).toBe("/mcp/orders");
    expect(options.headers["MCP-Protocol-Version"]).toBe(MCP_VERSION);
    expect(options.headers["Mcp-Name"]).toBe("get_order");
    expect(options.headers["X-Admin-Token"]).toBeUndefined();
    expect(JSON.parse(options.body).params._meta.protocolVersion).toBe(
      MCP_VERSION,
    );
  });
  it("preserves false and zero query parameters", () => {
    expect(query({ a: false, b: 0, c: "", d: null })).toBe("a=false&b=0");
  });
});
