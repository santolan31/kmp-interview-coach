import * as fs from "fs/promises";
import * as os from "os";
import * as path from "path";
import { core, Rules } from "./core";

/** Lo que lee el Mentor para un archivo: de dónde salió, el extracto, su nombre legible y de qué IA es. */
export interface Found {
  file: string;
  excerpt: string;
  lastRequest: string | null;
  automatic: boolean;
  /** «Claude Code», «Antigravity», «GitHub Copilot» o «Plan pegado». */
  source: string;
  /** «hace 3 meses» si es una conversación vieja que elegiste a mano; null si es reciente. */
  oldAgo: string | null;
}

/** La última conversación del proyecto, cuando ninguna es reciente: estás retomando el proyecto. */
export interface Old {
  file: string;
  source: string;
  brand: string;
  title: string | null;
  ago: string;
}

/** De dónde salieron los pasos de la guía: el texto, su origen y cuándo se escribió. */
export interface StepsSource {
  id: string;
  markdown: string;
  kind: string;
  title: string;
  at: string;
}

/** Cuántas conversaciones de este proyecto se encontraron en cada IA: el panel lo dice cuando no hay plan. */
export interface Searched {
  claude: number;
  copilot: number;
  antigravity: number;
}

interface Plan { file: string; text: string; mtime: number }
interface Conv { file: string; mtime: number }

const TAIL_BYTES = 600_000;
/** Conversaciones de los últimos días: lo que estás haciendo ahora. */
const MAX_AGE_MS = 3 * 24 * 3600 * 1000;
/** Un plan dura más que una conversación: se sigue días después de escribirlo. */
const PLAN_MAX_AGE_MS = 7 * 24 * 3600 * 1000;

/**
 * Las conversaciones del proyecto con otras IA, para saber qué plan sigues:
 * - Claude Code: `~/.claude/projects/<carpeta>/*.jsonl`.
 * - Antigravity (IDE, app y CLI): `~/.gemini/antigravity…/brain/<id>/.system_generated/logs/transcript.jsonl`, y sus
 *   planes `implementation_plan.md`.
 * - GitHub Copilot: `…/workspaceStorage/<este proyecto>/chatSessions/*.json` del editor donde corre la extensión.
 * - Un plan que pegaste a mano (de cualquier otra IA).
 * Lee solo el final de cada archivo y guarda lo leído mientras el archivo no cambie. Todo es local: nada se envía.
 */
export class Sessions {
  private readonly cache = new Map<string, { key: string; turns: string }>();
  private readonly belongs = new Map<string, { key: string; yes: boolean }>();
  private chosen: string | null = null;
  /** Lo último buscado, para que el panel diga dónde buscó cuando no encuentra nada. */
  lastSearch: Searched = { claude: 0, copilot: 0, antigravity: 0 };

  constructor(
    private readonly root: string,
    /** Carpeta `chatSessions` de Copilot para este proyecto en este editor, o null si no aplica. */
    private readonly copilotDir: string | null,
    /** El plan pegado a mano, si hay. */
    private readonly pasted: () => string | null,
  ) {}

  // ---------- dónde están las conversaciones ----------

  private async files(dir: string, filter: (name: string) => boolean, maxAge = MAX_AGE_MS): Promise<Conv[]> {
    const names = await fs.readdir(dir).catch(() => [] as string[]);
    const out: Conv[] = [];
    for (const n of names.filter(filter)) {
      const file = path.join(dir, n);
      const s = await fs.stat(file).catch(() => null);
      if (s?.isFile() && Date.now() - s.mtimeMs < maxAge) out.push({ file, mtime: s.mtimeMs });
    }
    return out;
  }

  /** Con tildes en la ruta («Facturación») se prueban las dos formas de escribirlas: macOS y Claude Code pueden usar otra. */
  private async claudeFiles(maxAge = MAX_AGE_MS) {
    const out: Conv[] = [];
    for (const root of core.raw.pathVariants(this.root)) {
      out.push(...await this.files(path.join(os.homedir(), ".claude", "projects", core.projectDirName(root)), (n) => n.endsWith(".jsonl"), maxAge));
    }
    return out;
  }

