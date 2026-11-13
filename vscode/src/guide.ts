import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { core } from "./core";
import { Mentor } from "./mentor";
import { readRules, StepsSource } from "./context";
import { BannerAction, chooseSource, clip, NOTE_COLORS, NoteKind, noteTitle, samePath, StepBanner } from "./banner";

interface Todo { label: string; text: string }
interface Edit { path: string; fileName: string; line: number; isSkeleton: boolean; isDeletion: boolean; key: string; todos: Todo[] }
interface Step { id: string; number: string; title: string; label: string; verify: string | null; edits: Edit[] }
interface Mark { label: string; text: string; state: "PENDING" | "MISSING" | "DONE" | "UNKNOWN"; line: number | null; missing: boolean }
interface Status { todos: Mark[]; skeletonPlaced: boolean; canPlaceSkeleton: boolean; complete: boolean; cursorLine: number | null; found: boolean }
interface Verdict { ok: boolean; gaps: { file: string | null; line: number | null; text: string }[] }

interface EditView { edit: Edit; index: number; status: Status; text: string }
interface StepView { step: Step; index: number; edits: EditView[]; complete: boolean; verdict: Verdict | null; staleVerdict: Verdict | null }
interface View { source: StepsSource; steps: Step[]; current: StepView; next: Step | null }

/** Una marca del paso sobre una línea del archivo: lo que falta (rojo), la confirmación (verde) o algo ya viejo (gris). */
interface StepNote { line: number; kind: NoteKind; label: string; message: string }

/** Carpetas que nunca tienen el código del paso (al buscar un archivo cuyo enlace no apunta a nada). */
const NOT_SOURCE = "**/{build,node_modules,.gradle,.git,.idea,out,dist}/**";

/**
 * Guía de pasos en VS Code: toma el plan más reciente que habla del archivo (de Claude Code, Copilot o Antigravity) y,
 * como la barra de Android Studio, dice en qué paso vas y qué te falta, con sus acciones: ir a lo que falta, poner el
 * esqueleto, «¿Cumple el paso?», explicarlo y avanzar. VS Code no deja poner una barra fija dentro del editor, así que
 * la misma barra ([banner]) se dibuja arriba del panel del Mentor, en la barra de estado y como CodeLens en la primera
 * línea; y lo que falta se marca sobre su propia línea. La lógica (ubicar los TODO, el esqueleto, la comprobación) es la
 * del core de Kotlin, la misma del plugin de Android Studio.
 */
export class Guide implements vscode.CodeLensProvider, vscode.Disposable {
  private readonly lensesChanged = new vscode.EventEmitter<void>();
  readonly onDidChangeCodeLenses = this.lensesChanged.event;

  private readonly sources = new Map<string, { at: number; value: StepsSource | null }>();
  private readonly loading = new Set<string>();
  /** El plan que estás siguiendo y sus pasos. Vale para todos sus archivos, no solo para el que lo mencionó último. */
  private parsed: { source: StepsSource; steps: Step[] } | null = null;
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

  /** Líneas marcadas: un borde a la izquierda y un fondo suave del color de la marca (el texto va en el CodeLens). */
  private readonly lineStyles = new Map<NoteKind, vscode.TextEditorDecorationType>(
    (["error", "bien", "stale"] as NoteKind[]).map((k) => [k, vscode.window.createTextEditorDecorationType({
      isWholeLine: true,
      borderWidth: "0 0 0 3px",
      borderStyle: "solid",
      borderColor: NOTE_COLORS[k],
      backgroundColor: k === "error" ? "rgba(236,124,105,0.08)" : k === "bien" ? "rgba(93,190,138,0.08)" : undefined,
      overviewRulerColor: NOTE_COLORS[k],
      overviewRulerLane: vscode.OverviewRulerLane.Left,
    })]),
  );
  private readonly disposables: vscode.Disposable[] = [this.lensesChanged, ...this.lineStyles.values()];

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

