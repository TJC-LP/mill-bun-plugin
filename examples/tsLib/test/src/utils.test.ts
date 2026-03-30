import { describe, test, expect } from "bun:test";
import { greet, formatDate, isValidEmail, capitalize } from "tsLib/utils";

describe("greet", () => {
  test("returns a greeting with the name", () => {
    expect(greet("World")).toBe("Hello, World! Built with Mill + Bun.");
  });

  test("handles empty string", () => {
    expect(greet("")).toBe("Hello, ! Built with Mill + Bun.");
  });
});

describe("formatDate", () => {
  test("formats a date as YYYY-MM-DD", () => {
    expect(formatDate(new Date("2024-01-15T10:30:00Z"))).toBe("2024-01-15");
  });

  test("uses the date's calendar fields instead of UTC serialization", () => {
    class GuardedDate extends Date {
      override toISOString(): string {
        throw new Error("formatDate should not call toISOString");
      }
    }

    expect(formatDate(new GuardedDate(2024, 0, 15, 23, 30))).toBe("2024-01-15");
  });
});

describe("isValidEmail", () => {
  test("accepts valid emails", () => {
    expect(isValidEmail("user@example.com")).toBe(true);
    expect(isValidEmail("a.b@c.co")).toBe(true);
  });

  test("rejects invalid emails", () => {
    expect(isValidEmail("invalid")).toBe(false);
    expect(isValidEmail("@no-local.com")).toBe(false);
    expect(isValidEmail("no-domain@")).toBe(false);
  });
});

describe("capitalize", () => {
  test("capitalizes the first letter", () => {
    expect(capitalize("hello")).toBe("Hello");
  });

  test("handles empty string", () => {
    expect(capitalize("")).toBe("");
  });

  test("leaves already-capitalized strings alone", () => {
    expect(capitalize("Hello")).toBe("Hello");
  });
});
