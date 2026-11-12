import * as vscode from "vscode";
import { core } from "./core";
import { Entry, Mentor } from "./mentor";
import { Reviews } from "./review";
import { Hints } from "./hints";
import { Guide } from "./guide";
import { brandOfPlanFile, brandOfProvider, brandSvg } from "./brands";

/**
 * Panel lateral del Mentor, con dos pestañas como en Android Studio: «Conversación» (encendido por archivo, contexto,
 * chat) y «Consumo». La extensión arma el HTML y el panel solo reemplaza lo que cambia: así no pierdes lo que estás
 * escribiendo ni dónde estabas leyendo.
 */
export class Panel implements vscode.WebviewViewProvider {
  private view?: vscode.WebviewView;
  private tab: "chat" | "usage" = "chat";

  constructor(private readonly mentor: Mentor, private readonly reviews: Reviews, private readonly hints: Hints, private readonly guide: Guide) {
    mentor.onDidChange(() => this.render());
  }

  resolveWebviewView(view: vscode.WebviewView) {
    this.view = view;
    view.webview.options = { enableScripts: true };
    view.webview.html = shell(nonce());
    view.webview.onDidReceiveMessage((m) => this.onMessage(m));
    view.onDidChangeVisibility(() => view.visible && this.render());
    this.render();
  }

  reveal() {
    void vscode.commands.executeCommand("mentor.panel.focus");
  }

  private get doc(): vscode.TextDocument | undefined {
    const ed = vscode.window.activeTextEditor;
    return ed && ed.document.uri.scheme === "file" ? ed.document : undefined;
  }

  private async onMessage(m: { type: string; text?: string; tab?: string; arg?: string }) {
    const doc = this.doc;
    switch (m.type) {
      case "ask": return this.mentor.ask(doc, m.text ?? "");
      case "review": return doc && this.reviews.review(doc);
      case "hint": return doc && this.hints.request(doc);
      case "hintForce": return doc && this.hints.request(doc, true);
      case "giveUp": return doc && this.hints.giveUp(doc);
      case "hideHint": return this.hints.hide(doc);
      case "newProblem": return this.hints.reset(doc);
      case "reveal": return doc && this.reviews.reveal(doc, Number(m.arg));
      case "clearReview": if (doc) { this.reviews.clear(doc); this.render(); } return;
      case "toggle": return doc && this.mentor.toggle(doc);
      case "provider": return vscode.commands.executeCommand("mentor.chooseProvider");
      case "model": return vscode.commands.executeCommand("mentor.chooseModel");
      case "session": return this.mentor.chooseSession(doc);
      case "paste": return this.mentor.pastePlan(doc);
      case "copyResume": return this.mentor.copyResume(doc);
      case "useOld": return this.mentor.useOld(doc);
      case "clearPaste": return this.mentor.clearPastedPlan(doc);
      case "test": return this.mentor.testConnection();
      case "setKey": return vscode.commands.executeCommand("mentor.setApiKey");
      case "login": return vscode.commands.executeCommand("mentor.loginCli");
      case "clear": return this.mentor.clearChat(doc);
      case "resetUsage": return this.mentor.resetUsage();
      case "tab": this.tab = m.tab === "usage" ? "usage" : "chat"; return this.render();
      case "ready": return this.render();
    }
  }

  /** Vuelve a dibujar, solo si el panel se ve (con el panel cerrado no se calcula nada). */
  render() {
    if (!this.view?.visible) return;
    const doc = this.doc;
    void this.view.webview.postMessage({
      type: "render",
      tab: this.tab,
      header: this.header(doc),
      body: this.tab === "chat" ? this.chatHtml(doc) : this.usageHtml(),
      busy: this.mentor.isBusy(doc),
      model: brandSvg(brandOfProvider(this.mentor.config.providerId)) + esc(this.mentor.config.label()) + " ▾",
      conn: this.connectionHtml(),
      actions: this.actions(doc),
    });
  }