  private copilotFiles(maxAge = MAX_AGE_MS) {
    return this.copilotDir ? this.files(this.copilotDir, (n) => n.endsWith(".json"), maxAge) : Promise.resolve([] as Conv[]);
  }

  /** Transcripciones de Antigravity que hablan de este proyecto (mencionan su carpeta). */
  private async antigravityFiles(maxAge = MAX_AGE_MS): Promise<Conv[]> {
    const out: Conv[] = [];
    for (const rel of core.raw.brainDirs()) {
      const brain = path.join(os.homedir(), rel);
      for (const id of await fs.readdir(brain).catch(() => [] as string[])) {
        const [t] = await this.files(path.join(brain, id, path.dirname(core.raw.antigravityTranscriptFile())), (n) => n === "transcript.jsonl", maxAge);
        if (t && (await this.belongsHere(t))) out.push(t);
      }
    }
    return out;
  }

  /** Todas las conversaciones del proyecto, de cualquier fecha y con mensajes, de la más reciente a la más antigua. */
  async history(): Promise<Conv[]> {
    const all = (await Promise.all([this.claudeFiles(Infinity), this.copilotFiles(Infinity), this.antigravityFiles(Infinity)])).flat();
    const withText: Conv[] = [];
    for (const c of all.sort((a, b) => b.mtime - a.mtime)) {
      if ((JSON.parse(await this.turns(c.file).catch(() => "[]")) as unknown[]).length) withText.push(c);
    }
    return withText;
  }

  /**
   * Si no hay ninguna conversación reciente: la última del proyecto, con su edad. Es que estás retomando el proyecto;
   * lo normal es abrir un chat nuevo con tu IA (el Mentor lo leerá solo en cuanto exista).
   */
  async latestOld(): Promise<Old | null> {
    const [last] = await this.history();
    if (!last || Date.now() - last.mtime < MAX_AGE_MS) return null;
    const src = sourceOf(last.file);
    return { file: last.file, source: src.label, brand: src.brand, title: core.lastRequest(await this.turns(last.file)), ago: core.raw.ago(last.mtime, Date.now()) };
  }

  private async belongsHere(c: Conv): Promise<boolean> {
    const key = String(c.mtime);
    const hit = this.belongs.get(c.file);
    if (hit?.key === key) return hit.yes;
    const yes = core.raw.antigravityBelongsTo(await this.tail(c.file), this.root);
    this.belongs.set(c.file, { key, yes });
    return yes;
  }

  /** Todas las conversaciones recientes del proyecto, de la más reciente a la más antigua (máximo 12). */
  async candidates(): Promise<string[]> {
    const [claude, copilot, antigravity] = await Promise.all([this.claudeFiles(), this.copilotFiles(), this.antigravityFiles()]);
    this.lastSearch = { claude: claude.length, copilot: copilot.length, antigravity: antigravity.length };
    return [...claude, ...copilot, ...antigravity].sort((a, b) => b.mtime - a.mtime).slice(0, 12).map((c) => c.file);
  }

  // ---------- leer una conversación ----------

  private async tail(file: string): Promise<string> {
    const s = await fs.stat(file);
    const handle = await fs.open(file, "r");
    try {
      const start = Math.max(0, s.size - TAIL_BYTES);
      const buf = Buffer.alloc(s.size - start);
      await handle.read(buf, 0, buf.length, start);
      const text = buf.toString("utf8");
      return start > 0 ? text.slice(text.indexOf("\n") + 1) : text; // la primera línea puede venir cortada
    } finally {
      await handle.close();
    }
  }

  /** Mensajes de texto de la conversación (JSON del core), sea de la IA que sea. */
  async turns(file: string): Promise<string> {
    const s = await fs.stat(file);
    const key = `${s.size}:${s.mtimeMs}`;
    const hit = this.cache.get(file);
    if (hit?.key === key) return hit.turns;
    const source = sourceOf(file).id;
    const turns = source === "copilot"
      ? core.raw.parseCopilot(await fs.readFile(file, "utf8")) // un JSON entero: no se puede leer solo el final
      : source === "antigravity"
        ? core.raw.parseAntigravity((await this.tail(file)).split("\n"))
        : core.parseSession((await this.tail(file)).split("\n"));
    this.cache.set(file, { key, turns });
    return turns;
  }

