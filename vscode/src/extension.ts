import * as path from "path";
import * as vscode from "vscode";
import { Config } from "./config";
import { core } from "./core";
import { Mentor, offerFix } from "./mentor";
import { Panel } from "./panel";
import { Reviews } from "./review";
import { Hints } from "./hints";
import { Guide } from "./guide";
import { brandIcon, brandOfProvider, initBrands } from "./brands";
import { copilotModels } from "./copilot";

/**
 * Punto de entrada de la extensión. Conecta el estado del Mentor con el panel lateral, el botón de la barra de estado
 * y los comandos. Funciona igual en VS Code, Cursor y Antigravity (son derivados de VS Code).
 */
export function activate(context: vscode.ExtensionContext) {
  initBrands(context.extensionPath);
  const config = new Config(context.secrets);
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? null;
  // Las conversaciones de GitHub Copilot de este proyecto viven junto a la carpeta de datos que el editor le da a la
  // extensión: …/workspaceStorage/<este proyecto>/chatSessions. Funciona en VS Code y en sus derivados.
  const copilotDir = context.storageUri ? path.join(path.dirname(context.storageUri.fsPath), "chatSessions") : null;
  const mentor = new Mentor(config, context.globalState, context.workspaceState, root, copilotDir);
  const reviews = new Reviews(mentor);
  const hints = new Hints(mentor);
  const guide = new Guide(mentor);
  const panel = new Panel(mentor, reviews, hints, guide);
  ready = readiness(config, mentor);
  const needDoc = (run: (doc: vscode.TextDocument) => unknown) => () => {
    const doc = activeDoc();
    if (!doc) return void vscode.window.showInformationMessage("Abre un archivo de código para usar el Mentor.");
    panel.reveal();
    return run(doc);
  };

  // Botón en la barra de estado: dice si el Mentor está encendido en el archivo y lo enciende o apaga con un clic.
  const status = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  status.command = "mentor.toggle";
  const syncStatus = () => {
    const doc = activeDoc();
    if (!doc) return status.hide();
    const on = mentor.isOn(doc);
    status.text = on ? "$(mortar-board) Mentor: activo" : "$(mortar-board) Mentor: apagado";
    status.tooltip = on ? "Apagar el Mentor en este archivo (⌘⌥⇧T)" : "Encender el Mentor en este archivo (⌘⌥⇧T)";
    status.show();
  };

  context.subscriptions.push(
    mentor,
    reviews,
    hints,
    guide,
    status,
    // La guía de pasos: una línea con el paso y sus acciones arriba de cada archivo con el Mentor encendido.
    vscode.languages.registerCodeLensProvider({ scheme: "file" }, guide),
    vscode.commands.registerCommand("mentor.guide.goTo", (s: number, e: number) => guide.goTo(s, e)),
    vscode.commands.registerCommand("mentor.guide.goToLine", (file: string, line: number) => guide.goToLine(file, line)),
    vscode.commands.registerCommand("mentor.guide.skeleton", (s: number, e: number) => guide.placeSkeleton(s, e)),
    vscode.commands.registerCommand("mentor.guide.verify", (s: number) => guide.verify(s)),
    vscode.commands.registerCommand("mentor.guide.explain", (s: number) => guide.explain(s)),
    vscode.commands.registerCommand("mentor.guide.advance", (s: number) => guide.advance(s)),
    vscode.commands.registerCommand("mentor.guide.done", (s: number) => guide.markDone(s)),
    vscode.window.registerWebviewViewProvider("mentor.panel", panel, { webviewOptions: { retainContextWhenHidden: true } }),
    mentor.onDidChange(syncStatus),
    vscode.window.onDidChangeActiveTextEditor(async (ed) => {
      syncStatus();
      if (ed && mentor.isOn(ed.document)) await mentor.refreshSession(ed.document);
      panel.render();
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (!e.affectsConfiguration("mentor")) return;
      // Otro proveedor u otra ruta tienen otros modelos: la lista del desplegable se vuelve a pedir.
      if (["mentor.provider", "mentor.baseUrl"].some((k) => e.affectsConfiguration(k))) mentor.forgetModels();
      panel.render();
      // Cambiaste el proveedor, el modelo o la ruta (también desde Ajustes): se prueba y se avisa si quedó conectado.
      if (["mentor.provider", "mentor.model", "mentor.baseUrl"].some((k) => e.affectsConfiguration(k))) ready.soon();
    }),
    vscode.commands.registerCommand("mentor.setApiKey", async () => {
      if (!(await setApiKey(config))) return;
      mentor.forgetModels(); // con la clave nueva quizá ya se puede leer la lista de modelos
      await mentor.testConnection();
    }),
    vscode.commands.registerCommand("mentor.testConnection", () => mentor.testConnection()),
    vscode.commands.registerCommand("mentor.loginCli", () => loginCli(config, mentor)),
    vscode.commands.registerCommand("mentor.pastePlan", () => mentor.pastePlan(activeDoc())),
    vscode.commands.registerCommand("mentor.clearPastedPlan", () => mentor.clearPastedPlan(activeDoc())),

    vscode.commands.registerCommand("mentor.toggle", async () => {
      const doc = activeDoc();
      if (!doc) return void vscode.window.showInformationMessage("Abre un archivo de código para encender el Mentor.");
      // El cambio de estado es inmediato; la búsqueda de la conversación sigue mientras ya ves el panel.
      const searching = mentor.toggle(doc);
      if (mentor.isOn(doc)) panel.reveal();
      await searching;
    }),
    vscode.commands.registerCommand("mentor.openPanel", () => panel.reveal()),
    vscode.commands.registerCommand("mentor.chooseProvider", async () => {
      ready.paused = true;
      try { await chooseProvider(config); } finally { ready.paused = false; }
      mentor.forgetModels(); // la lista se pide de nuevo, ya con la clave que acabas de poner
      await ready.check();
    }),
    vscode.commands.registerCommand("mentor.chooseModel", () => chooseModel(config).then(() => panel.render())),
    vscode.commands.registerCommand("mentor.chooseSession", () => mentor.chooseSession(activeDoc())),
    vscode.commands.registerCommand("mentor.review", needDoc((doc) => reviews.review(doc))),
    vscode.commands.registerCommand("mentor.hint", needDoc((doc) => hints.request(doc))),
    vscode.commands.registerCommand("mentor.giveUp", needDoc((doc) => hints.giveUp(doc))),
    vscode.commands.registerCommand("mentor.hideHint", () => hints.hide(activeDoc())),
    // Desde la caja de un comentario del Mentor: responderle, o darlo por resuelto.
    vscode.commands.registerCommand("mentor.reply", (r: vscode.CommentReply) => reviews.reply(r)),
    vscode.commands.registerCommand("mentor.resolveComment", (t: vscode.CommentThread) => reviews.resolve(t)),
  );
  syncStatus();
}

