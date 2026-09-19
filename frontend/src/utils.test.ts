import { it, expect } from "vitest";
import { csvCell, integer, parseJson, shanghaiOffset } from "./utils";
it("escapes spreadsheet formula inputs and quotes", () => {
  expect(csvCell("=SUM(A1)")).toBe('"\'=SUM(A1)"');
  expect(csvCell('a"b')).toBe('"a""b"');
  expect(csvCell(" @cmd")).toBe('"\' @cmd"');
});
it("validates integer money without silently rounding", () => {
  expect(integer("123", "金额")).toBe(123);
  for (const v of ["", -1, 1.1, "x", Number.MAX_SAFE_INTEGER + 1])
    expect(() => integer(v, "金额")).toThrow();
});
it("validates JSON shapes", () => {
  expect(parseJson("{}", "object", "参数")).toEqual({});
  expect(() => parseJson("[]", "object", "参数")).toThrow();
  expect(() => parseJson("null", "array", "参数")).toThrow();
});
it("sends wall clock inputs with Shanghai offset", () => {
  expect(shanghaiOffset("2026-09-18T12:00")).toBe("2026-09-18T12:00:00+08:00");
});
