/** Incremental SSE framer. Network chunks may split CRLF, UTF-8 characters or JSON. */
export function sseParser(onData: (data: string) => void) {
  let pending = "",
    lines: string[] = [];
  const line = (text: string) => {
    if (text === "") {
      if (lines.length) onData(lines.join("\n"));
      lines = [];
    } else if (text.startsWith("data:"))
      lines.push(text.slice(5).replace(/^ /, ""));
  };
  return (chunk: string, end = false) => {
    pending += chunk;
    let start = 0;
    for (let i = 0; i < pending.length; i++) {
      const ch = pending[i];
      if (ch !== "\n" && ch !== "\r") continue;
      if (ch === "\r" && i === pending.length - 1 && !end) break;
      line(pending.slice(start, i));
      if (ch === "\r" && pending[i + 1] === "\n") i++;
      start = i + 1;
    }
    pending = pending.slice(start);
    // An unterminated event is incomplete and is deliberately not dispatched.
    if (end) {
      pending = "";
      lines = [];
    }
  };
}
export async function readSse(
  response: Response,
  onData: (data: string) => void,
) {
  if (!response.headers.get("Content-Type")?.includes("text/event-stream"))
    throw new Error("上游没有返回 SSE 流");
  if (!response.body) throw new Error("响应流为空");
  const reader = response.body.getReader(),
    decoder = new TextDecoder(),
    parse = sseParser(onData);
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        parse(decoder.decode(), true);
        break;
      }
      parse(decoder.decode(value, { stream: true }));
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
