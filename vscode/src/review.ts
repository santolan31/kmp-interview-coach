import * as vscode from "vscode";
import { core } from "./core";
import { Mentor } from "./mentor";
import { readRules } from "./context";
import { around, followLine } from "./lines";
import { NOTE_COLORS, NoteKind, noteTitle } from "./banner";

/** Un comentario de la revisión, con lo que hace falta para responderlo y saber si ya quedó viejo. */
interface ReviewComment {
  thread: vscode.CommentThread;
  line: number;
  type: string;
  typeLabel: string;
  message: string;
  /** Líneas alrededor cuando se escribió: si el código del que habla ya cambió, se resuelve solo. */
  origin: string[];
  /** Conversación sobre este comentario, para seguirla con el Mentor. */
  replies: { role: "user" | "assistant"; content: string }[];
  busy: boolean;
}

export interface ReviewSummary {
  statusLabel: string;
  summary: string;
  nextStep: string;
  total: number;
  open: number;
  autoResolved: number;
  at: number;
}

/** Colores de cada tipo de comentario, los mismos del plugin de Android Studio. */
const COLORS: Record<string, string> = { error: NOTE_COLORS.error, mejora: NOTE_COLORS.mejora, pregunta: NOTE_COLORS.pregunta, bien: NOTE_COLORS.bien };

/**
 * Revisión del archivo, compacta como en Android Studio: cada comentario es una línea pequeña sobre su código
 * (CodeLens: «$(error) Error · … · Responder · Resolver») y la línea lleva un borde del color del tipo. No ocupa
 * espacio ni te quita el foco mientras escribes. El recuadro con la caja de respuesta (la API de comentarios de VS Code)
 * queda plegado y solo se abre al pulsar «Responder». Los comentarios no cambian solos con cada tecla: se actualizan
 * cuando pides otra revisión, salvo los que hablan de un código que ya cambiaste, que se resuelven solos.
 */
export class Reviews implements vscode.CodeLensProvider, vscode.Disposable {
  private readonly controller = vscode.comments.createCommentController("mentor", "Mentor");
  private readonly lensesChanged = new vscode.EventEmitter<void>();
  readonly onDidChangeCodeLenses = this.lensesChanged.event;
  /** Borde a la izquierda de cada línea comentada, del color de su tipo. */
  private readonly borders = new Map<string, vscode.TextEditorDecorationType>();
  private readonly byDoc = new Map<string, ReviewComment[]>();
  private readonly summaries = new Map<string, ReviewSummary>();
  private readonly busy = new Set<string>();
  private readonly decorations = new Map<string, vscode.TextEditorDecorationType>();
  private staleTimer: NodeJS.Timeout | undefined;
  private readonly disposables: vscode.Disposable[] = [];

  constructor(private readonly mentor: Mentor) {
    this.controller.options = { prompt: "Responde al Mentor…", placeHolder: "Tu respuesta o tu pregunta (el Mentor contesta aquí mismo)" };
    for (const [type, color] of Object.entries(COLORS)) {
      this.borders.set(type, vscode.window.createTextEditorDecorationType({
        isWholeLine: true, borderWidth: "0 0 0 3px", borderStyle: "solid", borderColor: color,
      }));
      if (type === "bien") continue;
      this.decorations.set(type, vscode.window.createTextEditorDecorationType({
        textDecoration: `underline wavy ${color}`,
        overviewRulerColor: color,
        overviewRulerLane: vscode.OverviewRulerLane.Right,
      }));
    }
    this.disposables.push(
      this.controller,
      this.lensesChanged,
      ...this.decorations.values(),
      ...this.borders.values(),
      vscode.workspace.onDidChangeTextDocument((e) => this.onEdit(e)),
      vscode.window.onDidChangeVisibleTextEditors(() => this.paint()),
    );
  }

  isBusy(doc: vscode.TextDocument | undefined) {
    return !!doc && this.busy.has(doc.uri.toString());
  }

  summary(doc: vscode.TextDocument | undefined): ReviewSummary | undefined {
    return doc ? this.summaries.get(doc.uri.toString()) : undefined;
  }

