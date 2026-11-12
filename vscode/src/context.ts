import * as fs from "fs/promises";
import * as os from "os";
import * as path from "path";
import { core, Rules } from "./core";

/** Una conversación encontrada para un archivo: de dónde salió, el extracto para el Mentor y el último pedido. */
export interface Found {
  file: string;
  excerpt: string;
  lastRequest: string | null;
  automatic: boolean;
}

/** De dónde salieron los pasos de la guía: el texto (ya en el formato de la guía), su origen y cuándo se escribió. */
export interface StepsSource {
  id: string;
  markdown: string;
  kind: "Claude Code" | "Antigravity";
  title: string;
  at: string;
}

interface Plan { file: string; text: string; mtime: number }

const TAIL_BYTES = 600_000;
const MAX_AGE_MS = 3 * 24 * 3600 * 1000;
/** Un plan dura más que una conversación: se sigue días después de escribirlo. */
const PLAN_MAX_AGE_MS = 7 * 24 * 3600 * 1000;

/**
 * Lee las conversaciones de Claude Code del proyecto (`~/.claude/projects/<carpeta>/*.jsonl`), solo el final de cada
 * archivo, con caché por tamaño y fecha. Igual que en Android Studio: el plan que dio la otra IA es la fuente de verdad.
 */
export class Sessions {
  private cache = new Map<string, { key: string; turns: string }>();
  private chosen: string | null = null;

  constructor(private readonly root: string) {}

  private dir() {
    return path.join(os.homedir(), ".claude", "projects", core.projectDirName(this.root));
  }

  /** Conversaciones de los últimos 3 días, la más reciente primero (máximo 8). */
  async candidates(): Promise<string[]> {
    try {
      const dir = this.dir();
      const names = (await fs.readdir(dir)).filter((n) => n.endsWith(".jsonl"));
      const stats = await Promise.all(names.map(async (n) => ({ f: path.join(dir, n), s: await fs.stat(path.join(dir, n)) })));
      return stats
        .filter((x) => Date.now() - x.s.mtimeMs < MAX_AGE_MS)
        .sort((a, b) => b.s.mtimeMs - a.s.mtimeMs)
        .slice(0, 8)
        .map((x) => x.f);
    } catch {
      return [];
    }
  }

  /** Mensajes de texto de la sesión (JSON del core), leyendo solo el final del archivo. */
  async turns(file: string): Promise<string> {
    const s = await fs.stat(file);
    const key = `${s.size}:${s.mtimeMs}`;
    const hit = this.cache.get(file);
    if (hit?.key === key) return hit.turns;
    const handle = await fs.open(file, "r");
    try {
      const start = Math.max(0, s.size - TAIL_BYTES);
      const buf = Buffer.alloc(s.size - start);
      await handle.read(buf, 0, buf.length, start);
      const lines = buf.toString("utf8").split("\n");
      if (start > 0) lines.shift(); // la primera línea puede venir cortada
      const turns = core.parseSession(lines);
      this.cache.set(file, { key, turns });
      return turns;
    } finally {
      await handle.close();
    }
  }

  /**
   * Qué lee el Mentor para este archivo: el plan de Antigravity si es el más reciente que habla de él; si no, la
   * conversación de Claude Code elegida a mano o la más reciente que lo menciona (si ninguna, la más reciente).
   */
  async find(fileName: string): Promise<Found | null> {
    const claude = await this.pick(fileName);
    const chosen = await this.chosenPlan();
    const plan = chosen ?? await this.antigravityFirst(fileName, claude);
    if (plan) return { file: plan.file, excerpt: core.raw.planExcerpt(plan.text), lastRequest: `Antigravity · ${core.raw.planTitle(plan.text) ?? path.basename(path.dirname(plan.file))}`, automatic: !chosen };
    if (!claude) return null;
    const turns = await this.turns(claude.file);
    const excerpt = core.sessionExcerpt(turns, fileName);
    return excerpt ? { file: claude.file, excerpt, lastRequest: core.lastRequest(turns), automatic: claude.automatic } : null;
  }

