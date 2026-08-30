import type { TextOperation } from "./types";
import { isUtf16Boundary } from "../ot/utf16";

/**
 * Extracts UTF-16 text operations (INSERT / DELETE) by comparing oldText and newText.
 *
 * Algorithm:
 * 1. Find common UTF-16 prefix length p.
 * 2. Find common UTF-16 suffix length s (bounded so prefix and suffix do not overlap).
 * 3. Any remaining length in oldText is a DELETE at position p.
 * 4. Any remaining text in newText is an INSERT at position p.
 */
export function extractOperations(oldText: string, newText: string): TextOperation[] {
  if (oldText === newText) {
    return [];
  }

  let p = 0;
  const minLen = Math.min(oldText.length, newText.length);
  while (p < minLen && oldText.charCodeAt(p) === newText.charCodeAt(p)) {
    p++;
  }
  while (p > 0 && (!isUtf16Boundary(oldText, p) || !isUtf16Boundary(newText, p))) {
    p--;
  }

  let s = 0;
  const maxSuffix = Math.min(oldText.length - p, newText.length - p);
  while (
    s < maxSuffix &&
    oldText.charCodeAt(oldText.length - 1 - s) === newText.charCodeAt(newText.length - 1 - s)
  ) {
    s++;
  }
  while (
    s > 0
    && (
      !isUtf16Boundary(oldText, oldText.length - s)
      || !isUtf16Boundary(newText, newText.length - s)
    )
  ) {
    s--;
  }

  const ops: TextOperation[] = [];

  const deleteLength = oldText.length - p - s;
  if (deleteLength > 0) {
    ops.push({
      kind: "DELETE",
      position: p,
      length: deleteLength,
    });
  }

  const insertText = newText.slice(p, newText.length - s);
  if (insertText.length > 0) {
    ops.push({
      kind: "INSERT",
      position: p,
      text: insertText,
    });
  }

  return ops;
}