/**
 * Después de cambiar proveedor, clave o modelo: si falta la clave, se pide; si no, se prueba la conexión y se avisa
 * del resultado. Se agrupan los cambios seguidos y se pausa mientras eliges proveedor (para no probar a medias).
 */
function readiness(config: Config, mentor: Mentor) {
  let timer: NodeJS.Timeout | undefined;
  let lastCheck = 0;
  const r = {
    paused: false,
    soon() {
      // El aviso de «ajustes cambiados» puede llegar justo después de elegir proveedor: no se prueba dos veces.
      if (r.paused || Date.now() - lastCheck < 2000) return;
      clearTimeout(timer);
      timer = setTimeout(() => void r.check(), 600);
    },
    async check() {
      lastCheck = Date.now();
      const p = core.provider(config.providerId);
      if (p.needsKey && !(await config.hasKey(p.id))) {
        const pick = await vscode.window.showWarningMessage(`El Mentor usa ${p.label}, pero falta tu clave de API.`, "Poner la clave", "Elegir otro proveedor");
        if (pick === "Poner la clave") await vscode.commands.executeCommand("mentor.setApiKey");
        if (pick === "Elegir otro proveedor") await vscode.commands.executeCommand("mentor.chooseProvider");
        return;
      }
      await mentor.testConnection();
    },
  };
  return r;
}
let ready: ReturnType<typeof readiness>;

export function deactivate() {}

/** Pide (o cambia) la clave del proveedor actual y la guarda en el almacén seguro. Devuelve si se guardó una. */
async function setApiKey(config: Config): Promise<boolean> {
  const p = core.provider(config.providerId);
  if (!p.needsKey) {
    const pick = await vscode.window.showInformationMessage(`${p.label} no usa clave de API.`, "Elegir otro proveedor");
    if (pick) await vscode.commands.executeCommand("mentor.chooseProvider");
    return false;
  }
  const had = await config.hasKey(p.id);
  const key = await vscode.window.showInputBox({
    title: `Clave de API de ${p.label}`,
    prompt: (had ? "Ya hay una clave guardada: escribe una nueva para reemplazarla. " : "") +
      "Se guarda en el almacén seguro de VS Code, solo en este equipo. Nunca va en los ajustes ni en el código.",
    placeHolder: p.id === "gemini" ? "Clave de Google AI Studio (aistudio.google.com)" : "Pega aquí tu clave",
    password: true,
    ignoreFocusOut: true,
  });
  if (!key?.trim()) return false;
  await config.setKey(p.id, key);
  return true;
}

/**
 * Iniciar sesión en la suscripción: abre una terminal y ejecuta el programa oficial (`claude`, `cursor-agent login`).
 * Tú inicias sesión ahí; el Mentor nunca ve tus credenciales.
 */