  /** Pide una revisión del archivo. Reemplaza la anterior. */
  async review(doc: vscode.TextDocument) {
    const k = doc.uri.toString();
    const code = doc.getText();
    if (!code.trim() || this.busy.has(k)) return;
    this.busy.add(k);
    this.mentor.notify();
    try {
      const rel = this.mentor.relativePath(doc);
      const session = this.mentor.isOn(doc) ? this.mentor.foundFor(doc)?.excerpt ?? null : null;
      const rules = this.mentor.root ? await readRules(this.mentor.root) : null;
      const prompt = JSON.parse(core.raw.reviewPrompt(JSON.stringify(this.mentor.config.options()), rel, code, session, rules ? JSON.stringify(rules) : null));
      const reply = await this.mentor.call("review", prompt.system, [{ role: "user", content: prompt.user }], 1800);
      if (!reply.ok) return this.mentor.addEntry(doc, { role: "error", text: reply.error, at: Date.now() });
      let parsed: { status: string; statusLabel: string; summary: string; nextStep: string; comments: { line: number; type: string; typeLabel: string; message: string }[] };
      try {
        parsed = JSON.parse(core.raw.parseReview(reply.text, doc.lineCount, this.mentor.config.options().maxComments));
      } catch {
        return this.mentor.addEntry(doc, { role: "error", text: "La respuesta del modelo llegó incompleta o con otro formato. Vuelve a intentarlo.", at: Date.now() });
      }
      this.clear(doc);
      const lines = code.split("\n");
      const comments = parsed.comments.map((c) => this.thread(doc, c, lines));
      this.byDoc.set(k, comments);
      const s: ReviewSummary = { statusLabel: parsed.statusLabel, summary: parsed.summary, nextStep: parsed.nextStep, total: comments.length, open: comments.length, autoResolved: 0, at: Date.now() };
      this.summaries.set(k, s);
      this.mentor.addEntry(doc, { role: "review", text: "", at: s.at });
      this.paint();
    } finally {
      this.busy.delete(k);
      this.mentor.notify();
    }
  }

  private thread(doc: vscode.TextDocument, c: { line: number; type: string; typeLabel: string; message: string }, lines: string[]): ReviewComment {
    const line = Math.min(Math.max(c.line - 1, 0), doc.lineCount - 1);
    const thread = this.controller.createCommentThread(doc.uri, new vscode.Range(line, 0, line, 0), [comment(c.typeLabel, c.message)]);
    thread.label = `Mentor · ${c.typeLabel}`;
    thread.canReply = c.type !== "bien";
    thread.contextValue = "mentor";
    // Plegado: el comentario se lee en su CodeLens; el recuadro con la caja de respuesta solo se abre al pulsar «Responder».
    thread.collapsibleState = vscode.CommentThreadCollapsibleState.Collapsed;
    return { thread, line, type: c.type, typeLabel: c.typeLabel, message: c.message, origin: around(lines, line), replies: [], busy: false };
  }

