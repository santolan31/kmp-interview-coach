import * as path from "path";
import * as vscode from "vscode";
import { Config } from "./config";
import * as fs from "fs";
import { Sessions, readRules, Found, Old, sourceOf } from "./context";
import { ChatMessage, core, Reply } from "./core";
import { brandIcon } from "./brands";
import { actionFor, FixAction, forVsCode } from "./messages";

/**
 * Una entrada de la conversación del panel, en orden de llegada. «review» y «hint» solo marcan el momento: el panel
 * dibuja la revisión y la pista desde su propio estado (que cambia al resolver comentarios).
 */
export interface Entry {
  role: "user" | "assistant" | "error" | "review" | "hint" | "giveup";
  text: string;
  at: number;
}

/**
 * Si el Mentor puede hablar con la IA elegida. Se prueba con una llamada mínima al configurar (proveedor, clave o
 * modelo) o cuando pulsas «Probar»: así sabes enseguida si quedó conectado, sin esperar a la primera pregunta.
 * [action]: lo que se te ofrece si falló (poner la clave, iniciar sesión o elegir otro proveedor).
 */
export interface Connection {
  state: "untested" | "testing" | "ok" | "error";
  message: string;
  label: string;
  at: number;
  action?: FixAction;
}

/** Consumo acumulado por tipo de llamada (se guarda entre sesiones de VS Code). */
export interface UsageTotals {
  [kind: string]: { calls: number; input: number; output: number; cost: number };
}

/**
 * El Mentor en VS Code: en qué archivos está encendido, la conversación de cada archivo, el consumo y las llamadas a
 * la IA. La lógica (prompts, lectura de respuestas, contexto) vive en el core de Kotlin; aquí solo se orquesta.
 */
export class Mentor implements vscode.Disposable {
  private readonly changed = new vscode.EventEmitter<void>();
  readonly onDidChange = this.changed.event;

  /** Archivos donde encendiste el Mentor. No se guarda: al abrir VS Code está apagado en todos. */
  private readonly on = new Set<string>();
  private readonly chats = new Map<string, Entry[]>();
  private readonly busy = new Set<string>();
  /** Conversaciones de Claude Code y planes de Antigravity del proyecto (null sin carpeta abierta). */
  readonly sessions: Sessions | null;
  /** Última conversación encontrada por archivo, para mostrarla en el panel sin volver a leer el disco. */
  private readonly found = new Map<string, Found | null>();
  /** Si no hay conversación reciente: la última del proyecto (estás retomando el proyecto). */
  private readonly old = new Map<string, Old | null>();

  connection: Connection = { state: "untested", message: "Sin probar", label: "", at: 0 };

  constructor(
    readonly config: Config,
    private readonly state: vscode.Memento,
    /** Datos de este proyecto (el plan pegado). */
    private readonly workspace: vscode.Memento,
    readonly root: string | null,
    /** Carpeta de las conversaciones de GitHub Copilot de este proyecto en este editor, si la hay. */
    copilotDir: string | null,
  ) {
    this.sessions = root ? new Sessions(root, copilotDir, () => this.workspace.get<string>("mentor.pastedPlan") ?? null) : null;
    if (this.workspace.get<string>("mentor.pastedPlan")) this.sessions?.choose(core.raw.pastedPath());
  }

  // ---------- consultas para la interfaz ----------

  isOn(doc: vscode.TextDocument | undefined): boolean {
    return !!doc && this.on.has(doc.uri.toString());
  }

  chat(doc: vscode.TextDocument | undefined): Entry[] {
    return this.chats.get(key(doc)) ?? [];
  }

  isBusy(doc: vscode.TextDocument | undefined): boolean {
    return this.busy.has(key(doc));
  }

  /** Ruta del archivo dentro del proyecto (dice su módulo y, en KMP, su source set). */
  relativePath(doc: vscode.TextDocument): string {
    return this.root ? path.relative(this.root, doc.uri.fsPath) : path.basename(doc.uri.fsPath);
  }

  foundFor(doc: vscode.TextDocument | undefined): Found | null | undefined {
    return doc ? this.found.get(doc.uri.toString()) : undefined;
  }

  usage(): UsageTotals {
    return this.state.get<UsageTotals>("mentor.usage", {});
  }

  // ---------- acciones ----------

  /** Enciende o apaga el Mentor en el archivo. Encendido, lee tu conversación con la otra IA para saber en qué paso vas. */
  async toggle(doc: vscode.TextDocument) {
    const k = doc.uri.toString();
    if (this.on.has(k)) this.on.delete(k);
    else {
      this.on.add(k);
      await this.refreshSession(doc);
    }
    this.changed.fire();
  }

