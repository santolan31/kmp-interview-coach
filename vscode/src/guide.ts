import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { core } from "./core";
import { Mentor } from "./mentor";
import { readRules, StepsSource } from "./context";

interface Todo { label: string; text: string }
interface Edit { path: string; fileName: string; line: number; isSkeleton: boolean; isDeletion: boolean; key: string; todos: Todo[] }
interface Step { id: string; number: string; title: string; label: string; verify: string | null; edits: Edit[] }
interface Mark { label: string; text: string; state: "PENDING" | "MISSING" | "DONE" | "UNKNOWN"; line: number | null; missing: boolean }
interface Status { todos: Mark[]; skeletonPlaced: boolean; canPlaceSkeleton: boolean; complete: boolean; cursorLine: number | null; found: boolean }
interface Verdict { ok: boolean; gaps: { file: string | null; line: number | null; text: string }[] }

interface EditView { edit: Edit; index: number; status: Status; text: string }
interface StepView { step: Step; index: number; edits: EditView[]; complete: boolean; verdict: Verdict | null; staleVerdict: Verdict | null }
interface View { source: StepsSource; steps: Step[]; current: StepView; next: Step | null }

const GREEN = "#5DBE8A";
const RED = "#EC7C69";

/**
 * Guía de pasos en VS Code: toma el plan más reciente que habla del archivo (de Claude Code o de Antigravity) y dice
 * arriba del archivo, con un CodeLens, en qué paso vas y qué te falta, con sus acciones: ir a lo que falta, poner el
 * esqueleto, «¿Cumple el paso?», explicarlo y avanzar. La lógica (ubicar los TODO, el esqueleto, la comprobación) es la
 * del core de Kotlin, la misma del plugin de Android Studio.
 */
export class Guide implements vscode.CodeLensProvider, vscode.Disposable {
  private readonly lensesChanged = new vscode.EventEmitter<void>();
  readonly onDidChangeCodeLenses = this.lensesChanged.event;

  private readonly sources = new Map<string, { at: number; value: StepsSource | null }>();
  private readonly loading = new Set<string>();
  private parsed: { id: string; steps: Step[] } | null = null;
  private viewedId: string | null = null;
  /** Cambios donde ya viste el esqueleto puesto: si un TODO desaparece, cuenta como hecho. */
  private readonly seenPlaced = new Set<string>();
  private readonly manualDone = new Set<string>();
  /** Respuesta del Mentor a «¿Cumple el paso?», con una huella del código: si lo cambias, deja de valer. */
  private readonly verdicts = new Map<string, { print: string; v: Verdict }>();
  private readonly verifying = new Set<string>();
  /** Pasos donde pulsaste «¿Cumple el paso?»: desde entonces lo que falta se marca en rojo. */
  private readonly checked = new Set<string>();
  private timer: NodeJS.Timeout | undefined;

  private readonly missing = vscode.window.createTextEditorDecorationType({
    isWholeLine: true,
    backgroundColor: "rgba(236,124,105,0.10)",
    overviewRulerColor: RED,
    overviewRulerLane: vscode.OverviewRulerLane.Left,
    after: { margin: "0 0 0 2em", color: RED, fontWeight: "600" },
  });
  private readonly confirmed = vscode.window.createTextEditorDecorationType({
    isWholeLine: true,
    after: { margin: "0 0 0 2em", color: GREEN, fontWeight: "600" },
  });
  private readonly stale = vscode.window.createTextEditorDecorationType({
    isWholeLine: true,
    after: { margin: "0 0 0 2em", color: new vscode.ThemeColor("disabledForeground"), fontStyle: "italic" },
  });
  private readonly disposables: vscode.Disposable[] = [this.lensesChanged, this.missing, this.confirmed, this.stale];

  constructor(private readonly mentor: Mentor) {
    this.disposables.push(
      vscode.workspace.onDidChangeTextDocument(() => this.refreshSoon()),
      vscode.languages.onDidChangeDiagnostics(() => this.refreshSoon()),
      vscode.window.onDidChangeVisibleTextEditors(() => this.paint()),
      mentor.onDidChange(() => this.refreshSoon()),
    );
  }