  /** Si el Mentor está conectado, siempre a la vista: punto verde, rojo (con el botón que lo arregla) o gris. */
  private connectionHtml(): string {
    const c = this.mentor.connection;
    const ago = c.at ? ` · ${agoText(c.at)}` : "";
    switch (c.state) {
      case "ok": return `<span class="dot ok"></span>${esc(c.message)}${ago} · <a data-action="test">probar de nuevo</a>`;
      case "testing": return `<span class="dot"></span>Probando la conexión…`;
      case "error": {
        const fix = c.action === "key" ? `<a data-action="setKey">Poner la clave</a>` : c.action === "login" ? `<a data-action="login">Iniciar sesión en la terminal</a>` : `<a data-action="provider">Elegir proveedor</a>`;
        return `<span class="dot bad"></span><span class="error">Sin conexión:</span> ${esc(c.message)} · ${fix} · <a data-action="test">probar</a>`;
      }
      default: return `<span class="dot"></span>Conexión sin probar · <a data-action="test">Probar conexión</a>`;
    }
  }

  /** Lo que puedes pedirle sobre el archivo. Sin archivo, nada (nunca un botón que no hace nada). */
  private actions(doc: vscode.TextDocument | undefined): string {
    if (!doc) return "";
    const next = this.hints.nextLevel(doc);
    const hintBusy = this.hints.isBusy(doc);
    const hint = hintBusy ? `<span class="muted">Pensando la pista…</span>`
      : !next ? `<span class="muted">Sin más pistas</span>`
      : `<a data-action="hint" title="Te guía sin darte la solución: pregunta → concepto → herramienta → pasos (⌘⌥⇧H)">${next.number === 1 ? "Dame una pista" : `Otra pista (${next.number}/${this.hints.total()})`}</a>`;
    const review = this.reviews.isBusy(doc) ? `<span class="muted">Revisando…</span>`
      : `<a data-action="review" title="El Mentor comenta bajo las líneas de este archivo">${this.reviews.summary(doc) ? "Actualizar revisión" : "Revisar archivo"}</a>`;
    return [hint, review, `<a data-action="giveUp" title="Te explica la solución paso a paso">Me rindo</a>`].join(" · ");
  }

  private header(doc: vscode.TextDocument | undefined): string {
    if (!doc) return `<div class="file">Sin archivo abierto</div><div class="muted">Abre un archivo de código para usar el Mentor.</div>`;
    const on = this.mentor.isOn(doc);
    const name = esc(doc.uri.path.split("/").pop() ?? "");
    const status = on
      ? "Activo en este archivo: lee tu plan y las reglas del proyecto."
      : "Apagado en este archivo: nada aparece solo. Enciéndelo cuando lo necesites.";
    return `<div class="row"><div class="file">${name} <span class="muted">· ${esc(core.languageOf(this.mentor.relativePath(doc)))}</span></div>` +
      `<button class="switch ${on ? "on" : ""}" data-action="toggle" title="Encender o apagar el Mentor en este archivo (⌘⌥⇧T)">${on ? "Activo" : "Apagado"}</button></div>` +
      `<div class="muted">${status}</div>`;
  }

  private chatHtml(doc: vscode.TextDocument | undefined): string {
    let h = this.contextLine(doc);
    const entries = this.mentor.chat(doc);
    const working = this.mentor.isBusy(doc) || this.reviews.isBusy(doc) || this.hints.isBusy(doc);
    if (entries.length === 0 && !working) h += guide();
    const lastReview = entries.filter((e) => e.role === "review").at(-1);
    for (const e of entries) {
      if (e.role === "review") h += doc ? this.reviewEntry(doc, e === lastReview) : "";
      else if (e.role === "hint") h += this.hintEntry(doc, e.at);
      else h += entry(e);
    }
    const notice = this.hints.ladder(doc)?.notice;
    if (notice) h += `<div class="entry"><span class="warn">${esc(notice)}</span> <a data-action="hintForce">Necesito otra pista igual</a></div>`;
    if (this.reviews.isBusy(doc)) h += `<div class="entry muted">Mentor · revisando el archivo… <span class="dots"></span></div>`;
    if (this.hints.isBusy(doc)) h += `<div class="entry muted">Mentor · pensando… <span class="dots"></span></div>`;
    if (this.mentor.isBusy(doc)) h += `<div class="entry muted">Mentor · escribiendo… <span class="dots"></span></div>`;
    const links: string[] = [];
    if (this.hints.ladder(doc)?.hints.length) {
      links.push(`<a data-action="newProblem">Nuevo problema (reinicia las pistas)</a>`);
      if (this.hints.ladder(doc)?.visible) links.push(`<a data-action="hideHint">Quitar la pista del editor</a>`);
    }
    if (entries.length) links.push(`<a data-action="clear">Borrar la conversación</a>`);
    if (links.length) h += `<div class="links">${links.join(" · ")}</div>`;
    return h;
  }