  /** Vuelve a buscar la conversación de este archivo (solo con el Mentor encendido y si lo permites en Ajustes). */
  async refreshSession(doc: vscode.TextDocument) {
    if (!this.isOn(doc) || !this.config.readAiSession || !this.sessions) return;
    const found = await this.sessions.find(path.basename(doc.uri.fsPath)).catch(() => null);
    this.found.set(doc.uri.toString(), found);
    this.old.set(doc.uri.toString(), found ? null : await this.sessions.latestOld().catch(() => null));
    this.changed.fire();
  }

  oldFor(doc: vscode.TextDocument | undefined): Old | null | undefined {
    return doc ? this.old.get(doc.uri.toString()) : undefined;
  }

  /** Usa la conversación vieja como plan, sabiendo su edad (el Mentor avisa que el código pudo cambiar). */
  async useOld(doc: vscode.TextDocument | undefined) {
    const o = this.oldFor(doc);
    if (!o || !this.sessions) return;
    this.sessions.choose(o.file);
    if (doc) await this.refreshSession(doc);
  }

  /**
   * «Copiar resumen para tu IA»: al retomar un proyecto, un mensaje para pegar en un chat nuevo con el plan anterior,
   * qué pasos ya están hechos según el código actual y el pedido de los siguientes pasos en el formato de la guía.
   */
  async copyResume(doc: vscode.TextDocument | undefined) {
    const o = this.oldFor(doc);
    if (!o || !this.sessions || !this.root) return;
    const turns = await this.sessions.turns(o.file);
    // El plan: el último mensaje con pasos; en Antigravity, también el implementation_plan.md de esa conversación.
    const list: { role: string; text: string }[] = JSON.parse(turns);
    let plan = [...list].reverse().find((t) => t.role === "assistant" && core.hasSteps(t.text))?.text ?? null;
    let planText: string | null = null;
    if (!plan && o.source === "Antigravity") {
      const brainDir = path.resolve(path.dirname(o.file), "..", "..");
      const md = path.join(brainDir, "implementation_plan.md");
      planText = fs.existsSync(md) ? fs.readFileSync(md, "utf8") : null;
      if (planText && core.raw.isPlan(planText)) plan = planText;
    }
    const steps = plan ? this.stepStatus(plan) : [];
    // Sin pasos: el plan de Antigravity tal cual si lo hay (describe mejor lo que se iba a hacer); si no, lo último del chat.
    const excerpt = steps.length ? null : planText?.trim() || core.sessionExcerpt(turns, null);
    const message = core.raw.resumeHandoff(path.basename(this.root), o.source, o.ago, o.title, JSON.stringify(steps), excerpt);
    await vscode.env.clipboard.writeText(message);
    void vscode.window.showInformationMessage(
      `Copiado: el plan anterior${steps.length ? ` (${steps.filter((s) => s.done).length} de ${steps.length} pasos hechos)` : ""} y el pedido de los siguientes pasos. ` +
      "Abre un chat nuevo con tu IA y pégalo: el Mentor leerá ese chat solo en cuanto exista.",
    );
  }

  /** Cada paso del plan con su estado según el código actual: hecho, pendiente o sin forma de comprobarlo (null). */
  private stepStatus(plan: string): { label: string; done: boolean | null }[] {
    const steps: { label: string; edits: { path: string }[] }[] = JSON.parse(core.raw.steps(plan, this.root));
    return steps.map((s, i) => {
      if (!s.edits.length) return { label: s.label, done: null };
      const done = s.edits.every((e, j) => {
        const text = fs.existsSync(e.path) ? fs.readFileSync(e.path, "utf8") : "";
        return text !== "" && JSON.parse(core.raw.editStatus(plan, this.root, i, j, text, false, false)).complete === true;
      });
      return { label: s.label, done };
    });
  }

