import { expect, test } from "bun:test";

test("bunTestEnv reaches the bun test process", () => {
  expect(process.env.BUN_TEST_MARKER).toBe("set");
});
