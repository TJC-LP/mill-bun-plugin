import { expect, test } from "bun:test";

import { greeting } from "../src/main";

test("greeting", () => {
  expect(greeting("Bun")).toBe("Hello, Bun!");
});
