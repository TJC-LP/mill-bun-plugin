import { expect, test } from "bun:test";
import { app } from "../src/main";

test("the root route responds", async () => {
  const response = await app.request("/");
  expect(await response.text()).toBe("Hello from mill-bun!");
});