  /** Respondes a un comentario desde su caja: el Mentor evalúa tu respuesta y contesta en el mismo hilo. */
  async reply(r: vscode.CommentReply) {
    const c = this.find(r.thread);
    const text = r.text.trim();
    if (!c || !text || c.busy) return;
    const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === r.thread.uri.toString());
    c.thread.comments = [...c.thread.comments, comment("Tú", text, true)];
    c.replies.push({ role: "user", content: text });
    c.busy = true;
    c.thread.comments = [...c.thread.comments, comment("Mentor", "_pensando…_")];
    try {
      const rules = this.mentor.root ? await readRules(this.mentor.root) : null;
      const rel = doc ? this.mentor.relativePath(doc) : null;
      const session = doc && this.mentor.isOn(doc) ? this.mentor.foundFor(doc)?.excerpt ?? null : null;
      const system = core.chatSystem(this.mentor.config.options(), rel, doc?.getText() ?? "", session, rules);
      // El primer mensaje cita el comentario para que el Mentor sepa a cuál respondes; los demás siguen la conversación.
      const messages = c.replies.map((m, i) => i === 0 ? { role: m.role, content: core.raw.replyToComment(c.line + 1, c.type, c.message, m.content) } : m);
      const answer = await this.mentor.call("chat", system, messages, 500);
      const out = answer.ok ? answer.text.trim() : `⚠ ${answer.error}`;
      if (answer.ok) c.replies.push({ role: "assistant", content: out });
      c.thread.comments = [...c.thread.comments.slice(0, -1), comment("Mentor", out)];
    } finally {
      c.busy = false;
      this.lensesChanged.fire(); // «Responder» pasa a «Ver la conversación (n)»
    }
  }

  /** «Resolver»: el comentario sale del editor y queda contado en el panel. */
  resolve(thread: vscode.CommentThread, auto = false) {
    for (const [k, list] of this.byDoc) {
      const i = list.findIndex((c) => c.thread === thread);
      if (i < 0) continue;
      list.splice(i, 1);
      thread.dispose();
      const s = this.summaries.get(k);
      if (s) { s.open = list.length; if (auto) s.autoResolved++; }
      this.paint();
      this.mentor.notify();
      return;
    }
  }

  /** Quita la revisión del archivo. */
  clear(doc: vscode.TextDocument) {
    const k = doc.uri.toString();
    this.byDoc.get(k)?.forEach((c) => c.thread.dispose());
    this.byDoc.delete(k);
    this.paint();
  }

  /** «Responder»: abre el recuadro del comentario con su caja de respuesta. */
  async open(uri: string, index: number) {
    const c = this.byDoc.get(uri)?.[index];
    if (!c) return;
    c.thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri);
    if (doc) await this.reveal(doc, index);
  }

  /** Cada comentario en una línea sobre su código, con «Responder» y «Resolver». */
  provideCodeLenses(doc: vscode.TextDocument): vscode.CodeLens[] {
    const list = this.byDoc.get(doc.uri.toString()) ?? [];
    return list.flatMap((c, i) => {
      if (c.line >= doc.lineCount) return [];
      const at = new vscode.Range(c.line, 0, c.line, 0);
      const kind = (c.type in NOTE_COLORS ? c.type : "pregunta") as NoteKind;
      const lenses = [new vscode.CodeLens(at, { title: noteTitle(kind, `${c.typeLabel} (Mentor)`, c.message), command: "", tooltip: `${c.typeLabel}: ${c.message}` })];
      const talked = c.replies.length > 0;
      if (c.thread.canReply || talked) {
        lenses.push(new vscode.CodeLens(at, { title: talked ? `Ver la conversación (${c.replies.length})` : "Responder", command: "mentor.openComment", arguments: [doc.uri.toString(), i], tooltip: "Abre la caja para responderle al Mentor aquí mismo" }));
      }
      lenses.push(new vscode.CodeLens(at, { title: "Resolver", command: "mentor.resolveComment", arguments: [c.thread], tooltip: "Quitar este comentario del editor" }));
      return lenses;
    });
  }

  /** Muestra en el editor el comentario [index] (desde el panel). */
  async reveal(doc: vscode.TextDocument, index: number) {
    const c = this.byDoc.get(doc.uri.toString())?.[index];
    if (!c) return;
    const ed = await vscode.window.showTextDocument(doc);
    ed.revealRange(new vscode.Range(c.line, 0, c.line, 0), vscode.TextEditorRevealType.InCenter);
    ed.selection = new vscode.Selection(c.line, 0, c.line, 0);
  }

  comments(doc: vscode.TextDocument | undefined) {
    return doc ? this.byDoc.get(doc.uri.toString()) ?? [] : [];
  }

  private find(thread: vscode.CommentThread) {
    for (const list of this.byDoc.values()) {
      const c = list.find((x) => x.thread === thread);
      if (c) return c;
    }
    return undefined;
  }

  /**
   * Al escribir: los comentarios siguen a su línea (si insertas líneas arriba, bajan con ella) y, tras una pausa, se
   * resuelven solos los que hablan de un código que ya cambiaste.
   */
  private onEdit(e: vscode.TextDocumentChangeEvent) {
    const list = this.byDoc.get(e.document.uri.toString());
    if (!list?.length || e.contentChanges.length === 0) return;
    for (const ch of e.contentChanges) {
      const delta = ch.text.split("\n").length - 1 - (ch.range.end.line - ch.range.start.line);
      if (delta === 0) continue;
      for (const c of list) {
        const next = followLine(c.line, ch.range.start.line, ch.range.end.line, ch.range.end.character, delta);
        if (next !== c.line) {
          c.line = next;
          c.thread.range = new vscode.Range(c.line, 0, c.line, 0);
        }
      }
    }
    this.paint();
    clearTimeout(this.staleTimer);
    this.staleTimer = setTimeout(() => this.resolveStale(e.document), 800);
  }

  private resolveStale(doc: vscode.TextDocument) {
    const list = [...(this.byDoc.get(doc.uri.toString()) ?? [])];
    const lines = doc.getText().split("\n");
    for (const c of list) {
      if (c.busy || c.replies.length) continue; // si estás conversando sobre él, lo decides tú
      const gone = c.line >= lines.length;
      if (gone || core.raw.isOutdated(c.message, c.origin, around(lines, c.line))) this.resolve(c.thread, true);
    }
  }

  /** Subraya las líneas comentadas en los editores visibles. Dibujar decoraciones no hace que VS Code reanalice nada. */
  private paint() {
    for (const ed of vscode.window.visibleTextEditors) {
      const list = this.byDoc.get(ed.document.uri.toString()) ?? [];
      for (const [type, deco] of this.decorations) {
        const ranges = list.filter((c) => c.type === type && c.line < ed.document.lineCount).map((c) => {
          const l = ed.document.lineAt(c.line);
          return new vscode.Range(c.line, l.firstNonWhitespaceCharacterIndex, c.line, l.text.trimEnd().length);
        });
        ed.setDecorations(deco, ranges);
      }
      for (const [type, border] of this.borders) {
        ed.setDecorations(border, list.filter((c) => c.type === type && c.line < ed.document.lineCount).map((c) => ({
          range: new vscode.Range(c.line, 0, c.line, 0),
          hoverMessage: new vscode.MarkdownString(`**${c.typeLabel} (Mentor)** · ${c.message}`),
        })));
      }
    }
    this.lensesChanged.fire();
  }

  dispose() {
    clearTimeout(this.staleTimer);
    this.disposables.forEach((d) => d.dispose());
  }
}

function comment(author: string, text: string, isUser = false): vscode.Comment {
  return { body: new vscode.MarkdownString(text), mode: vscode.CommentMode.Preview, author: { name: isUser ? "Tú" : author === "Mentor" ? "Mentor" : `Mentor · ${author}` } };
}