  /** La revisión: cómo vas, cada comentario abierto con un enlace a su línea y lo que se resolvió solo. */
  private reviewEntry(doc: vscode.TextDocument, latest: boolean): string {
    const s = this.reviews.summary(doc);
    if (!latest || !s) return `<div class="entry muted">Mentor · revisión anterior (la reemplazó una más nueva)</div>`;
    let b = `<div class="head">Mentor · revisión de ${esc(doc.uri.path.split("/").pop() ?? "")}</div><b>${esc(s.statusLabel)}</b>`;
    if (s.summary) b += ` · ${md(s.summary)}`;
    const open = this.reviews.comments(doc);
    b += open.map((c, i) =>
      `<div class="comment ${esc(c.type)}"><a data-action="reveal" data-arg="${i}">L${c.line + 1}</a> <b>${esc(c.typeLabel)}</b> ${md(c.message)}</div>`).join("");
    if (s.total > 0 && open.length === 0) b += `<div class="ok">✓ Todos los comentarios resueltos</div>`;
    if (s.autoResolved) b += `<div class="muted">✓ ${s.autoResolved} se resolvieron solos: cambiaste el código del que hablaban</div>`;
    if (s.nextStep) b += `<div><b>Siguiente paso:</b> ${md(s.nextStep)}</div>`;
    b += `<div class="muted">Responde cada comentario en su caja, bajo la línea. <a data-action="clearReview">Quitar esta revisión</a></div>`;
    return `<div class="entry card">${b}</div>`;
  }

  private hintEntry(doc: vscode.TextDocument | undefined, at: number): string {
    const h = this.hints.ladder(doc)?.hints.find((x) => x.at === at);
    if (!h) return "";
    let b = `<div class="head">Mentor · pista ${h.number}/${this.hints.total()} · ${esc(h.levelLabel)}</div>L${h.line} ${md(h.text)}`;
    if (h.redacted) b += `<div class="muted">Oculté un fragmento con código de tu ejercicio.</div>`;
    return `<div class="entry card info">${b}</div>`;
  }