async function loginCli(config: Config, mentor: Mentor) {
  const p = core.provider(config.providerId);
  if (!p.subscription) return void vscode.window.showInformationMessage(`${p.label} usa clave de API, no inicio de sesión. Usa «Mentor: Poner o cambiar la clave de API».`);
  if (p.editor) {
    // Copilot usa la sesión de GitHub que ya tiene VS Code: no hay terminal; se inicia desde el ícono de Cuentas.
    const pick = await vscode.window.showInformationMessage(
      "GitHub Copilot usa tu sesión de GitHub en VS Code. Si no la tienes, inicia sesión desde el ícono de Cuentas (abajo a la izquierda) o desde el chat de Copilot. Luego prueba la conexión y acepta el permiso.",
      "Probar conexión",
    );
    if (pick) await mentor.testConnection();
    return;
  }
  const exe = core.findCli(p.id, vscode.workspace.getConfiguration("mentor").get<string>("baseUrl", ""));
  if (!exe) return void offerFix(`No encontré \`${p.executable}\` en este equipo. Instálalo o escribe su ruta en Ajustes › Mentor › Base Url.`, "provider");
  const terminal = vscode.window.createTerminal({ name: `Mentor · iniciar sesión (${p.executable})` });
  terminal.show();
  terminal.sendText(p.id === "cursor-cli" ? `"${exe}" login` : `"${exe}"`);
  const pick = await vscode.window.showInformationMessage(
    p.id === "cursor-cli" ? "Sigue los pasos en la terminal para iniciar sesión en Cursor. Cuando termines, prueba la conexión."
      : "En la terminal, escribe /login si te lo pide y sigue los pasos con tu cuenta. Cuando termines, prueba la conexión.",
    "Probar conexión",
  );
  if (pick) await mentor.testConnection();
}

function activeDoc(): vscode.TextDocument | undefined {
  const ed = vscode.window.activeTextEditor;
  return ed && ed.document.uri.scheme === "file" ? ed.document : undefined;
}

/**
 * Elegir proveedor: con clave de API (se pide y se guarda en el almacén seguro) o con tu suscripción (se busca el
 * programa del servicio; el Mentor nunca ve tus credenciales).
 */
async function chooseProvider(config: Config) {
  const baseUrl = vscode.workspace.getConfiguration("mentor").get<string>("baseUrl", "");
  const items = await Promise.all(core.providers().map(async (p) => {
    let detail: string;
    if (p.editor) {
      detail = "Suscripción · usa tu sesión de GitHub Copilot en VS Code · te pide permiso la primera vez · gasta tu cuota de Copilot";
    } else if (p.subscription) {
      const found = core.findCli(p.id, baseUrl);
      detail = found ? `Suscripción · usa tu sesión de ${p.executable} (${found}) · sin costo por llamada, unos segundos más lento` : `Suscripción · no encontré ${p.executable} en este equipo`;
    } else {
      detail = !p.needsKey ? "Sin clave" : (await config.hasKey(p.id)) ? "Clave de API guardada (cámbiala con «Mentor: Poner o cambiar la clave de API»)" : "Clave de API: te la pido al elegirlo";
    }
    return { label: p.label, description: p.id === config.providerId ? "actual" : undefined, detail, iconPath: brandIcon(brandOfProvider(p.id)), provider: p };
  }));
  const pick = await vscode.window.showQuickPick(items, { title: "¿Con quién piensa el Mentor?", matchOnDetail: true });
  if (!pick) return;
  const p = pick.provider;
  await config.setProvider(p.id);
  // Con clave: se pide si falta. Después se prueba la conexión y se avisa si quedó conectado (lo hace quien llama).
  if (p.needsKey && !(await config.hasKey(p.id))) await setApiKey(config);
}

/** Elegir modelo: la lista del proveedor (o del programa de la suscripción), o escribir uno a mano. */
async function chooseModel(config: Config) {
  const cfg = await config.aiConfig();
  const result = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: "Cargando modelos…" },
    () => (core.provider(cfg.provider).editor ? copilotModels() : core.listModels(cfg)),
  );
  const custom = "$(edit) Escribir el nombre de otro modelo…";
  const other = "$(key) Cambiar de proveedor o de clave…";
  const models = result.ok ? result.models : [];
  if (!result.ok) void vscode.window.showWarningMessage(result.error);
  const pick = await vscode.window.showQuickPick([custom, other, ...models], { title: `Modelo (${core.provider(cfg.provider).label})`, placeHolder: config.model || "modelo por defecto" });
  if (!pick) return;
  if (pick === other) return void vscode.commands.executeCommand("mentor.chooseProvider");
  const model = pick === custom ? await vscode.window.showInputBox({ title: "Nombre del modelo", value: config.model }) : pick;
  if (model !== undefined) await config.setModel(model.trim());
}
