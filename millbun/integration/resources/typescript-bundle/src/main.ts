import { readFileSync } from "node:fs";

const configUrl = new URL("./resources/nested/config.json", import.meta.url);
const config = JSON.parse(readFileSync(configUrl, "utf8")) as { message: string };

console.log(config.message);
