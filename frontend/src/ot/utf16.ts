export function isUtf16Boundary(document: string, position: number): boolean {
  if (!Number.isSafeInteger(position) || position < 0 || position > document.length) {
    return false;
  }

  if (position === 0 || position === document.length) {
    return true;
  }

  return !(
    isHighSurrogate(document.charCodeAt(position - 1))
    && isLowSurrogate(document.charCodeAt(position))
  );
}

function isHighSurrogate(codeUnit: number): boolean {
  return codeUnit >= 0xd800 && codeUnit <= 0xdbff;
}

function isLowSurrogate(codeUnit: number): boolean {
  return codeUnit >= 0xdc00 && codeUnit <= 0xdfff;
}
