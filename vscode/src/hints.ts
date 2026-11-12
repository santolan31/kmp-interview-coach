import * as vscode from "vscode";
import { core } from "./core";
import { Mentor } from "./mentor";
import { readRules } from "./context";

export interface Hint {
  level: string;
  number: number;
  levelLabel: string;
  line: number;
  problem: string;
  text: string;
  concept: string | null;
  redacted: boolean;
  at: number;
}

/** La escalera de pistas de un archivo: las que ya te dio, si se ve en el editor y el último intento. */
interface Ladder {
  hints: Hint[];
  visible: boolean;
  textAtLastHint: string | null;
  lastAt: number;
  notice: string | null;
}

/**
 * Pistas escalonadas (pregunta → concepto → herramienta → pasos), sin darte la solución. La última pista se ve al final
 * de su línea y completa al pasar el ratón, con enlaces para pedir otra u ocultarla. Si el modelo regala código de tu
 * ejercicio, se le pide reescribirla; si insiste, ese fragmento se oculta. «Me rindo» explica la solución en el panel.
 */
export class Hints implements vscode.Disposable {
  private readonly ladders = new Map<string, Ladder>();
  private readonly busy = new Set<string>();
  private readonly levels: { name: string; number: number; label: string }[] = JSON.parse(core.raw.hintLevels());
  private readonly deco = vscode.window.createTextEditorDecorationType({
    after: { margin: "0 0 0 2em", color: new vscode.ThemeColor("editorCodeLens.foreground"), fontStyle: "italic" },
    isWholeLine: true,
  });
  private readonly disposables: vscode.Disposable[] = [this.deco];

  constructor(private readonly mentor: Mentor) {
    this.disposables.push(vscode.window.onDidChangeVisibleTextEditors(() => this.paint()));
  }

  ladder(doc: vscode.TextDocument | undefined): Ladder | undefined {
    return doc ? this.ladders.get(doc.uri.toString()) : undefined;
  }

  isBusy(doc: vscode.TextDocument | undefined) {
    return !!doc && this.busy.has(doc.uri.toString());
  }

  /** Nombre del siguiente escalón, o null si ya diste los cuatro (solo queda «Me rindo»). */
  nextLevel(doc: vscode.TextDocument | undefined): { name: string; number: number; label: string } | null {
    const last = this.ladder(doc)?.hints.at(-1)?.level ?? null;
    const next = core.raw.nextHintLevel(last);
    return next ? this.levels.find((l) => l.name === next) ?? null : null;
  }

  total() {
    return this.levels.length;
  }

  /** Pide la siguiente pista. [force]: aunque no hayas cambiado nada desde la anterior. */
  async request(doc: vscode.TextDocument, force = false) {
    const k = doc.uri.toString();
    if (this.busy.has(k)) return;
    const code = doc.getText();
    const level = this.nextLevel(doc);
    if (!code.trim() || !level) return;
    const ladder = this.ladders.get(k) ?? { hints: [], visible: true, textAtLastHint: null, lastAt: 0, notice: null };
    this.ladders.set(k, ladder);
    // Pedir pistas seguidas sin intentar nada no enseña: primero se pide un intento.
    if (!force && core.raw.shouldNudge(ladder.textAtLastHint, code, ladder.lastAt, Date.now())) {
      ladder.notice = "Intenta algo con la pista anterior, aunque esté mal: equivocarse también enseña.";
      this.mentor.notify();
      return;
    }
    ladder.notice = null;
    this.busy.add(k);
    this.mentor.notify();
    try {
      const rel = this.mentor.relativePath(doc);
      const ed = vscode.window.activeTextEditor?.document === doc ? vscode.window.activeTextEditor : undefined;
      const focus = ed ? ed.selection.active.line + 1 : 0;
      const problems = this.problems(doc);
      const session = this.mentor.isOn(doc) ? this.mentor.foundFor(doc)?.excerpt ?? null : null;
      const goal = session ? `Extracto de su conversación con la IA que lo guía paso a paso:\n${session}` : null;
      const rules = this.mentor.root ? await readRules(this.mentor.root) : null;
      const previous = JSON.stringify(ladder.hints.map((h) => ({ level: h.level, line: h.line, text: h.text, problem: h.problem })));
      const prompt = JSON.parse(core.raw.hintPrompt(JSON.stringify(this.mentor.config.options()), rel, code, JSON.stringify(problems), previous, focus, level.name, goal, rules ? JSON.stringify(rules) : null));
      const fallback = focus || problems[0]?.line || 1;
      const messages: { role: "user" | "assistant"; content: string }[] = [{ role: "user", content: prompt.user }];
      let reply = await this.mentor.call("hint", prompt.system, messages, 500);
      if (!reply.ok) return this.mentor.addEntry(doc, { role: "error", text: reply.error, at: Date.now() });
      let hint = this.parse(reply.text, code, doc.lineCount, level.name, fallback);
      if (hint.leaks) {
        // La pista regalaba código de tu ejercicio: se le pide reescribirla una vez; si insiste, ese fragmento se oculta.
        const retry = [...messages, { role: "assistant" as const, content: reply.text }, { role: "user" as const, content: core.raw.leakRetry() }];
        reply = await this.mentor.call("hint", prompt.system, retry, 500);
        if (reply.ok) hint = this.parse(reply.text, code, doc.lineCount, level.name, fallback);
        if (hint.leaks) hint = { ...hint, text: hint.redacted ?? hint.text, redactedFlag: true };
      }
      const h: Hint = {
        level: level.name, number: level.number, levelLabel: level.label, line: hint.line, problem: hint.problem,
        text: hint.text, concept: hint.concept, redacted: !!hint.redactedFlag, at: Date.now(),
      };
      ladder.hints.push(h);
      ladder.visible = true;
      ladder.textAtLastHint = code;
      ladder.lastAt = Date.now();
      this.mentor.addEntry(doc, { role: "hint", text: "", at: h.at });
      this.paint();
    } finally {
      this.busy.delete(k);
      this.mentor.notify();
    }
  }

