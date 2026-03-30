import { greet, isValidEmail, capitalize } from "tsLib/utils";

const app = document.getElementById("app")!;

function render(): void {
  app.innerHTML = `
    <h1>Mill Polyglot Frontend</h1>
    <p>This TypeScript frontend imports from <code>tsLib</code> via Mill's <code>moduleDeps</code>,
       bundled with Bun, and served by a Scala/Cask JVM backend.</p>

    <section>
      <h2>Greeting</h2>
      <div class="row">
        <input id="name-input" placeholder="Enter a name" />
        <button id="greet-btn">Greet</button>
      </div>
      <div id="greet-result" class="result"></div>
    </section>

    <section>
      <h2>Email Validator</h2>
      <div class="row">
        <input id="email-input" placeholder="Enter an email" />
        <button id="email-btn">Validate</button>
      </div>
      <div id="email-result" class="result"></div>
    </section>

    <section>
      <h2>Backend API</h2>
      <button id="time-btn">Get Server Time</button>
      <div id="time-result" class="result"></div>
    </section>
  `;

  document.getElementById("greet-btn")!.addEventListener("click", () => {
    const name = (document.getElementById("name-input") as HTMLInputElement).value;
    document.getElementById("greet-result")!.textContent = greet(capitalize(name || "World"));
  });

  document.getElementById("email-btn")!.addEventListener("click", () => {
    const email = (document.getElementById("email-input") as HTMLInputElement).value;
    const valid = isValidEmail(email);
    const el = document.getElementById("email-result")!;
    el.textContent = valid ? `${email} is valid` : `${email} is not valid`;
    el.className = "result " + (valid ? "valid" : "invalid");
  });

  document.getElementById("time-btn")!.addEventListener("click", async () => {
    const res = await fetch("/api/time");
    const data = await res.json();
    document.getElementById("time-result")!.textContent = `Server time: ${data.time}`;
  });
}

render();