  private active(doc: vscode.TextDocument) {
    return this.mentor.isOn(doc) && this.mentor.config.readAiSession && !!this.mentor.root;
  }

  /** Vuelve a dibujar la guía, agrupando los cambios seguidos (al escribir) en uno. */
  refreshSoon() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => { this.lensesChanged.fire(); this.paint(); }, 400);
  }

  // ---------- de dónde salen los pasos ----------

  /** Lo último encontrado para el archivo; si tiene más de 10 s, lo busca de nuevo en segundo plano. */
  private source(doc: vscode.TextDocument): StepsSource | null {
    const name = path.basename(doc.uri.fsPath);
    const cached = this.sources.get(name);
    if ((!cached || Date.now() - cached.at > 10_000) && !this.loading.has(name) && this.mentor.sessions) {
      this.loading.add(name);
      this.mentor.sessions.latestSteps(name).catch(() => null).then((value) => {
        const before = this.sources.get(name)?.value?.id;
        this.sources.set(name, { at: Date.now(), value });
        this.loading.delete(name);
        if (before !== value?.id) this.refreshSoon();
      });
    }
    return cached?.value ?? null;
  }

  private steps(src: StepsSource): Step[] {
    if (this.parsed?.id !== src.id) {
      this.parsed = { id: src.id, steps: JSON.parse(core.raw.steps(src.markdown, this.mentor.root)) };
      this.viewedId = null;
    }
    return this.parsed.steps;
  }

  /** Texto actual de un archivo: del editor si está abierto (con cambios sin guardar), si no, del disco. */
  private textOf(p: string): string {
    const open = vscode.workspace.textDocuments.find((d) => d.uri.fsPath === p);
    if (open) return open.getText();
    try { return fs.readFileSync(p, "utf8"); } catch { return ""; }
  }

  private hasErrors(p: string): boolean {
    return vscode.languages.getDiagnostics(vscode.Uri.file(p)).some((d) => d.severity === vscode.DiagnosticSeverity.Error);
  }

  private fingerprint(step: Step): string {
    return [...new Set(step.edits.map((e) => e.path))].map((p) => this.textOf(p)).join("\u0000");
  }

  private stepView(src: StepsSource, steps: Step[], index: number): StepView {
    const step = steps[index];
    const edits = step.edits.map((edit, i): EditView => {
      const text = this.textOf(edit.path);
      const status: Status = JSON.parse(core.raw.editStatus(src.markdown, this.mentor.root, index, i, text, this.hasErrors(edit.path), this.seenPlaced.has(edit.key)));
      if (status.skeletonPlaced) this.seenPlaced.add(edit.key);
      return { edit, index: i, status, text };
    });
    const stored = this.verdicts.get(step.id);
    const fresh = stored && stored.print === this.fingerprint(step) ? stored.v : null;
    const complete = this.manualDone.has(step.id) || fresh?.ok === true || (edits.length > 0 && edits.every((e) => e.status.complete));
    return { step, index, edits, complete, verdict: fresh, staleVerdict: stored && !fresh ? stored.v : null };
  }

  /** Lo que muestra la guía en este archivo, o null si no hay plan para él. */
  view(doc: vscode.TextDocument): View | null {
    if (!this.active(doc)) return null;
    const source = this.source(doc);
    if (!source) return null;
    const steps = this.steps(source);
    if (!steps.length) return null;
    let index = steps.findIndex((s) => s.id === this.viewedId);
    if (index < 0) {
      // El primer paso sin terminar. Queda fijo hasta que pulses «Avanzar», para que veas «✓ completo».
      index = steps.findIndex((_, i) => !this.stepView(source, steps, i).complete);
      if (index < 0) index = steps.length - 1;
      this.viewedId = steps[index].id;
    }
    return { source, steps, current: this.stepView(source, steps, index), next: steps[index + 1] ?? null };
  }

  // ---------- CodeLens: la barra de la guía arriba del archivo ----------

  provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
    const v = this.view(doc);
    if (!v) return [];
    const top = new vscode.Range(0, 0, 0, 0);
    const lens = (title: string, command = "", args: unknown[] = [], tooltip?: string) =>
      new vscode.CodeLens(top, { title, command, arguments: args, tooltip });
    const cur = v.current;
    const head = `Paso ${cur.step.number} (${cur.index + 1} de ${v.steps.length})`;
    const here = cur.edits.filter((e) => e.edit.path === doc.uri.fsPath);
    const gaps = [...here, ...cur.edits.filter((e) => !here.includes(e))].flatMap((e) => e.status.todos.filter((t) => t.missing).map((t) => ({ e, t })));
    const busy = this.verifying.has(cur.step.id);
    const out: vscode.CodeLens[] = [];
    const actions = (verifyLabel = "¿Cumple el paso?") => {
      const canPlace = here.find((e) => e.status.canPlaceSkeleton);
      if (canPlace) out.push(lens("Poner el esqueleto", "mentor.guide.skeleton", [cur.index, canPlace.index], "Pone solo los TODO del paso (se deshace con ⌘Z)"));
      out.push(busy ? lens("Comprobando…") : lens(verifyLabel, "mentor.guide.verify", [cur.index], "El Mentor compara tu código con el paso"));
      out.push(lens("Explícame este paso", "mentor.guide.explain", [cur.index]));
    };
    const from = `${v.source.kind} · «${clip(v.source.title, 40)}»`;

    if (cur.complete) {
      out.push(lens(`✓ ${head} completo` + (cur.verdict?.ok ? " · el Mentor lo confirma" : ""), "", [], from));
      if (v.next) out.push(lens(`Avanzar al paso ${v.next.number}` + (v.next.edits[0] ? ` · ${v.next.edits[0].fileName}` : "") + " ›", "mentor.guide.advance", [cur.index + 1]));
      else out.push(lens("Terminaste los pasos de este plan"));
      if (!cur.verdict && cur.edits.length) out.push(lens("¿Cumple el paso?", "mentor.guide.verify", [cur.index]));
    } else if (cur.edits.length === 0) {
      out.push(lens(`${head} · ${clip(cur.step.title, 60)}` + (cur.step.verify ? ` · ${clip(cur.step.verify, 70)}` : ""), "", [], from));
      out.push(lens("Hecho", "mentor.guide.done", [cur.index]));
    } else if (here.length === 0) {
      const target = cur.edits.find((e) => !e.status.complete) ?? cur.edits[0];
      out.push(lens(`${head} · este paso sigue en ${target.edit.fileName}`, "", [], from));
      out.push(lens("Ir ›", "mentor.guide.goTo", [cur.index, target.index]));
    } else if (busy) {
      out.push(lens(`${head} · Comprobando con el Mentor…`, "", [], from));
    } else if (cur.verdict && !cur.verdict.ok) {
      const more = cur.verdict.gaps.length - 1;
      out.push(lens(`⚠ ${head} · El Mentor dice que falta: ${clip(cur.verdict.gaps[0]?.text ?? "algo del paso", 80)}` + (more > 0 ? ` (y ${more} más)` : "") + " · marcado en rojo", "", [], from));
      const gap = cur.verdict.gaps.find((g) => g.line && (!g.file || g.file === path.basename(doc.uri.fsPath)));
      if (gap) out.push(lens("Ir a lo que falta", "mentor.guide.goToLine", [doc.uri.fsPath, gap.line! - 1]));
      actions("Comprobar de nuevo");
    } else if (gaps.length) {
      const { e, t } = gaps[0];
      const where = e.edit.path !== doc.uri.fsPath ? ` (en ${e.edit.fileName})` : "";
      const more = gaps.length > 1 ? ` · y ${gaps.length - 1} más` : "";
      out.push(lens(t.state === "PENDING"
        ? `${head} · ${t.label}${where} sigue escrito: ${clip(t.text, 60)} · cuando lo completes, borra el comentario${more}`
        : `${head} · Te falta ${t.label}${where}: ${clip(t.text, 80)}${more}`, "", [], from));
      out.push(lens("Ir a lo que falta", "mentor.guide.goTo", [cur.index, e.index]));
      actions();
    } else {
      out.push(lens(`${head} · ${clip(cur.step.title, 60)} · no veo TODO pendientes: si compila, pulsa «¿Cumple el paso?»`, "", [], from));
      actions();
    }
    return out;
  }

  // ---------- marcas en el código ----------

  /** Tras «¿Cumple el paso?»: lo que falta en rojo y la confirmación en verde, sobre su línea. */
  paint() {
    for (const ed of vscode.window.visibleTextEditors) {
      const v = this.view(ed.document);
      const red: vscode.DecorationOptions[] = [];
      const green: vscode.DecorationOptions[] = [];
      const grey: vscode.DecorationOptions[] = [];
      const cur = v?.current;
      if (cur && this.checked.has(cur.step.id)) {
        const here = cur.edits.filter((e) => e.edit.path === ed.document.uri.fsPath);
        const at = (line: number, text: string): vscode.DecorationOptions => {
          const l = Math.min(Math.max(line, 0), ed.document.lineCount - 1);
          return { range: new vscode.Range(l, 0, l, 0), renderOptions: { after: { contentText: text } }, hoverMessage: text };
        };
        // Si el Mentor vio tu código actual y dice que cumple, su respuesta manda sobre la comprobación gratis.
        if (!cur.verdict?.ok) for (const e of here) for (const t of e.status.todos) {
          if (!t.missing || t.line === null) continue;
          red.push(at(t.line, t.state === "PENDING" ? `◀ ${t.label} sigue escrito · cuando lo completes, borra el comentario` : `◀ Falta · ${t.label}: ${t.text}`));
        }
        const name = path.basename(ed.document.uri.fsPath);
        const fallback = here[0]?.status.cursorLine ?? 0;
        const verdict = cur.verdict ?? cur.staleVerdict;
        if (verdict?.ok && here.length) (cur.verdict ? green : grey).push(at(fallback, `✓ Mentor: tu código cumple el ${cur.step.label}`));
        else if (verdict) for (const g of verdict.gaps.filter((g) => !g.file || g.file === name)) {
          const line = g.line ? g.line - 1 : fallback;
          if (!red.some((r) => r.range.start.line === line)) (cur.verdict ? red : grey).push(at(line, `◀ Falta (Mentor): ${g.text}`));
        }
      }
      ed.setDecorations(this.missing, red);
      ed.setDecorations(this.confirmed, green);
      ed.setDecorations(this.stale, grey);
    }
  }

  // ---------- acciones ----------

  private current(): { doc: vscode.TextDocument; v: View } | null {
    const doc = vscode.window.activeTextEditor?.document;
    const v = doc ? this.view(doc) : null;
    return doc && v ? { doc, v } : null;
  }

  /** Abre el archivo del cambio y deja el cursor en lo primero que falta (o donde va el código). */
  async goTo(stepIndex: number, editIndex: number) {
    const c = this.current();
    if (!c) return;
    const sv = this.stepView(c.v.source, c.v.steps, stepIndex);
    const e = sv.edits[editIndex];
    if (!e) return;
    await this.goToLine(e.edit.path, e.status.cursorLine ?? Math.max(e.edit.line - 1, 0));
  }

  async goToLine(file: string, line: number) {
    const doc = await vscode.workspace.openTextDocument(file).then(undefined, () => null);
    if (!doc) return void vscode.window.showWarningMessage(`No encontré ${path.basename(file)} en el proyecto.`);
    const ed = await vscode.window.showTextDocument(doc);
    const l = Math.min(Math.max(line, 0), doc.lineCount - 1);
    const col = doc.lineAt(l).firstNonWhitespaceCharacterIndex;
    ed.selection = new vscode.Selection(l, col, l, col);
    ed.revealRange(new vscode.Range(l, 0, l, 0), vscode.TextEditorRevealType.InCenter);
  }

  /** Pone el esqueleto del cambio (solo los TODO, nunca una solución). Se deshace con ⌘Z. */
  async placeSkeleton(stepIndex: number, editIndex: number) {
    const c = this.current();
    if (!c) return;
    const e = this.stepView(c.v.source, c.v.steps, stepIndex).edits[editIndex];
    const r = core.raw.placeSkeleton(c.v.source.markdown, this.mentor.root, stepIndex, editIndex, e.text);
    if (!r) return void vscode.window.showInformationMessage("El código ya no coincide con el que citó la IA, así que no sé dónde poner el esqueleto. Cópialo desde el chat.");
    const { from, to, lines } = JSON.parse(r) as { from: number; to: number; lines: string[] };
    const doc = await vscode.workspace.openTextDocument(e.edit.path);
    const edit = new vscode.WorkspaceEdit();
    edit.replace(doc.uri, new vscode.Range(from, 0, to, doc.lineAt(to).text.length), lines.join("\n"));
    await vscode.workspace.applyEdit(edit);
    this.seenPlaced.add(e.edit.key);
    await this.goTo(stepIndex, editIndex);
    this.refreshSoon();
  }

  /** Avanza al siguiente paso: abre su archivo con el Mentor encendido y deja el cursor donde va el código. */
  async advance(stepIndex: number) {
    const c = this.current();
    if (!c) return;
    const next = c.v.steps[stepIndex];
    if (!next) return;
    this.viewedId = next.id;
    const nv = this.stepView(c.v.source, c.v.steps, stepIndex);
    const target = nv.edits.find((e) => !e.status.complete) ?? nv.edits[0];
    if (target) {
      const doc = await vscode.workspace.openTextDocument(target.edit.path).then(undefined, () => null);
      if (doc && !this.mentor.isOn(doc)) await this.mentor.toggle(doc);
      await this.goTo(stepIndex, target.index);
    }
    this.refreshSoon();
  }

  markDone(stepIndex: number) {
    const c = this.current();
    if (!c) return;
    this.manualDone.add(c.v.steps[stepIndex].id);
    this.refreshSoon();
  }

  /** «Explícame este paso»: qué lograr en cada TODO y por qué, sin darte el código. Responde en el panel. */
  async explain(stepIndex: number) {
    const c = this.current();
    if (!c) return;
    const step = c.v.steps[stepIndex];
    const todos = step.edits.flatMap((e) => e.todos).map((t) => `${t.label}: ${t.text}`).join("; ");
    void vscode.commands.executeCommand("mentor.openPanel");
    await this.mentor.ask(c.doc, `Explícame el ${step.label}: qué tengo que lograr` + (todos ? ` en cada TODO (${todos})` : "") + " y por qué, punto por punto, sin darme el código.");
  }

  /** «¿Cumple el paso?»: el Mentor compara tu código con el paso, con el plan de la otra IA como contexto. */
  async verify(stepIndex: number) {
    const c = this.current();
    if (!c) return;
    const step = c.v.steps[stepIndex];
    if (this.verifying.has(step.id)) return;
    this.verifying.add(step.id);
    this.checked.add(step.id);
    this.refreshSoon();
    try {
      const paths = [...new Set(step.edits.map((e) => e.path))];
      const files = paths.map((p) => [path.basename(p), this.textOf(p)]);
      const print = this.fingerprint(step);
      const rules = this.mentor.root ? await readRules(this.mentor.root) : null;
      const session = this.mentor.foundFor(c.doc)?.excerpt ?? null;
      const prompt = JSON.parse(core.raw.verifyPrompt(JSON.stringify(this.mentor.config.options()), c.v.source.markdown, this.mentor.root, stepIndex, JSON.stringify(files), rules ? JSON.stringify(rules) : null, session));
      const reply = await this.mentor.call("step", prompt.system, [{ role: "user", content: prompt.user }], 1000);
      if (!reply.ok) return void vscode.window.showWarningMessage(reply.error);
      try {
        this.verdicts.set(step.id, { print, v: JSON.parse(core.raw.parseVerdict(reply.text)) });
      } catch {
        void vscode.window.showWarningMessage("La respuesta del modelo llegó incompleta o con otro formato. Vuelve a intentarlo.");
      }
    } finally {
      this.verifying.delete(step.id);
      this.refreshSoon();
    }
  }

  /** Para el panel: «Paso 5.2 · Antigravity». */
  summary(doc: vscode.TextDocument | undefined): string | null {
    const v = doc ? this.view(doc) : null;
    return v ? `Paso ${v.current.step.number} de ${v.steps.length} · ${v.source.kind}` : null;
  }

  dispose() {
    clearTimeout(this.timer);
    this.disposables.forEach((d) => d.dispose());
  }
}

function clip(s: string, max: number): string {
  const t = s.replace(/`/g, "").replace(/\s+/g, " ").trim();
  return t.length > max ? t.slice(0, max - 1) + "…" : t;
}