  /** Los pasos para la guía: del plan más reciente que habla del archivo, sea de Claude Code o de Antigravity. */
  async latestSteps(fileName: string): Promise<StepsSource | null> {
    const claude = await this.pick(fileName);
    const plan = (await this.chosenPlan()) ?? await this.antigravityFirst(fileName, claude);
    if (plan) {
      const at = new Date(plan.mtime).toISOString();
      return { id: `${plan.file}#${at}`, markdown: plan.text, kind: "Antigravity", title: core.raw.planTitle(plan.text) ?? "plan", at };
    }
    if (!claude) return null;
    const turns: { role: string; text: string; at: string }[] = JSON.parse(await this.turns(claude.file));
    const turn = [...turns].reverse().find((t) => t.role === "assistant" && core.hasSteps(t.text));
    if (!turn) return null;
    return { id: `${claude.file}#${turn.at}`, markdown: turn.text, kind: "Claude Code", title: core.lastRequest(await this.turns(claude.file)) ?? "conversación", at: turn.at };
  }

  /** La conversación de Claude Code elegida a mano o, si no, la más reciente que habla del archivo (o la más reciente). */
  private async pick(fileName: string): Promise<{ file: string; automatic: boolean } | null> {
    const list = await this.candidates();
    if (this.chosen && list.includes(this.chosen)) return { file: this.chosen, automatic: false };
    for (const f of list) if (core.lastMention(await this.turns(f), fileName)) return { file: f, automatic: true };
    return list.length ? { file: list[0], automatic: true } : null;
  }

  /**
   * Planes de Antigravity de la última semana: los Markdown del «cerebro» de cada conversación (las conversaciones
   * se guardan en binario). Del más reciente al más antiguo.
   */
  async antigravityPlans(): Promise<Plan[]> {
    const out: Plan[] = [];
    for (const rel of core.raw.brainDirs()) {
      const brain = path.join(os.homedir(), rel);
      const ids = await fs.readdir(brain).catch(() => [] as string[]);
      for (const id of ids) {
        const names = await fs.readdir(path.join(brain, id)).catch(() => [] as string[]);
        for (const name of names.filter((n) => core.raw.isPlanFile(n))) {
          const file = path.join(brain, id, name);
          const s = await fs.stat(file).catch(() => null);
          if (!s?.isFile() || Date.now() - s.mtimeMs > PLAN_MAX_AGE_MS) continue;
          const text = await fs.readFile(file, "utf8").catch(() => "");
          if (core.raw.isPlan(text)) out.push({ file, text, mtime: s.mtimeMs });
        }
      }
    }
    return out.sort((a, b) => b.mtime - a.mtime);
  }

  /** El plan de Antigravity que habla del archivo, si es más nuevo que la última mención de Claude. Si elegiste una conversación a mano, manda la tuya. */
  private async antigravityFirst(fileName: string, claude: { file: string; automatic: boolean } | null): Promise<Plan | null> {
    if (claude && !claude.automatic) return null;
    const plan = (await this.antigravityPlans()).find((p) => core.raw.planMentions(p.text, fileName));
    if (!plan) return null;
    const claudeAt = claude ? core.lastMention(await this.turns(claude.file), fileName) : null;
    return !claudeAt || new Date(plan.mtime).toISOString() > claudeAt ? plan : null;
  }

  /** Para el selector: nombre legible de cada conversación (su último pedido) o plan de Antigravity (su título). */
  async label(file: string): Promise<string> {
    if (file.endsWith(".md")) return `Plan: ${core.raw.planTitle(await fs.readFile(file, "utf8").catch(() => "")) ?? path.basename(path.dirname(file))}`;
    return core.lastRequest(await this.turns(file).catch(() => "[]")) ?? path.basename(file);
  }

  /** El plan de Antigravity que elegiste a mano, si elegiste uno. */
  private async chosenPlan(): Promise<Plan | null> {
    if (!this.chosen?.endsWith(".md")) return null;
    const text = await fs.readFile(this.chosen, "utf8").catch(() => "");
    const s = await fs.stat(this.chosen).catch(() => null);
    return s && core.raw.isPlan(text) ? { file: this.chosen, text, mtime: s.mtimeMs } : null;
  }

  choose(file: string | null) {
    this.chosen = file;
  }
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
