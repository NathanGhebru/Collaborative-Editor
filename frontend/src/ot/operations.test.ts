import { describe, expect, it } from "vitest";
import {
  OtValidationError,
  applyOperation,
  assertOperationValidForDocument,
  flattenOperations,
} from "./operations";
import { isUtf16Boundary } from "./utf16";

describe("UTF-16 boundaries and operation validation", () => {
  it("accepts ASCII and BMP code-unit boundaries", () => {
    expect(isUtf16Boundary("ASCII", 3)).toBe(true);
    expect(isUtf16Boundary("A終B", 2)).toBe(true);

    expect(applyOperation("A終B", { kind: "INSERT", position: 2, text: "界" })).toBe("A終界B");
    expect(applyOperation("A終B", { kind: "DELETE", position: 1, length: 1 })).toBe("AB");
  });

  it("counts emoji as two UTF-16 code units while accepting boundaries around the pair", () => {
    const document = "A😀B";
    expect(document.length).toBe(4);
    expect(isUtf16Boundary(document, 1)).toBe(true);
    expect(isUtf16Boundary(document, 2)).toBe(false);
    expect(isUtf16Boundary(document, 3)).toBe(true);
    expect(applyOperation(document, { kind: "DELETE", position: 1, length: 2 })).toBe("AB");
  });

  it("rejects insert positions and delete endpoints that split a surrogate pair", () => {
    const document = "A😀B";

    expectValidationCode(
      () => assertOperationValidForDocument(document, { kind: "INSERT", position: 2, text: "X" }),
      "INVALID_POSITION",
    );
    expectValidationCode(
      () => assertOperationValidForDocument(document, { kind: "DELETE", position: 1, length: 1 }),
      "INVALID_POSITION",
    );
    expectValidationCode(
      () => assertOperationValidForDocument(document, { kind: "DELETE", position: 2, length: 1 }),
      "INVALID_POSITION",
    );
  });

  it("rejects malformed positions, lengths, empty inserts, and out-of-range operations", () => {
    expectValidationCode(
      () => applyOperation("abc", { kind: "INSERT", position: 1.5, text: "X" }),
      "INVALID_POSITION",
    );
    expectValidationCode(
      () => applyOperation("abc", { kind: "INSERT", position: 1, text: "" }),
      "INVALID_OPERATION",
    );
    expectValidationCode(
      () => applyOperation("abc", { kind: "DELETE", position: 1, length: 0 }),
      "INVALID_LENGTH",
    );
    expectValidationCode(
      () => applyOperation("abc", { kind: "DELETE", position: 2, length: 2 }),
      "INVALID_LENGTH",
    );
    expectValidationCode(
      () => applyOperation("abc", { kind: "REPLACE" } as never),
      "INVALID_OPERATION",
    );
    expectValidationCode(
      () => applyOperation("abc", null as never),
      "INVALID_OPERATION",
    );
    expectValidationCode(
      () => applyOperation("abc", {
        kind: "GROUP",
        operations: [{ kind: "INSERT", position: 0, text: "X" }],
      }),
      "INVALID_OPERATION",
    );
  });

  it("applies GROUP primitives sequentially and NO_OP as identity", () => {
    expect(
      applyOperation("abcdef", {
        kind: "GROUP",
        operations: [
          { kind: "DELETE", position: 1, length: 2 },
          { kind: "INSERT", position: 2, text: "XY" },
        ],
      }),
    ).toBe("adXYef");
    expect(applyOperation("unchanged", { kind: "NO_OP" })).toBe("unchanged");
  });

  it("flattens effective operations to NO_OP, one primitive, or one flat GROUP", () => {
    const insert = { kind: "INSERT", position: 0, text: "A" } as const;
    const deletion = { kind: "DELETE", position: 1, length: 1 } as const;

    expect(flattenOperations([{ kind: "NO_OP" }])).toEqual({ kind: "NO_OP" });
    expect(flattenOperations([{ kind: "NO_OP" }, insert])).toEqual(insert);
    expect(
      flattenOperations([
        { kind: "GROUP", operations: [insert, deletion] },
        { kind: "NO_OP" },
      ]),
    ).toEqual({ kind: "GROUP", operations: [insert, deletion] });
  });

  it("preserves text exactly without Unicode normalization", () => {
    const decomposed = "e\u0301";
    const composed = "é";
    const result = applyOperation("", { kind: "INSERT", position: 0, text: decomposed });

    expect(result).toBe(decomposed);
    expect(result).not.toBe(composed);
    expect(result.length).toBe(2);
  });
});

function expectValidationCode(
  action: () => unknown,
  expectedCode: OtValidationError["code"],
): void {
  try {
    action();
    throw new Error("Expected operation validation to fail.");
  } catch (error) {
    expect(error).toBeInstanceOf(OtValidationError);
    expect((error as OtValidationError).code).toBe(expectedCode);
  }
}