  async chooseSession(doc: vscode.TextDocument | undefined) {
    if (!this.sessions) return void vscode.window.showInformationMessage("Abre una carpeta de proyecto para leer sus conversaciones.");
    // Conversaciones de Claude Code, GitHub Copilot y Antigravity, planes de Antigravity y el plan pegado, cada uno con su ícono.
    const pastedPath = core.raw.pastedPath();
    // Todas las conversaciones de este proyecto (de cualquier fecha, con su edad), los planes y el plan pegado.
    const history = await this.sessions.history();
    const plans = (await this.sessions.antigravityPlans()).map((p) => ({ file: p.file, mtime: p.mtime }));
    const files = [...history, ...plans].sort((a, b) => b.mtime - a.mtime);
    if (this.workspace.get<string>("mentor.pastedPlan")) files.unshift({ file: pastedPath, mtime: Date.now() });
    type Item = vscode.QuickPickItem & { file: string | null };
    const auto: Item = { label: "$(sparkle) Automática", description: "la conversación o el plan más reciente que habla de este archivo", file: null };
    const paste: Item = { label: "$(clippy) Pegar un plan…", description: "de cualquier otra IA (Gemini web, ChatGPT…): lo toma del portapapeles", file: "__paste__" };
    const items: Item[] = [auto, ...(await Promise.all(files.map(async (f): Promise<Item> => ({
      label: await this.sessions!.label(f.file),
      description: `${sourceOf(f.file).label}${f.file === pastedPath ? "" : ` · ${core.raw.ago(f.mtime, Date.now())}`}`,
      iconPath: brandIcon(sourceOf(f.file).brand),
      file: f.file as string | null,
    })))), paste];
    if (files.length === 0) items.splice(1, 0, { label: "$(info) Este proyecto no tiene conversaciones con Claude Code, Copilot ni Antigravity", file: null });
    const pick = await vscode.window.showQuickPick(items, { title: "De dónde lee el Mentor tu plan" });
    if (!pick) return;
    if (pick.file === "__paste__") return this.pastePlan(doc);
    this.sessions.choose(pick.file);
    if (doc) await this.refreshSession(doc);
  }

  /**
   * «Pegar el plan»: para cualquier IA cuya conversación no se puede leer (Gemini o ChatGPT en el navegador, Cursor…).
   * Copias el plan, pulsas el botón y el Mentor lo toma del portapapeles. Se guarda para este proyecto.
   */
  async pastePlan(doc: vscode.TextDocument | undefined) {
    const text = (await vscode.env.clipboard.readText()).trim();
    if (!text) return void vscode.window.showWarningMessage("El portapapeles está vacío. Copia el plan que te dio tu IA y vuelve a pulsar «Pegar el plan».");
    const steps = (JSON.parse(core.raw.steps(text, this.root)) as unknown[]).length;
    const what = steps ? `${steps} pasos` : "sin pasos «## Paso N» (solo se usará como contexto)";
    const ok = await vscode.window.showInformationMessage(
      `¿Usar como plan el texto del portapapeles? ${text.length.toLocaleString("es")} caracteres, ${what}. Empieza: «${text.slice(0, 80).replace(/\s+/g, " ")}…»`,
      { modal: true }, "Usar este plan",
    );
    if (ok !== "Usar este plan") return;
    await this.workspace.update("mentor.pastedPlan", text);
    this.sessions?.choose(core.raw.pastedPath());
    if (doc) await this.refreshSession(doc);
    this.changed.fire();
  }

  async clearPastedPlan(doc: vscode.TextDocument | undefined) {
    await this.workspace.update("mentor.pastedPlan", undefined);
    if (this.sessions?.chosenFile === core.raw.pastedPath()) this.sessions?.choose(null);
    if (doc) await this.refreshSession(doc);
    this.changed.fire();
  }

  /** Lo que se buscó la última vez (para decir dónde, si no se encontró nada). */
  searched() {
    return this.sessions?.lastSearch ?? { claude: 0, copilot: 0, antigravity: 0 };
  }

  /** Pregunta en el chat. Con el archivo abierto, el Mentor ve su código, las reglas del proyecto y el plan de la otra IA. */
  async ask(doc: vscode.TextDocument | undefined, question: string) {
    const q = question.trim();
    const k = key(doc);
    if (!q || this.busy.has(k)) return;
    const history = this.chats.get(k) ?? [];
    history.push({ role: "user", text: q, at: Date.now() });
    this.chats.set(k, history);
    this.busy.add(k);
    this.changed.fire();
    try {
      const rel = doc ? this.relativePath(doc) : null;
      const session = doc && this.isOn(doc) ? this.found.get(doc.uri.toString())?.excerpt ?? null : null;
      const rules = this.root ? await readRules(this.root) : null;
      // Encendido y sin plan: el Mentor dice qué buscó y qué botón usar, en vez de pedir «contexto» en general.
      const noPlan = !!doc && this.isOn(doc) && this.config.readAiSession && !session;
      const system = core.chatSystem(this.config.options(), rel, doc?.getText() ?? "", session, rules, noPlan);
      // Los últimos 10 mensajes, empezando por uno tuyo.
      const recent = history.filter((e) => e.role === "user" || e.role === "assistant").slice(-10);
      while (recent.length && recent[0].role !== "user") recent.shift();
      const messages: ChatMessage[] = recent.map((e) => ({ role: e.role as "user" | "assistant", content: e.text }));
      const reply = await this.call("chat", system, messages, 900);
      history.push(reply.ok ? { role: "assistant", text: reply.text.trim(), at: Date.now() } : { role: "error", text: reply.error, at: Date.now() });
    } finally {
      this.busy.delete(k);
      this.changed.fire();
    }
  }

