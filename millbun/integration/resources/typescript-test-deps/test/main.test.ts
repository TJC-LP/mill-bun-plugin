import { expect, test } from "bun:test";
import isOdd from "is-odd";
import { checkEven } from "../src/main";

test("checkEven", () => {
  expect(checkEven(4)).toBe(true);
  expect(checkEven(3)).toBe(false);
});

test("test-only dep is available", () => {
  expect(isOdd(3)).toBe(true);
});