  // ---------- qué lee el Mentor ----------

  /**
   * Qué lee el Mentor para este archivo: lo que elegiste a mano (conversación, plan o el plan pegado); si no, el plan de
   * Antigravity si es el más reciente que habla de él; si no, la conversación más reciente que lo menciona (de cualquier
   * IA) o, si ninguna lo menciona, la más reciente del proyecto.
   */
  async find(fileName: string): Promise<Found | null> {
    const pasted = this.pastedPlan();
    if (pasted) return { file: core.raw.pastedPath(), excerpt: core.raw.planExcerpt(pasted), lastRequest: core.raw.planTitle(pasted) ?? "plan pegado", automatic: false, source: "Plan pegado", oldAgo: null };
    const conv = await this.pick(fileName);
    const chosen = await this.chosenPlan();
    const plan = chosen ?? await this.antigravityFirst(fileName, conv);
    if (plan) return { file: plan.file, excerpt: core.raw.planExcerpt(plan.text), lastRequest: core.raw.planTitle(plan.text) ?? path.basename(path.dirname(plan.file)), automatic: !chosen, source: "Antigravity", oldAgo: null };
    if (!conv) return null;
    const turns = await this.turns(conv.file);
    let excerpt = core.sessionExcerpt(turns, fileName);
    if (!excerpt) return null;
    // Una conversación vieja elegida a mano: el Mentor sabe su edad y que el código pudo cambiar desde entonces.
    const mtime = (await fs.stat(conv.file)).mtimeMs;
    const oldAgo = Date.now() - mtime > MAX_AGE_MS ? core.raw.ago(mtime, Date.now()) : null;
    if (oldAgo) excerpt = `(Esta conversación es de ${oldAgo}: el código pudo cambiar desde entonces. Compruébalo antes de dar algo por hecho.)\n\n${excerpt}`;
    return { file: conv.file, excerpt, lastRequest: core.lastRequest(turns), automatic: conv.automatic, source: sourceOf(conv.file).label, oldAgo };
  }

  /** Los pasos para la guía: del plan más reciente que habla del archivo, sea de la IA que sea, o del plan pegado. */
  async latestSteps(fileName: string): Promise<StepsSource | null> {
    const pasted = this.pastedPlan();
    if (pasted) return { id: `pegado#${pasted.length}#${hash(pasted)}`, markdown: pasted, kind: "Plan pegado", title: core.raw.planTitle(pasted) ?? "plan pegado", at: "" };
    const conv = await this.pick(fileName);
    const plan = (await this.chosenPlan()) ?? await this.antigravityFirst(fileName, conv);
    if (plan) {
      const at = new Date(plan.mtime).toISOString();
      return { id: `${plan.file}#${at}`, markdown: plan.text, kind: "Antigravity", title: core.raw.planTitle(plan.text) ?? "plan", at };
    }
    if (!conv) return null;
    const turns: { role: string; text: string; at: string }[] = JSON.parse(await this.turns(conv.file));
    const turn = [...turns].reverse().find((t) => t.role === "assistant" && core.hasSteps(t.text));
    if (!turn) return null;
    return { id: `${conv.file}#${turn.at}`, markdown: turn.text, kind: sourceOf(conv.file).label, title: core.lastRequest(await this.turns(conv.file)) ?? "conversación", at: turn.at };
  }

  /** La conversación elegida a mano o, si no, la más reciente que habla del archivo (o la más reciente del proyecto). */
  private async pick(fileName: string): Promise<{ file: string; automatic: boolean } | null> {
    const list = await this.candidates();
    // Lo que elegiste a mano vale aunque sea viejo (por ejemplo, para retomar un proyecto).
    if (this.chosen && !this.chosen.endsWith(".md") && this.chosen !== core.raw.pastedPath() && (await fs.stat(this.chosen).catch(() => null))) {
      return { file: this.chosen, automatic: false };
    }
    for (const f of list) if (core.lastMention(await this.turns(f), fileName)) return { file: f, automatic: true };
    return list.length ? { file: list[0], automatic: true } : null;
  }

