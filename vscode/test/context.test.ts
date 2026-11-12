// Contexto del Mentor en VS Code: encontrar la conversación de Claude Code que habla del archivo y leer AGENTS.md.
// Usa un HOME y un proyecto falsos en una carpeta temporal.
import { test } from "node:test";
import assert from "node:assert/strict";
import * as fs from "fs";
import * as os from "os";
import * as path from "path";

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mentor-ctx-"));
process.env.HOME = tmp; // os.homedir() lo respeta: las conversaciones se buscan en el HOME falso
const project = path.join(tmp, "proyecto");
fs.mkdirSync(project);

// eslint-disable-next-line @typescript-eslint/no-require-imports
const { Sessions, readRules } = require("../src/context") as typeof import("../src/context");
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { core } = require("../src/core") as typeof import("../src/core");

const line = (type: string, text: string, at: string) =>
  JSON.stringify({ type, timestamp: at, message: { role: type, content: type === "user" ? text : [{ type: "text", text }] } });

function session(name: string, lines: string[], ageMs = 0) {
  const dir = path.join(tmp, ".claude", "projects", core.projectDirName(project));
  fs.mkdirSync(dir, { recursive: true });
  const f = path.join(dir, name);
  fs.writeFileSync(f, lines.join("\n") + "\n");
  const t = new Date(Date.now() - ageMs);
  fs.utimesSync(f, t, t);
  return f;
}

test("elige la conversación que habla del archivo y arma el extracto con el plan", async () => {
  session("otra.jsonl", [line("user", "Arregla el login", "t1"), line("assistant", "Listo LoginScreen", "t2")], 0);
  session("plan.jsonl", [
    line("user", "Dame el plan de la campana", "t1"),
    line("assistant", "## Paso 5.1: MacoTopBar acepta null\n[MacoTopBar.kt:1-6](file:///p/MacoTopBar.kt#L1-L6)", "t2"),
  ], 60_000);
  const found = await new Sessions(project).find("MacoTopBar.kt");
  assert.ok(found, "encontró una conversación");
  assert.equal(path.basename(found!.file), "plan.jsonl");
  assert.ok(found!.excerpt.includes("## Paso 5.1"));
  assert.equal(found!.automatic, true);
});

test("ignora conversaciones de hace más de 3 días", async () => {
  const old = session("vieja.jsonl", [line("user", "x", "t"), line("assistant", "VentanaVieja", "t")], 5 * 24 * 3600 * 1000);
  const list = await new Sessions(project).candidates();
  assert.ok(!list.includes(old));
});

// ---------- planes de Antigravity ----------

function brainPlan(id: string, name: string, text: string, ageMs = 0) {
  const dir = path.join(tmp, ".gemini", "antigravity", "brain", id);
  fs.mkdirSync(dir, { recursive: true });
  const f = path.join(dir, name);
  fs.writeFileSync(f, text);
  const t = new Date(Date.now() - ageMs);
  fs.utimesSync(f, t, t);
  return f;
}

const agPlan = [
  "# Login con JWT",
  "## Proposed Changes",
  "#### [MODIFY] [AuthService.kt](file:///p/AuthService.kt)",
  "```diff",
  " fun login() {",
  "-    return tokens.create(email)",
  "+    // TODO 1: valida la contraseña",
  " }",
  "```",
].join("\n");

test("un plan de Antigravity más nuevo que la conversación de Claude es el que manda", async () => {
  brainPlan("a1", "implementation_plan.md", agPlan, 0);
  brainPlan("a1", "walkthrough.md", agPlan.replace("Login", "Resumen"), 0); // lo ya hecho: no es un plan
  const sessions = new Sessions(project);
  const steps = await sessions.latestSteps("AuthService.kt");
  assert.equal(steps?.kind, "Antigravity");
  assert.equal(steps?.title, "Login con JWT");
  const found = await sessions.find("AuthService.kt");
  assert.ok(found!.excerpt.startsWith("IA (plan de Antigravity: «Login con JWT»)"));
  assert.equal(found!.lastRequest, "Antigravity · Login con JWT");
});

test("para un archivo del que Antigravity no habla, sigue la conversación de Claude", async () => {
  const steps = await new Sessions(project).latestSteps("MacoTopBar.kt");
  assert.equal(steps?.kind, "Claude Code");
  assert.ok(steps!.markdown.includes("## Paso 5.1"));
});

test("ignora planes de Antigravity de hace más de una semana", async () => {
  brainPlan("viejo", "implementation_plan.md", agPlan.replace("AuthService", "ViejoService"), 9 * 24 * 3600 * 1000);
  const plans = await new Sessions(project).antigravityPlans();
  assert.ok(!plans.some((p) => p.text.includes("ViejoService")));
});

test("los pasos de un plan de Antigravity salen del core con su TODO", () => {
  const steps = JSON.parse(core.raw.steps(agPlan, "/p"));
  assert.equal(steps[0].title, "Modificar AuthService.kt");
  assert.deepEqual(steps[0].edits[0].todos.map((t: { label: string }) => t.label), ["TODO 1"]);
  const status = JSON.parse(core.raw.editStatus(agPlan, "/p", 0, 0, "fun login() {\n    return tokens.create(email)\n}", false, false));
  assert.equal(status.todos[0].state, "MISSING");
  assert.equal(status.canPlaceSkeleton, true);
});

test("lee las reglas de AGENTS.md y salta un CLAUDE.md que solo apunta a él", async () => {
  fs.writeFileSync(path.join(project, "CLAUDE.md"), "@AGENTS.md\n");
  assert.equal(await readRules(project), null);
  fs.writeFileSync(path.join(project, "AGENTS.md"), "# Reglas\n## Arquitectura\n- KMP\n## Git\n- nada de push");
  const rules = await readRules(project);
  assert.equal(rules?.source, "AGENTS.md");
  assert.ok(rules!.text.includes("KMP") && !rules!.text.includes("push"));
});
