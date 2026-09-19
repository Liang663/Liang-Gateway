export const money = (fen: unknown) =>
  `¥${(Number(fen || 0) / 100).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
export const number = (value: unknown) =>
  Number(value || 0).toLocaleString("zh-CN");
export const date = (value: unknown) =>
  value
    ? String(value)
        .replace("T", " ")
        .replace(/\.\d+$/, "")
    : "—";
export function integer(value: unknown, label: string) {
  const n = Number(value);
  if (value === "" || !Number.isSafeInteger(n) || n < 0)
    throw new Error(`${label}需要填写非负整数`);
  return n;
}
export function parseJson(
  value: unknown,
  shape: "object" | "array",
  label: string,
): any {
  let parsed: unknown;
  try {
    parsed = JSON.parse(String(value));
  } catch {
    throw new Error(`${label}不是有效的 JSON`);
  }
  if (
    !parsed ||
    (shape === "array"
      ? !Array.isArray(parsed)
      : typeof parsed !== "object" || Array.isArray(parsed))
  )
    throw new Error(`${label}需要 JSON ${shape === "array" ? "数组" : "对象"}`);
  return parsed;
}
export function csvCell(value: unknown) {
  let text = value == null ? "" : String(value);
  if (/^[\s]*[=+@-]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}
export function exportCsv(rows: Record<string, unknown>[], name: string) {
  if (!rows.length) return;
  const keys = Object.keys(rows[0]!);
  const csv =
    "\uFEFF" +
    [
      keys.map(csvCell).join(","),
      ...rows.map((row) => keys.map((key) => csvCell(row[key])).join(",")),
    ].join("\r\n");
  const url = URL.createObjectURL(
    new Blob([csv], { type: "text/csv;charset=utf-8" }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export const shanghaiOffset = (value: string) =>
  value ? `${value.length === 16 ? value + ":00" : value}+08:00` : undefined;
