import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { renderHook, act } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { extractOperations } from "./operationExtractor";
import { useLocalEditor } from "./useLocalEditor";
import { PlainTextEditor, formatOperation } from "./PlainTextEditor";

describe("extractOperations UTF-16 diffing", () => {
  it("returns an empty array when oldText and newText are identical", () => {
    expect(extractOperations("hello", "hello")).toEqual([]);
    expect(extractOperations("", "")).toEqual([]);
  });

  it("extracts a single character insertion at the end", () => {
    expect(extractOperations("hello", "hello!")).toEqual([
      { kind: "INSERT", position: 5, text: "!" },
    ]);
  });

  it("extracts an insertion at index 0", () => {
    expect(extractOperations("world", "hello world")).toEqual([
      { kind: "INSERT", position: 0, text: "hello " },
    ]);
  });

  it("extracts a single character deletion", () => {
    expect(extractOperations("hello!", "hello")).toEqual([
      { kind: "DELETE", position: 5, length: 1 },
    ]);
  });

  it("extracts a deletion at index 0", () => {
    expect(extractOperations("hello world", "world")).toEqual([
      { kind: "DELETE", position: 0, length: 6 },
    ]);
  });

  it("extracts a selection replacement as DELETE then INSERT", () => {
    expect(extractOperations("hello world", "hello earth")).toEqual([
      { kind: "DELETE", position: 6, length: 5 },
      { kind: "INSERT", position: 6, text: "earth" },
    ]);
  });

  it("handles multi-line text insertions and deletions", () => {
    const oldText = "line 1\nline 2";
    const newText = "line 1\nline 1.5\nline 2";
    expect(extractOperations(oldText, newText)).toEqual([
      { kind: "INSERT", position: 12, text: "1.5\nline " },
    ]);
  });

  it("correctly handles UTF-16 surrogate pairs (emojis)", () => {
    // "🚀" is 2 UTF-16 code units (0xD83D 0xDE80)
    const oldText = "Rocket 🚀";
    const newText = "Rocket 🚀 launch!";
    expect(extractOperations(oldText, newText)).toEqual([
      { kind: "INSERT", position: 9, text: " launch!" },
    ]);
  });
});

describe("useLocalEditor hook", () => {
  it("initializes with provided content and clean isDirty state", () => {
    const { result } = renderHook(() => useLocalEditor({ initialContent: "Initial" }));
    expect(result.current.content).toBe("Initial");
    expect(result.current.isDirty).toBe(false);
    expect(result.current.extractedOperations).toEqual([]);
    expect(result.current.status).toBe("idle");
  });

  it("updates content, tracks operations, and sets isDirty to true", () => {
    const { result } = renderHook(() => useLocalEditor({ initialContent: "Hello" }));
    act(() => {
      result.current.updateContent("Hello World");
    });
    expect(result.current.content).toBe("Hello World");
    expect(result.current.isDirty).toBe(true);
    expect(result.current.status).toBe("editing");
    expect(result.current.extractedOperations).toEqual([
      { kind: "INSERT", position: 5, text: " World" },
    ]);
    expect(result.current.lastOperation).toEqual({
      kind: "INSERT",
      position: 5,
      text: " World",
    });
  });

  it("resets content snapshot cleanly", () => {
    const { result } = renderHook(() => useLocalEditor({ initialContent: "v1" }));
    act(() => {
      result.current.updateContent("v1 edited");
    });
    expect(result.current.isDirty).toBe(true);

    act(() => {
      result.current.resetContent("v2");
    });
    expect(result.current.content).toBe("v2");
    expect(result.current.isDirty).toBe(false);
    expect(result.current.extractedOperations).toEqual([]);
  });
});

describe("PlainTextEditor component", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders initial content and saved status badge", () => {
    render(<PlainTextEditor initialContent="Initial doc text" />);
    expect(screen.getByRole("status")).toHaveTextContent("Saved");
    expect(screen.getByLabelText("Document text editor")).toHaveValue("Initial doc text");
  });

  it("updates text and displays unsaved changes badge on typing", () => {
    const onContentChange = vi.fn();
    const onOperationExtracted = vi.fn();
    render(
      <PlainTextEditor
        initialContent="Hello"
        onContentChange={onContentChange}
        onOperationExtracted={onOperationExtracted}
      />,
    );

    const textarea = screen.getByLabelText("Document text editor");
    fireEvent.change(textarea, { target: { value: "Hello World" } });

    expect(screen.getByRole("status")).toHaveTextContent("Unsaved local changes");
    expect(onContentChange).toHaveBeenLastCalledWith("Hello World", true);
    expect(onOperationExtracted).toHaveBeenCalledWith({
      kind: "INSERT",
      position: 5,
      text: " World",
    });
  });

  it("formats operation log output correctly", () => {
    expect(formatOperation(null)).toBe("None");
    expect(formatOperation({ kind: "INSERT", position: 10, text: "abc" })).toBe(
      'INSERT @ pos 10: "abc"',
    );
    expect(formatOperation({ kind: "DELETE", position: 5, length: 3 })).toBe(
      "DELETE @ pos 5: 3 code units",
    );
  });

  it("respects readOnly state", () => {
    render(<PlainTextEditor initialContent="Read only text" readOnly={true} />);
    const textarea = screen.getByLabelText("Document text editor");
    expect(textarea).toHaveAttribute("readonly");
    expect(screen.getByText("Read Only")).toBeInTheDocument();
  });
});
