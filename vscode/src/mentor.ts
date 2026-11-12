import * as path from "path";
import * as vscode from "vscode";
import { Config } from "./config";
import { Sessions, readRules, Found } from "./context";
import { ChatMessage, core, Reply } from "./core";
import { brandIcon, brandOfPlanFile } from "./brands";

/**
 * Una entrada de la conversación del panel, en orden de llegada. «review» y «hint» solo marcan el momento: el panel
 * dibuja la revisión y la pista desde su propio estado (que cambia al resolver comentarios).
 */
export interface Entry {
  role: "user" | "assistant" | "error" | "review" | "hint" | "giveup";
  text: string;
  at: number;
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

  constructor(
    readonly config: Config,
    private readonly state: vscode.Memento,
    readonly root: string | null,
  ) {
    this.sessions = root ? new Sessions(root) : null;
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
    this.found.set(doc.uri.toString(), await this.sessions.find(path.basename(doc.uri.fsPath)).catch(() => null));
    this.changed.fire();
  }

  async chooseSession(doc: vscode.TextDocument | undefined) {
    if (!this.sessions) return void vscode.window.showInformationMessage("Abre una carpeta de proyecto para leer sus conversaciones.");
    // Conversaciones de Claude Code y planes de Antigravity, cada uno con el ícono de su IA.
    const files = [...await this.sessions.candidates(), ...(await this.sessions.antigravityPlans()).map((p) => p.file)];
    const auto = { label: "$(sparkle) Automática", description: "el plan más reciente que habla de este archivo", file: null as string | null };
    const items = [auto, ...(await Promise.all(files.map(async (f) => ({
      label: await this.sessions!.label(f),
      description: brandOfPlanFile(f) === "antigravity" ? "Antigravity" : "Claude Code",
      iconPath: brandIcon(brandOfPlanFile(f)),
      file: f as string | null,
    }))))];
    const pick = await vscode.window.showQuickPick(items, { title: "De dónde lee el Mentor tu plan" });
    if (!pick) return;
    this.sessions.choose(pick.file);
    if (doc) await this.refreshSession(doc);
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
      const system = core.chatSystem(this.config.options(), rel, doc?.getText() ?? "", session, rules);
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

  /** Llama a la IA con la configuración actual y anota el consumo. */
  async call(kind: string, system: string, messages: ChatMessage[], maxTokens: number): Promise<Reply> {
    const cfg = await this.config.aiConfig();
    const reply = await core.complete(cfg, system, messages, maxTokens);
    if (reply.ok) await this.record(kind, reply.inputTokens ?? 0, reply.outputTokens ?? 0, reply.costUsd ?? 0);
    return reply;
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