  /** Qué está leyendo el Mentor: así sabes en qué se basa lo que te dice. */
  private contextLine(doc: vscode.TextDocument | undefined): string {
    if (!doc || !this.mentor.isOn(doc)) return "";
    const found = this.mentor.foundFor(doc);
    const parts = [`<span title="${esc(this.mentor.relativePath(doc))}">${esc(this.mentor.relativePath(doc))}</span>`];
    const step = this.guide.summary(doc);
    if (step) parts.push(esc(step));
    if (!this.mentor.config.readAiSession) parts.push("sin leer conversaciones (Ajustes)");
    else if (found) {
      const pasted = found.source === "Plan pegado";
      parts.push(`${brandSvg(brandOfPlanFile(found.file))}<b>${esc(found.source)}</b>: «${esc(found.lastRequest ?? "conversación")}»` +
        (found.oldAgo ? ` · <span class="warn">de ${esc(found.oldAgo)}</span>` : "") + ` · ` +
        `${found.automatic ? "elegida sola" : "elegida por ti"} · <a data-action="session">cambiar</a>` + (pasted ? ` · <a data-action="clearPaste">quitar</a>` : ""));
    } else {
      const head = `<div class="context">Contexto: ${parts.join(" · ")}</div>`;
      const old = this.mentor.oldFor(doc);
      if (old) {
        // Retomas el proyecto: lo normal es un chat nuevo con tu IA. Se ayuda a empezarlo con el plan anterior.
        return head + `<div class="card warncard"><b>Estás retomando este proyecto.</b> Su última conversación es de <b>${esc(old.ago)}</b> ` +
          `(${brandSvg(old.brand)}${esc(old.source)}${old.title ? ` · «${esc(old.title)}»` : ""}).<br>` +
          `<span class="muted">Abre un <b>chat nuevo</b> con tu IA y pídele el siguiente plan: el Mentor lo leerá solo en cuanto exista. ` +
          `Para que no empiece de cero, copia el resumen: el plan anterior, qué pasos ya están hechos según tu código y el pedido de los siguientes.</span><br>` +
          `<a data-action="copyResume">Copiar resumen para tu IA</a> · <a data-action="useOld">Usar la conversación vieja igual</a> · <a data-action="paste">Pegar un plan</a></div>`;
      }
      // Ninguna conversación del proyecto en ninguna IA: se dice tal cual y qué hacer.
      return head + `<div class="card warncard"><b>Este proyecto no tiene conversaciones con Claude Code, GitHub Copilot ni Antigravity.</b><br>` +
        `<span class="muted">Pídele a tu IA un plan con pasos para este proyecto: el Mentor lo leerá solo. Si usas otra IA (Gemini o ChatGPT en el navegador…), ` +
        `copia su plan y pégalo aquí.</span><br><a data-action="paste">Pegar el plan</a> · <a data-action="session">Elegir conversación</a></div>`;
    }
    return `<div class="context">Contexto: ${parts.join(" · ")}</div>`;
  }

  private usageHtml(): string {
    const totals = this.mentor.usage();
    const kinds = Object.entries(totals);
    const provider = core.provider(this.mentor.config.providerId);
    let h = `<div class="card"><b>Consumo</b> <span class="muted">· ${esc(this.mentor.config.label())} · <a data-action="provider">cambiar</a></span><br>`;
    h += provider.subscription
      ? `<span class="muted">Con tu suscripción no hay costo por llamada: gasta la cuota de tu plan. Aquí ves los tokens.</span>`
      : `<span class="muted">Costo según lo que informa el proveedor (OpenRouter lo da exacto); si no lo informa, ves solo tokens.</span>`;
    if (provider.billingUrl) h += ` <a href="${esc(provider.billingUrl)}">Ver mi cuenta</a>`;
    h += `</div>`;
    if (kinds.length === 0) return h + `<div class="muted">Todavía no hay llamadas.</div>`;
    const label: Record<string, string> = { chat: "Chat", review: "Revisión", hint: "Pistas", giveup: "Me rindo", step: "Comprobar paso", test: "Prueba de conexión" };
    h += `<table><tr><th></th><th>Llamadas</th><th>Entrada</th><th>Salida</th><th>Costo</th></tr>`;
    let calls = 0, input = 0, output = 0, cost = 0;
    for (const [k, t] of kinds) {
      calls += t.calls; input += t.input; output += t.output; cost += t.cost;
      h += `<tr><td>${esc(label[k] ?? k)}</td><td>${t.calls}</td><td>${core.formatTokens(t.input)}</td><td>${core.formatTokens(t.output)}</td><td>${t.cost ? core.formatUsd(t.cost) : "—"}</td></tr>`;
    }
    h += `<tr class="total"><td>Total</td><td>${calls}</td><td>${core.formatTokens(input)}</td><td>${core.formatTokens(output)}</td><td>${cost ? core.formatUsd(cost) : "—"}</td></tr></table>`;
    return h + `<div class="links"><a data-action="resetUsage">Reiniciar el conteo</a></div>`;
  }
}

function guide(): string {
  return `<div class="card info"><b>Cómo usar el Mentor</b><br><br>` +
    `<b>1. Enciéndelo cuando lo necesites</b> con el botón de arriba o <code>⌘⌥⇧T</code>. Encendido, lee el plan que te dio tu otra IA (Claude Code) y las reglas de tu proyecto (<code>AGENTS.md</code>).<br><br>` +
    `<b>2. Pregúntale lo que quieras</b> abajo: te guía con pistas y preguntas, no te escribe la solución.<br><br>` +
    `<b>3. Elige con quién piensa</b> en el pie del panel: tu clave de API o tu suscripción (Claude Code, Cursor).</div>`;
}

