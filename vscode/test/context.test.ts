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

// Carpeta de Copilot de este proyecto (en la extensión real la da el editor) y el plan pegado.
const copilotDir = path.join(tmp, "Code", "User", "workspaceStorage", "abc", "chatSessions");
let pasted: string | null = null;
const sessions = () => new Sessions(project, copilotDir, () => pasted);

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
  const found = await sessions().find("MacoTopBar.kt");
  assert.ok(found, "encontró una conversación");
  assert.equal(path.basename(found!.file), "plan.jsonl");
  assert.ok(found!.excerpt.includes("## Paso 5.1"));
  assert.equal(found!.automatic, true);
});

test("ignora conversaciones de hace más de 3 días", async () => {
  const old = session("vieja.jsonl", [line("user", "x", "t"), line("assistant", "VentanaVieja", "t")], 5 * 24 * 3600 * 1000);
  const list = await sessions().candidates();
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
  const s = sessions();
  const steps = await s.latestSteps("AuthService.kt");
  assert.equal(steps?.kind, "Antigravity");
  assert.equal(steps?.title, "Login con JWT");
  const found = await s.find("AuthService.kt");
  assert.ok(found!.excerpt.startsWith("IA (plan de Antigravity: «Login con JWT»)"));
  // El origen va aparte (con su ícono en el panel); el nombre es el título del plan.
  assert.equal(found!.source, "Antigravity");
  assert.equal(found!.lastRequest, "Login con JWT");
});

test("para un archivo del que Antigravity no habla, sigue la conversación de Claude", async () => {
  const steps = await sessions().latestSteps("MacoTopBar.kt");
  assert.equal(steps?.kind, "Claude Code");
  assert.ok(steps!.markdown.includes("## Paso 5.1"));
});

test("ignora planes de Antigravity de hace más de una semana", async () => {
  brainPlan("viejo", "implementation_plan.md", agPlan.replace("AuthService", "ViejoService"), 9 * 24 * 3600 * 1000);
  const plans = await sessions().antigravityPlans();
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

// ---------- GitHub Copilot, conversaciones de Antigravity y plan pegado ----------

test("lee la conversación de GitHub Copilot de este proyecto y sus pasos", async () => {
  fs.mkdirSync(copilotDir, { recursive: true });
  fs.writeFileSync(path.join(copilotDir, "c1.json"), JSON.stringify({
    version: 3, responderUsername: "GitHub Copilot",
    requests: [{ message: { text: "Dame los pasos del perfil" }, timestamp: Date.now(), response: [{ value: "## Paso 1: ProfileScreen muestra el nombre\nUsa el estado." }] }],
  }));
  const s = sessions();
  const steps = await s.latestSteps("ProfileScreen.kt");
  assert.equal(steps?.kind, "GitHub Copilot");
  assert.ok(steps!.markdown.includes("## Paso 1"));
  const found = await s.find("ProfileScreen.kt");
  assert.equal(found?.source, "GitHub Copilot");
  assert.ok(s.lastSearch.copilot >= 1);
});

test("lee las conversaciones de Antigravity que hablan de este proyecto, no las de otro", async () => {
  const transcript = (id: string, folder: string, answer: string) => {
    const dir = path.join(tmp, ".gemini", "antigravity-ide", "brain", id, ".system_generated", "logs");
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, "transcript.jsonl"), [
      JSON.stringify({ type: "USER_INPUT", created_at: new Date().toISOString(), content: `<USER_REQUEST>\nPlan del carrito\n</USER_REQUEST>\n<ADDITIONAL_METADATA>\nActive Document: ${folder}/CartScreen.kt\n</ADDITIONAL_METADATA>` }),
      JSON.stringify({ type: "PLANNER_RESPONSE", created_at: new Date().toISOString(), content: answer }),
    ].join("\n"));
  };
  transcript("aqui", project, "## Paso 1: CartScreen suma el total");
  transcript("otro", path.join(tmp, "otro-proyecto"), "## Paso 1: CartScreen de otro proyecto");
  const s = sessions();
  const found = await s.find("CartScreen.kt");
  assert.equal(found?.source, "Antigravity");
  assert.ok(found!.excerpt.includes("suma el total") && !found!.excerpt.includes("otro proyecto"));
  assert.equal(s.lastSearch.antigravity, 1, "solo la de este proyecto");
});

test("el plan pegado manda cuando lo eliges, y sus pasos funcionan", async () => {
  pasted = "# Plan de Gemini\n## Paso 1: LoginScreen valida el correo\n[LoginScreen.kt:1](file:///p/LoginScreen.kt#L1)";
  const s = sessions();
  s.choose(core.raw.pastedPath());
  const found = await s.find("LoginScreen.kt");
  assert.equal(found?.source, "Plan pegado");
  assert.equal(found?.lastRequest, "Plan de Gemini");
  assert.equal((await s.latestSteps("LoginScreen.kt"))?.kind, "Plan pegado");
  pasted = null;
});

test("retomar un proyecto: sin conversaciones recientes, se ofrece la última con su edad, y se puede usar igual", async () => {
  const viejo = path.join(tmp, "viejo");
  fs.mkdirSync(viejo);
  const dir = path.join(tmp, ".claude", "projects", core.projectDirName(viejo));
  fs.mkdirSync(dir, { recursive: true });
  const f = path.join(dir, "s.jsonl");
  fs.writeFileSync(f, [line("user", "Dame el plan de cotizaciones", "t1"), line("assistant", "## Paso 1: QuoteScreen arma la cotización", "t2")].join("\n"));
  const t = new Date(Date.now() - 100 * 24 * 3600 * 1000);
  fs.utimesSync(f, t, t);
  const s = new Sessions(viejo, null, () => null);
  assert.equal(await s.find("QuoteScreen.kt"), null, "una conversación vieja no se usa sola");
  const old = await s.latestOld();
  assert.equal(old?.source, "Claude Code");
  assert.equal(old?.ago, "hace 3 meses");
  assert.equal(old?.title, "Dame el plan de cotizaciones");
  s.choose(old!.file);
  const found = await s.find("QuoteScreen.kt");
  assert.equal(found?.oldAgo, "hace 3 meses");
  assert.ok(found!.excerpt.startsWith("(Esta conversación es de hace 3 meses"), "el Mentor sabe que es vieja");
});

test("un proyecto sin ninguna conversación no tiene «retomar»", async () => {
  const nuevo = path.join(tmp, "nuevo");
  fs.mkdirSync(nuevo);
  assert.equal(await new Sessions(nuevo, null, () => null).latestOld(), null);
});

test("lee las reglas de AGENTS.md y salta un CLAUDE.md que solo apunta a él", async () => {
  fs.writeFileSync(path.join(project, "CLAUDE.md"), "@AGENTS.md\n");
  assert.equal(await readRules(project), null);
  fs.writeFileSync(path.join(project, "AGENTS.md"), "# Reglas\n## Arquitectura\n- KMP\n## Git\n- nada de push");
  const rules = await readRules(project);
  assert.equal(rules?.source, "AGENTS.md");
  assert.ok(rules!.text.includes("KMP") && !rules!.text.includes("push"));
});