  /**
   * El plan para este archivo: el más reciente que lo menciona; pero si el plan que ya sigues tiene un cambio en este
   * archivo y es igual de nuevo o más, se queda ese. Así, al ir al archivo del paso, sigues en el mismo plan y paso.
   * La búsqueda se repite en segundo plano cada 10 s.
   */
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
    const followed = this.parsed;
    const inFollowed = !!followed?.steps.some((s) => s.edits.some((e) => samePath(e.path, doc.uri.fsPath)));
    return chooseSource(cached?.value ?? null, followed?.source ?? null, inFollowed);
  }

  private steps(src: StepsSource): Step[] {
    if (this.parsed?.source.id !== src.id) {
      const steps: Step[] = JSON.parse(core.raw.steps(src.markdown, this.mentor.root));
      this.parsed = { source: src, steps };
      this.viewedId = null;
      void this.findMissingFiles(steps);
    }
    return this.parsed.steps;
  }

  /**
   * Si un enlace del plan no apunta a un archivo que exista (la IA escribió solo el nombre, o una ruta de otra carpeta),
   * se busca en el proyecto por su nombre. Si hay uno solo (o uno cuya ruta termina igual), el paso pasa a usar ese.
   */
  private async findMissingFiles(steps: Step[]) {
    const missing = [...new Set(steps.flatMap((s) => s.edits.map((e) => e.path)).filter((p) => !fs.existsSync(p)))];
    let changed = false;
    for (const p of missing) {
      const found = await vscode.workspace.findFiles(`**/${path.basename(p)}`, NOT_SOURCE, 20).then((u) => u.map((x) => x.fsPath), () => [] as string[]);
      const tail = p.split("/").slice(-3).join("/");
      const pick = found.length === 1 ? found[0] : found.find((f) => f.endsWith(tail));
      if (!pick) continue;
      for (const s of steps) for (const e of s.edits) if (e.path === p) e.path = pick;
      changed = true;
    }
    if (changed) this.refreshSoon();
  }

  /** Texto actual de un archivo: del editor si está abierto (con cambios sin guardar), si no, del disco. */
  private textOf(p: string): string {
    const open = vscode.workspace.textDocuments.find((d) => samePath(d.uri.fsPath, p));
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

  // ---------- la barra del paso (datos) ----------

  /**
   * La barra del paso para este archivo, igual que la de Android Studio: qué paso es, qué falta en palabras y sus
   * acciones. La dibujan el panel, la barra de estado y el CodeLens de la primera línea.
   */
  banner(doc: vscode.TextDocument | undefined): StepBanner | null {
    const v = doc ? this.view(doc) : null;
    if (!doc || !v) return null;
    const cur = v.current;
    const head = `Paso ${cur.step.number} (${cur.index + 1} de ${v.steps.length})`;
    const here = cur.edits.filter((e) => samePath(e.edit.path, doc.uri.fsPath));
    const gaps = [...here, ...cur.edits.filter((e) => !here.includes(e))].flatMap((e) => e.status.todos.filter((t) => t.missing).map((t) => ({ e, t })));
    const busy = this.verifying.has(cur.step.id);
    const actions: BannerAction[] = [];
    const act = (label: string, command: string, args: unknown[] = [], tooltip?: string) => actions.push({ label, command, args, tooltip });
    const stepActions = (verifyLabel = "¿Cumple el paso?") => {
      const canPlace = here.find((e) => e.status.canPlaceSkeleton);
      if (canPlace) act("Poner el esqueleto", "mentor.guide.skeleton", [cur.index, canPlace.index], "Pone solo los TODO del paso (se deshace con ⌘Z)");
      if (busy) act("Comprobando…", "");
      else act(verifyLabel, "mentor.guide.verify", [cur.index], "El Mentor compara tu código con el paso");
      act("Explícame este paso", "mentor.guide.explain", [cur.index]);
    };
    let level: StepBanner["level"] = "info";
    let text: string;
    let missing: string | null = null;

    if (cur.complete) {
      level = "ok";
      text = `✓ ${head} completo` + (cur.verdict?.ok ? " · el Mentor lo confirma" : "");
      if (v.next) act(`Avanzar al paso ${v.next.number}` + (v.next.edits[0] ? ` · ${v.next.edits[0].fileName}` : "") + " ›", "mentor.guide.advance", [cur.index + 1]);
      else act("Terminaste los pasos de este plan", "");
      if (!cur.verdict && cur.edits.length) act("¿Cumple el paso?", "mentor.guide.verify", [cur.index]);
    } else if (cur.edits.length === 0) {
      text = `${head} · ${clip(cur.step.title, 60)}` + (cur.step.verify ? ` · ${clip(cur.step.verify, 80)}` : "");
      act("Hecho", "mentor.guide.done", [cur.index]);
    } else if (here.length === 0) {
      // El paso es en otro archivo: se dice cuál y se lleva ahí (con el Mentor encendido allá).
      const target = cur.edits.find((e) => !e.status.complete) ?? cur.edits[0];
      text = `${head} · este paso sigue en ${target.edit.fileName}`;
      missing = `en ${target.edit.fileName}`;
      act(`Ir a ${target.edit.fileName} ›`, "mentor.guide.goTo", [cur.index, target.index]);
      act("Explícame este paso", "mentor.guide.explain", [cur.index]);
    } else if (busy) {
      text = `${head} · Comprobando con el Mentor…`;
    } else if (cur.verdict && !cur.verdict.ok) {
      level = "warn";
      const more = cur.verdict.gaps.length - 1;
      const first = cur.verdict.gaps[0]?.text ?? "algo del paso";
      missing = first;
      text = `${head} · El Mentor dice que falta: ${clip(first, 80)}` + (more > 0 ? ` (y ${more} más)` : "") + " · marcado en rojo en el código";
      const gap = cur.verdict.gaps.find((g) => g.line && (!g.file || g.file === path.basename(doc.uri.fsPath)));
      if (gap) act("Ir a lo que falta", "mentor.guide.goToLine", [doc.uri.fsPath, gap.line! - 1]);
      else if (gaps[0]) act("Ir a lo que falta", "mentor.guide.goTo", [cur.index, gaps[0].e.index]);
      stepActions("Comprobar de nuevo");
    } else if (gaps.length) {
      const { e, t } = gaps[0];
      const where = !samePath(e.edit.path, doc.uri.fsPath) ? ` (en ${e.edit.fileName})` : "";
      const more = gaps.length > 1 ? ` · y ${gaps.length - 1} más` : "";
      missing = `${t.label}${where}`;
      text = t.state === "PENDING"
        ? `${head} · ${t.label}${where} sigue escrito: ${clip(t.text, 60)} · cuando lo completes, borra el comentario${more}`
        : `${head} · Te falta ${t.label}${where}: ${clip(t.text, 80)}${more}`;
      act("Ir a lo que falta", "mentor.guide.goTo", [cur.index, e.index]);
      stepActions();
    } else {
      // Sin TODO pendientes. Lo que falta puede estar en otro archivo del paso (borrar algo y luego quien lo usa).
      const open = here.find((e) => !e.status.complete);
      const elsewhere = cur.edits.find((e) => !here.includes(e) && !e.status.complete);
      text = !open && elsewhere
        ? `${head} · aquí ya está · falta en ${elsewhere.edit.fileName}`
        : `${head} · ${clip(cur.step.title, 60)} · no veo TODO pendientes: si compila, pulsa «¿Cumple el paso?»`;
      if (elsewhere) missing = `en ${elsewhere.edit.fileName}`;
      if (open) act("Ir al código", "mentor.guide.goTo", [cur.index, open.index]);
      if (elsewhere) act(`Ir a ${elsewhere.edit.fileName} ›`, "mentor.guide.goTo", [cur.index, elsewhere.index]);
      stepActions();
    }
    return { level, head, text, missing, tooltip: this.tooltip(v), actions };
  }

  /** Al pasar el ratón: de dónde salió el plan, el paso completo, cada TODO con su estado y cómo verificarlo. */
  private tooltip(v: View): string {
    const cur = v.current;
    const marks: Record<Mark["state"], string> = { DONE: "✓", PENDING: "✗ sigue escrito", MISSING: "✗ falta", UNKNOWN: "?" };
    const lines = [`${v.source.kind} · «${clip(v.source.title, 60)}»`, cur.step.label];
    for (const e of cur.edits) for (const t of e.status.todos) lines.push(`${marks[t.state]} ${t.label} (${e.edit.fileName}): ${clip(t.text, 100)}`);
    if (cur.step.verify) lines.push(`Cómo verificarlo: ${clip(cur.step.verify, 200)}`);
    return lines.join("\n");
  }

  /** Desde la barra de estado: las acciones de la barra en un menú. */
  async menu() {
    const doc = vscode.window.activeTextEditor?.document;
    const b = this.banner(doc);
    if (!b) return void vscode.window.showInformationMessage("Enciende el Mentor en un archivo del plan para ver en qué paso vas.");
    const pick = await vscode.window.showQuickPick(
      b.actions.filter((a) => a.command).map((a) => ({ label: a.label, detail: a.tooltip, a })),
      { title: b.text, placeHolder: b.head },
    );
    if (pick) await vscode.commands.executeCommand(pick.a.command, ...pick.a.args);
  }

  // ---------- CodeLens: la barra arriba del archivo y lo que falta sobre su línea ----------

  provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
    const b = this.banner(doc);
    if (!b) return [];
    const top = new vscode.Range(0, 0, 0, 0);
    const icon = b.level === "ok" ? "$(pass-filled)" : b.level === "warn" ? "$(warning)" : "$(mortar-board)";
    const out = [new vscode.CodeLens(top, { title: `${icon} ${b.text}`, command: "", tooltip: b.tooltip })];
    for (const a of b.actions) out.push(new vscode.CodeLens(top, { title: a.label, command: a.command, arguments: a.args, tooltip: a.tooltip }));
    // Lo que falta, sobre su propia línea (en Android Studio va en un bloque bajo la línea).
    for (const n of this.notes(doc)) {
      const at = new vscode.Range(n.line, 0, n.line, 0);
      out.push(new vscode.CodeLens(at, { title: noteTitle(n.kind, n.label, n.message), command: "", tooltip: `${n.label}: ${n.message}` }));
    }
    return out;
  }

  /** Tras «¿Cumple el paso?»: lo que falta, la confirmación o la respuesta que ya quedó vieja, en las líneas de este archivo. */
  private notes(doc: vscode.TextDocument): StepNote[] {
    const v = this.view(doc);
    const cur = v?.current;
    if (!cur || !this.checked.has(cur.step.id)) return [];
    const here = cur.edits.filter((e) => samePath(e.edit.path, doc.uri.fsPath));
    const out: StepNote[] = [];
    const clamp = (l: number) => Math.min(Math.max(l, 0), doc.lineCount - 1);
    // Si el Mentor vio tu código actual y dice que cumple, su respuesta manda sobre la comprobación gratis.
    if (!cur.verdict?.ok) for (const e of here) for (const t of e.status.todos) {
      if (!t.missing || t.line === null) continue;
      out.push(t.state === "PENDING"
        ? { line: clamp(t.line), kind: "error", label: `${t.label} sigue escrito`, message: `${t.text} · Cuando lo completes, borra el comentario TODO.` }
        : { line: clamp(t.line), kind: "error", label: `Falta · ${t.label}`, message: t.text });
    }
    const name = path.basename(doc.uri.fsPath);
    const fallback = clamp(here[0]?.status.cursorLine ?? 0);
    const verdict = cur.verdict ?? cur.staleVerdict;
    const stale = !cur.verdict;
    if (verdict?.ok && here.length) {
      out.push({ line: fallback, kind: stale ? "stale" : "bien", label: stale ? "Mentor (antes de tus cambios)" : "Mentor", message: `Tu código cumple el ${cur.step.label}.` });
    } else if (verdict) for (const g of verdict.gaps.filter((g) => !g.file || g.file === name)) {
      const line = g.line ? clamp(g.line - 1) : fallback;
      if (!out.some((n) => n.line === line)) out.push({ line, kind: stale ? "stale" : "error", label: stale ? "Falta (Mentor, antes de tus cambios)" : "Falta (Mentor)", message: g.text });
    }
    return out;
  }

  /** Marca las líneas de las notas con su color (el texto de cada nota va en su CodeLens). */
  paint() {
    for (const ed of vscode.window.visibleTextEditors) {
      const notes = this.notes(ed.document);
      for (const [kind, style] of this.lineStyles) {
        ed.setDecorations(style, notes.filter((n) => n.kind === kind).map((n) => ({
          range: new vscode.Range(n.line, 0, n.line, 0),
          hoverMessage: new vscode.MarkdownString(`**${n.label}** · ${n.message}`),
        })));
      }
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

  /**
   * Abre el archivo en la línea (0-based). Si el Mentor estaba apagado en ese archivo, lo enciende: así la barra del
   * paso y lo que falta te acompañan allá (como en Android Studio). Si el archivo no existe, lo busca por su nombre.
   */
  async goToLine(file: string, line: number) {
    let doc = await vscode.workspace.openTextDocument(file).then(undefined, () => null);
    if (!doc) {
      const found = await vscode.workspace.findFiles(`**/${path.basename(file)}`, NOT_SOURCE, 2).then(undefined, () => []);
      if (found.length === 1) doc = await vscode.workspace.openTextDocument(found[0]).then(undefined, () => null);
    }
    if (!doc) return void vscode.window.showWarningMessage(`No encontré ${path.basename(file)} en el proyecto. Revisa que la carpeta abierta sea la raíz del proyecto.`);
    const ed = await vscode.window.showTextDocument(doc);
    const l = Math.min(Math.max(line, 0), doc.lineCount - 1);
    const col = doc.lineAt(l).firstNonWhitespaceCharacterIndex;
    ed.selection = new vscode.Selection(l, col, l, col);
    ed.revealRange(new vscode.Range(l, 0, l, 0), vscode.TextEditorRevealType.InCenter);
    if (!this.mentor.isOn(doc)) await this.mentor.toggle(doc);
    this.refreshSoon();
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
    if (target) await this.goToLine(target.edit.path, target.status.cursorLine ?? Math.max(target.edit.line - 1, 0));
    this.refreshSoon();
  }

  markDone(stepIndex: number) {
    const c = this.current();
    if (!c) return;
    this.manualDone.add(c.v.steps[stepIndex].id);
    this.refreshSoon();
  }

  /**
   * «Explícame este paso»: qué lograr en cada TODO y por qué, sin darte el código. Le dice cómo vas (qué ya está y qué
   * falta, con archivo y línea) para que se centre en lo que falta. Responde en el panel.
   */
  async explain(stepIndex: number) {
    const c = this.current();
    if (!c) return;
    const sv = this.stepView(c.v.source, c.v.steps, stepIndex);
    const statuses = JSON.stringify(sv.edits.map((e) => e.status));
    const request: string = core.raw.explainRequest(c.v.source.markdown, this.mentor.root, stepIndex, statuses);
    void vscode.commands.executeCommand("mentor.openPanel");
    await this.mentor.ask(c.doc, request);
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