  /** Planes de Antigravity de la última semana (Markdown del «cerebro» de cada conversación), del más reciente al más antiguo. */
  async antigravityPlans(): Promise<Plan[]> {
    const out: Plan[] = [];
    for (const rel of core.raw.brainDirs()) {
      const brain = path.join(os.homedir(), rel);
      for (const id of await fs.readdir(brain).catch(() => [] as string[])) {
        for (const c of await this.files(path.join(brain, id), (n) => core.raw.isPlanFile(n), PLAN_MAX_AGE_MS)) {
          const text = await fs.readFile(c.file, "utf8").catch(() => "");
          if (core.raw.isPlan(text)) out.push({ file: c.file, text, mtime: c.mtime });
        }
      }
    }
    return out.sort((a, b) => b.mtime - a.mtime);
  }

  /** El plan de Antigravity que habla del archivo, si es más nuevo que la última mención en una conversación. Si elegiste una a mano, manda la tuya. */
  private async antigravityFirst(fileName: string, conv: { file: string; automatic: boolean } | null): Promise<Plan | null> {
    if (conv && !conv.automatic) return null;
    const plan = (await this.antigravityPlans()).find((p) => core.raw.planMentions(p.text, fileName));
    if (!plan) return null;
    const convAt = conv ? core.lastMention(await this.turns(conv.file), fileName) : null;
    return !convAt || new Date(plan.mtime).toISOString() > convAt ? plan : null;
  }

  private async chosenPlan(): Promise<Plan | null> {
    if (!this.chosen?.endsWith(".md")) return null;
    const text = await fs.readFile(this.chosen, "utf8").catch(() => "");
    const s = await fs.stat(this.chosen).catch(() => null);
    return s && core.raw.isPlan(text) ? { file: this.chosen, text, mtime: s.mtimeMs } : null;
  }

  private pastedPlan(): string | null {
    return this.chosen === core.raw.pastedPath() ? this.pasted() : null;
  }

  /** Para el selector: nombre legible de cada conversación (su último pedido) o plan (su título). */
  async label(file: string): Promise<string> {
    if (file === core.raw.pastedPath()) return `Plan pegado: ${core.raw.planTitle(this.pasted() ?? "") ?? "sin título"}`;
    if (file.endsWith(".md")) return `Plan: ${core.raw.planTitle(await fs.readFile(file, "utf8").catch(() => "")) ?? path.basename(path.dirname(file))}`;
    return core.lastRequest(await this.turns(file).catch(() => "[]")) ?? path.basename(file);
  }

  /** Elige a mano qué leer: una conversación, un plan, el plan pegado (`core.raw.pastedPath()`) o null = automático. */
  choose(file: string | null) {
    this.chosen = file;
  }

  get chosenFile(): string | null {
    return this.chosen;
  }
}

/** {id, label, brand} de la IA de una conversación o plan, por su ruta. */
export function sourceOf(file: string): { id: string; label: string; brand: string } {
  return JSON.parse(core.raw.sourceOf(file));
}

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}

/**
 * Las reglas del proyecto (`AGENTS.md`, o `CLAUDE.md` si no hay) desde la raíz: solo las secciones de arquitectura y
 * convenciones. Un `CLAUDE.md` que solo dice `@AGENTS.md` no trae reglas propias.
 */
export async function readRules(root: string): Promise<Rules | null> {
  for (const name of ["AGENTS.md", "CLAUDE.md"]) {
    try {
      const text = await fs.readFile(path.join(root, name), "utf8");
      if (!text.split("\n").some((l) => l.trim() && !l.trim().startsWith("@"))) continue;
      const rules = core.extractRules(text);
      if (rules) return { source: name, text: rules };
    } catch {
      // no existe: probar el siguiente
    }
  }
  return null;
}