  /** Agrega una entrada a la conversación del archivo (revisión, pista, solución explicada o error). */
  addEntry(doc: vscode.TextDocument | undefined, e: Entry) {
    const k = key(doc);
    this.chats.set(k, [...(this.chats.get(k) ?? []), e]);
    this.changed.fire();
  }

  clearChat(doc: vscode.TextDocument | undefined) {
    this.chats.delete(key(doc));
    this.changed.fire();
  }

  /** Llama a la IA con la configuración actual y anota el consumo. Los errores hablan de VS Code y de qué hacer. */
  async call(kind: string, system: string, messages: ChatMessage[], maxTokens: number): Promise<Reply> {
    const cfg = await this.config.aiConfig();
    const reply = await core.complete(cfg, system, messages, maxTokens);
    if (reply.ok) await this.record(kind, reply.inputTokens ?? 0, reply.outputTokens ?? 0, reply.costUsd ?? 0);
    else {
      const error = forVsCode(reply.error);
      // Un error de configuración también cambia el estado de la conexión: el panel lo muestra con su botón.
      if (actionFor(error)) this.setConnection({ state: "error", message: error, label: this.config.label(), at: Date.now(), action: actionFor(error) });
      return { ok: false, error };
    }
    return reply;
  }

  /**
   * Prueba si el Mentor puede hablar con la IA elegida: una llamada mínima («responde OK»). Con clave de API cuesta
   * unos pocos tokens; con suscripción gasta algo de su cuota. Avisa del resultado, con el botón que corresponda.
   */
  async testConnection(notify = true) {
    const label = this.config.label();
    this.setConnection({ state: "testing", message: "Probando la conexión…", label, at: Date.now() });
    const t0 = Date.now();
    const reply = await this.call("test", "Responde solo con la palabra OK.", [{ role: "user", content: "Prueba de conexión" }], 20);
    const secs = ((Date.now() - t0) / 1000).toFixed(1).replace(".", ",");
    if (reply.ok) {
      const how = core.provider(this.config.providerId).subscription ? "con tu suscripción" : "con tu clave de API";
      this.setConnection({ state: "ok", message: `Conectado ${how} (${secs} s)`, label, at: Date.now() });
      if (notify) void vscode.window.showInformationMessage(`✓ Mentor conectado: ${label} · ${how} · respondió en ${secs} s.`);
    } else {
      const action = actionFor(reply.error) ?? "provider";
      this.setConnection({ state: "error", message: reply.error, label, at: Date.now(), action });
      if (notify) await offerFix(reply.error, action);
    }
  }

  private setConnection(c: Connection) {
    this.connection = c;
    this.changed.fire();
  }

  private async record(kind: string, input: number, output: number, cost: number) {
    const totals = this.usage();
    const t = totals[kind] ?? { calls: 0, input: 0, output: 0, cost: 0 };
    totals[kind] = { calls: t.calls + 1, input: t.input + input, output: t.output + output, cost: t.cost + cost };
    await this.state.update("mentor.usage", totals);
  }

  async resetUsage() {
    await this.state.update("mentor.usage", {});
    this.changed.fire();
  }

  notify() {
    this.changed.fire();
  }

  dispose() {
    this.changed.dispose();
  }
}

const key = (doc: vscode.TextDocument | undefined) => doc?.uri.toString() ?? "_sin_archivo";

/** Muestra el error con el botón que lo arregla. */
export async function offerFix(error: string, action: Connection["action"]) {
  const buttons: Record<string, [string, string]> = {
    key: ["Poner la clave", "mentor.setApiKey"],
    login: ["Iniciar sesión en la terminal", "mentor.loginCli"],
    provider: ["Elegir proveedor", "mentor.chooseProvider"],
  };
  const [label, command] = buttons[action ?? "provider"];
  const options = action === "provider" || !action ? [label] : [label, "Elegir proveedor"];
  const pick = await vscode.window.showErrorMessage(`✗ El Mentor no pudo conectarse: ${error}`, ...options);
  if (pick === label) await vscode.commands.executeCommand(command);
  else if (pick === "Elegir proveedor") await vscode.commands.executeCommand("mentor.chooseProvider");
}
