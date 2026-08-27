import { Hono } from "hono";

export const app = new Hono();
app.get("/", (c) => c.text("Hello from mill-bun!"));

console.log("hono app ready");