function entry(e: Entry): string {
  if (e.role === "giveup") return `<div class="entry"><div class="head">Mentor · solución explicada</div>${md(e.text)}</div>`;
  if (e.role === "user") return `<div class="entry"><div class="bubble">${md(e.text)}</div></div>`;
  if (e.role === "error") return `<div class="entry error">${esc(e.text)} <a data-action="provider">Revisar proveedor</a></div>`;
  return `<div class="entry"><div class="head">Mentor</div>${md(e.text)}</div>`;
}

function agoText(at: number): string {
  const m = Math.round((Date.now() - at) / 60_000);
  return m < 1 ? "ahora" : m < 60 ? `hace ${m} min` : `hace ${Math.round(m / 60)} h`;
}

export function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/** Markdown mínimo de las respuestas: bloques de código, `código`, **negrita** y saltos de línea. */
export function md(text: string): string {
  const parts = text.split(/```[^\n]*\n?/);
  return parts.map((p, i) => {
    if (i % 2 === 1) return `<pre><code>${esc(p.replace(/\n$/, ""))}</code></pre>`;
    return esc(p)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
      .replace(/\n/g, "<br>");
  }).join("");
}

function nonce(): string {
  let s = "";
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  for (let i = 0; i < 32; i++) s += chars.charAt(Math.floor(Math.random() * chars.length));
  return s;
}

/** El esqueleto del panel: estilos con los colores del tema de VS Code y el script que dibuja y envía acciones. */
function shell(n: string): string {
  return `<!DOCTYPE html><html lang="es"><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${n}';">
<style>
  body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); padding: 0; margin: 0; display: flex; flex-direction: column; height: 100vh; }
  header { padding: 8px 10px 6px; border-bottom: 1px solid var(--vscode-panel-border); }
  .row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  .file { font-weight: 600; }
  .muted { color: var(--vscode-descriptionForeground); }
  .tabs { display: flex; gap: 14px; margin-top: 8px; }
  .tabs a { padding-bottom: 3px; color: var(--vscode-descriptionForeground); }
  .tabs a.sel { color: var(--vscode-foreground); border-bottom: 2px solid var(--vscode-focusBorder); }
  main { flex: 1; overflow-y: auto; padding: 8px 10px; }
  a { color: var(--vscode-textLink-foreground); cursor: pointer; text-decoration: none; }
  a:hover { text-decoration: underline; }
  .switch { border: 1px solid var(--vscode-button-border, transparent); background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); border-radius: 12px; padding: 2px 10px; cursor: pointer; }
  .switch.on { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
  .context { color: var(--vscode-descriptionForeground); margin-bottom: 10px; font-size: 0.92em; word-break: break-word; }
  .entry { margin: 10px 0; line-height: 1.45; }
  .head { color: var(--vscode-textLink-foreground); font-weight: 600; margin-bottom: 2px; }
  .bubble { background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, transparent); border-radius: 10px; padding: 6px 10px; margin-left: 24px; }
  .card { background: var(--vscode-editorWidget-background); border-left: 3px solid var(--vscode-focusBorder); border-radius: 6px; padding: 8px 10px; margin: 8px 0; }
  .error { color: var(--vscode-errorForeground); }
  .warn { color: var(--vscode-editorWarning-foreground); }
  .ok { color: var(--vscode-testing-iconPassed, #5DBE8A); margin-top: 6px; }
  .comment { border-left: 3px solid var(--vscode-panel-border); padding: 2px 0 2px 8px; margin: 6px 0; }
  .comment.error { border-color: #EC7C69; color: inherit; } .comment.mejora { border-color: #E3A443; }
  .comment.pregunta { border-color: #8DB8F2; } .comment.bien { border-color: #5DBE8A; }
  .actions { margin-bottom: 6px; }
  svg.brand { width: 14px; height: 14px; vertical-align: -2px; margin-right: 4px; }
  .conn { margin-top: 6px; font-size: 0.92em; color: var(--vscode-descriptionForeground); }
  .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; background: var(--vscode-descriptionForeground); vertical-align: 0; }
  .dot.ok { background: #5DBE8A; } .dot.bad { background: #EC7C69; }
  .warncard { border-left-color: var(--vscode-editorWarning-foreground); }
  code { font-family: var(--vscode-editor-font-family); background: var(--vscode-textCodeBlock-background); padding: 0 3px; border-radius: 3px; }
  pre { background: var(--vscode-textCodeBlock-background); padding: 8px; border-radius: 6px; overflow-x: auto; }
  pre code { background: none; padding: 0; }
  .links { margin-top: 10px; color: var(--vscode-descriptionForeground); }
  table { border-collapse: collapse; width: 100%; margin-top: 6px; }
  th, td { text-align: right; padding: 3px 4px; } th:first-child, td:first-child { text-align: left; }
  tr.total td { font-weight: 600; border-top: 1px solid var(--vscode-panel-border); }
  footer { border-top: 1px solid var(--vscode-panel-border); padding: 8px 10px; }
  textarea { width: 100%; box-sizing: border-box; resize: vertical; min-height: 52px; font-family: inherit; font-size: inherit; color: var(--vscode-input-foreground); background: var(--vscode-input-background); border: 1px solid var(--vscode-input-border, var(--vscode-panel-border)); border-radius: 8px; padding: 6px 8px; }
  textarea:focus { outline: 1px solid var(--vscode-focusBorder); }
  .bar { display: flex; justify-content: space-between; align-items: center; margin-top: 6px; gap: 8px; }
  button.send { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; border-radius: 6px; padding: 4px 14px; cursor: pointer; }
  button.send:disabled { opacity: 0.5; cursor: default; }
  .dots::after { content: "…"; animation: blink 1.2s infinite; } @keyframes blink { 50% { opacity: 0.2; } }
</style></head><body>
<header><div id="header"></div><div class="tabs"><a data-tab="chat" class="sel">Conversación</a><a data-tab="usage">Consumo</a></div></header>
<main id="body"></main>
<footer>
  <div class="actions" id="actions"></div>
  <textarea id="input" placeholder="Pregúntale al Mentor… (Enter envía · Mayús+Enter salta de línea)"></textarea>
  <div class="bar"><a data-action="model" id="model" title="Elegir modelo, proveedor o clave"></a><button class="send" id="send">Enviar</button></div>
  <div class="conn" id="conn"></div>
</footer>
<script nonce="${n}">
  const vscode = acquireVsCodeApi();
  const input = document.getElementById("input"), send = document.getElementById("send"), body = document.getElementById("body");
  let lastTab = "chat", lastBody = "";
  function submit() { const t = input.value.trim(); if (!t || send.disabled) return; vscode.postMessage({ type: "ask", text: t }); input.value = ""; }
  send.addEventListener("click", submit);
  input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); submit(); } });
  document.addEventListener("click", (e) => {
    const a = e.target.closest("[data-action],[data-tab]"); if (!a) return;
    if (a.dataset.tab) return vscode.postMessage({ type: "tab", tab: a.dataset.tab });
    vscode.postMessage({ type: a.dataset.action, arg: a.dataset.arg });
  });
  window.addEventListener("message", (ev) => {
    const m = ev.data; if (m.type !== "render") return;
    document.getElementById("header").innerHTML = m.header;
    document.querySelectorAll("[data-tab]").forEach((t) => t.classList.toggle("sel", t.dataset.tab === m.tab));
    document.getElementById("model").innerHTML = m.model;
    document.getElementById("conn").innerHTML = m.conn;
    document.getElementById("actions").innerHTML = m.actions;
    send.disabled = m.busy;
    if (m.body !== lastBody) {
      // Si llegó algo nuevo a la conversación, baja al final; si no, respeta dónde estabas leyendo.
      const atEnd = body.scrollTop + body.clientHeight >= body.scrollHeight - 30;
      body.innerHTML = m.body; lastBody = m.body;
      if (atEnd || m.tab !== lastTab) body.scrollTop = body.scrollHeight;
    }
    lastTab = m.tab;
  });
  vscode.postMessage({ type: "ready" });
</script></body></html>`;
}
