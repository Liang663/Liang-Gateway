import { ApiError, dataRequest } from "./api";
export const MCP_VERSION = "2026-07-28";
let requestId = 0;
export async function mcpRequest(
  path: string,
  token: string,
  method: string,
  params: Record<string, unknown> = {},
  signal?: AbortSignal,
) {
  const headers: Record<string, string> = {
    Accept: "application/json, text/event-stream",
    "MCP-Protocol-Version": MCP_VERSION,
    "Mcp-Method": method,
  };
  if (method === "tools/call") headers["Mcp-Name"] = String(params.name);
  let response: Response;
  try {
    response = await dataRequest(
      `/mcp/${encodeURIComponent(path)}`,
      token,
      {
        jsonrpc: "2.0",
        id: ++requestId,
        method,
        params: { ...params, _meta: { protocolVersion: MCP_VERSION } },
      },
      signal,
      headers,
    );
  } catch (error) {
    if (error instanceof ApiError) {
      const rpc = (
        error.body as {
          error?: { code?: number; message?: string; data?: unknown };
        }
      )?.error;
      if (rpc?.code != null)
        throw new Error(
          `MCP ${rpc.code}: ${rpc.message}${rpc.data ? " · " + JSON.stringify(rpc.data) : ""}`,
        );
    }
    throw error;
  }
  const data = await response.json();
  if (data.error)
    throw new Error(`MCP ${data.error.code}: ${data.error.message}`);
  return data.result;
}
