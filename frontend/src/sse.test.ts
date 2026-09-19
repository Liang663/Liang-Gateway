import { describe, it, expect } from "vitest";
import { sseParser, readSse } from "./sse";
describe("SSE framing", () => {
  it("handles every possible network chunk boundary including split CRLF", () => {
    const source =
      ':comment\r\ndata: {"content":"你好"}\r\n\r\ndata: [DONE]\r\n\r\n';
    for (let cut = 0; cut <= source.length; cut++) {
      const result: string[] = [];
      const parse = sseParser((x) => result.push(x));
      parse(source.slice(0, cut));
      parse(source.slice(cut));
      parse("", true);
      expect(result).toEqual(['{"content":"你好"}', "[DONE]"]);
    }
  });
  it("handles multiline data, LF, CR, comments and empty events", () => {
    const result: string[] = [];
    const parse = sseParser((x) => result.push(x));
    parse("data: first\ndata:second\n\n:comment\n\nevent:ping\ndata:ok\r\r");
    parse("", true);
    expect(result).toEqual(["first\nsecond", "ok"]);
  });
  it("does not dispatch an incomplete event at EOF", () => {
    const result: string[] = [];
    const parse = sseParser((x) => result.push(x));
    parse("data: incomplete");
    parse("", true);
    expect(result).toEqual([]);
  });
  it("decodes multibyte UTF8 split across chunks", async () => {
    const bytes = new TextEncoder().encode("data: 中文\n\ndata: [DONE]\n\n"),
      result: string[] = [];
    const response = new Response(
      new ReadableStream({
        start(c) {
          for (const b of bytes) c.enqueue(new Uint8Array([b]));
          c.close();
        },
      }),
      { headers: { "Content-Type": "text/event-stream" } },
    );
    await readSse(response, (x) => result.push(x));
    expect(result).toEqual(["中文", "[DONE]"]);
  });
  it("rejects non-stream responses", async () => {
    await expect(readSse(new Response("{}"), () => {})).rejects.toThrow("SSE");
  });
});