  private parse(text: string, code: string, lineCount: number, level: string, fallback: number) {
    try {
      return { ...JSON.parse(core.raw.parseHint(text, code, lineCount, level, fallback)), redactedFlag: false } as {
        line: number; problem: string; text: string; concept: string | null; leaks: boolean; redacted: string | null; redactedFlag: boolean;
      };
    } catch {
      // Si no vino en JSON, se usa el texto tal cual en la línea del cursor.
      return { line: fallback, problem: "", text: text.trim(), concept: null, leaks: false, redacted: null, redactedFlag: false };
    }
  }

  /** «Me rindo»: el mismo modelo explica la solución paso a paso, en el panel. */
  async giveUp(doc: vscode.TextDocument) {
    const k = doc.uri.toString();
    if (this.busy.has(k) || !doc.getText().trim()) return;
    this.busy.add(k);
    this.mentor.notify();
    try {
      const ladder = this.ladders.get(k);
      const ed = vscode.window.activeTextEditor?.document === doc ? vscode.window.activeTextEditor : undefined;
      const rules = this.mentor.root ? await readRules(this.mentor.root) : null;
      const hints = JSON.stringify((ladder?.hints ?? []).map((h) => ({ level: h.level, line: h.line, text: h.text, problem: h.problem })));
      const prompt = JSON.parse(core.raw.giveUpPrompt(JSON.stringify(this.mentor.config.options()), this.mentor.relativePath(doc), doc.getText(),
        JSON.stringify(this.problems(doc)), hints, ed ? ed.selection.active.line + 1 : 0, rules ? JSON.stringify(rules) : null));
      const reply = await this.mentor.call("giveup", prompt.system, [{ role: "user", content: prompt.user }], 1200);
      this.mentor.addEntry(doc, reply.ok ? { role: "giveup", text: reply.text.trim(), at: Date.now() } : { role: "error", text: reply.error, at: Date.now() });
    } finally {
      this.busy.delete(k);
      this.mentor.notify();
    }
  }

  /** Quita la pista del editor (sigue en el panel). */
  hide(doc: vscode.TextDocument | undefined) {
    const l = this.ladder(doc);
    if (l) l.visible = false;
    this.paint();
    this.mentor.notify();
  }

  /** «Nuevo problema»: empieza la escalera desde la primera pista. */
  reset(doc: vscode.TextDocument | undefined) {
    if (doc) this.ladders.delete(doc.uri.toString());
    this.paint();
    this.mentor.notify();
  }

  /** Errores que el editor marca en el archivo, para que la pista hable de lo que de verdad falla. */
  private problems(doc: vscode.TextDocument) {
    return vscode.languages.getDiagnostics(doc.uri)
      .filter((d) => d.severity === vscode.DiagnosticSeverity.Error)
      .slice(0, 20)
      .map((d) => ({ line: d.range.start.line + 1, message: d.message }));
  }

  /** Dibuja la última pista al final de su línea; al pasar el ratón, el texto completo con «Otra pista» y «Ocultar». */
  paint() {
    vscode.commands.executeCommand("setContext", "mentor.hintVisible", false);
    for (const ed of vscode.window.visibleTextEditors) {
      const l = this.ladders.get(ed.document.uri.toString());
      const h = l?.visible ? l.hints.at(-1) : undefined;
      if (!h) { ed.setDecorations(this.deco, []); continue; }
      const line = Math.min(Math.max(h.line - 1, 0), ed.document.lineCount - 1);
      const short = h.text.replace(/`/g, "").replace(/\s+/g, " ");
      const hover = new vscode.MarkdownString(
        `**Mentor · Pista ${h.number}/${this.total()} · ${h.levelLabel}**\n\n${h.text}\n\n` +
        (this.nextLevel(ed.document) ? `[Otra pista](command:mentor.hint) · ` : "Ya no quedan pistas: inténtalo o [ríndete sin culpa](command:mentor.giveUp) · ") +
        `[Ocultar](command:mentor.hideHint)`,
      );
      hover.isTrusted = { enabledCommands: ["mentor.hint", "mentor.hideHint", "mentor.giveUp"] };
      ed.setDecorations(this.deco, [{
        range: new vscode.Range(line, 0, line, 0),
        hoverMessage: hover,
        renderOptions: { after: { contentText: `💡 Pista ${h.number}/${this.total()}: ${short.length > 90 ? short.slice(0, 89) + "…" : short}` } },
      }]);
      if (ed === vscode.window.activeTextEditor) vscode.commands.executeCommand("setContext", "mentor.hintVisible", true);
    }
  }

  dispose() {
    this.disposables.forEach((d) => d.dispose());
  }
}
