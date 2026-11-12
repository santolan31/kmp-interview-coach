import * as vscode from "vscode";
import { Config } from "./config";
import { core } from "./core";
import { Mentor } from "./mentor";
import { Panel } from "./panel";
import { Reviews } from "./review";
import { Hints } from "./hints";
import { Guide } from "./guide";
import { brandIcon, brandOfProvider, initBrands } from "./brands";

/**
 * Punto de entrada de la extensión. Conecta el estado del Mentor con el panel lateral, el botón de la barra de estado
 * y los comandos. Funciona igual en VS Code, Cursor y Antigravity (son derivados de VS Code).
 */
export function activate(context: vscode.ExtensionContext) {
  initBrands(context.extensionPath);
  const config = new Config(context.secrets);
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath ?? null;
  const mentor = new Mentor(config, context.globalState, root);
  const reviews = new Reviews(mentor);
  const hints = new Hints(mentor);
  const guide = new Guide(mentor);
  const panel = new Panel(mentor, reviews, hints, guide);
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
    vscode.workspace.onDidChangeConfiguration((e) => e.affectsConfiguration("mentor") && panel.render()),

    vscode.commands.registerCommand("mentor.toggle", async () => {
      const doc = activeDoc();
      if (!doc) return void vscode.window.showInformationMessage("Abre un archivo de código para encender el Mentor.");
      await mentor.toggle(doc);
      if (mentor.isOn(doc)) panel.reveal();
    }),
    vscode.commands.registerCommand("mentor.openPanel", () => panel.reveal()),
    vscode.commands.registerCommand("mentor.chooseProvider", () => chooseProvider(config).then(() => panel.render())),
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

export function deactivate() {}

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
    if (p.subscription) {
      const found = core.findCli(p.id, baseUrl);
      detail = found ? `Suscripción · usa tu sesión de ${p.executable} (${found}) · sin costo por llamada, unos segundos más lento` : `Suscripción · no encontré ${p.executable} en este equipo`;
    } else {
      detail = !p.needsKey ? "Sin clave" : (await config.hasKey(p.id)) ? "Clave de API guardada" : "Clave de API: te la pido al elegirlo";
    }
    return { label: p.label, description: p.id === config.providerId ? "actual" : undefined, detail, iconPath: brandIcon(brandOfProvider(p.id)), provider: p };
  }));
  const pick = await vscode.window.showQuickPick(items, { title: "¿Con quién piensa el Mentor?", matchOnDetail: true });
  if (!pick) return;
  const p = pick.provider;
  await config.setProvider(p.id);
  if (p.needsKey && !(await config.hasKey(p.id))) {
    const key = await vscode.window.showInputBox({
      title: `Clave de API de ${p.label}`,
      prompt: "Se guarda en el almacén seguro de VS Code, solo en este equipo. Nunca va en los ajustes ni en el código.",
      password: true,
      ignoreFocusOut: true,
    });
    if (key) await config.setKey(p.id, key);
  }
  if (p.subscription) {
    const found = core.findCli(p.id, baseUrl);
    const login = p.id === "cursor-cli" ? "cursor-agent login" : p.executable;
    void vscode.window.showInformationMessage(found
      ? `El Mentor usará tu suscripción a través de ${p.executable}. Si es la primera vez, abre una terminal y ejecuta «${login}» para iniciar sesión.`
      : `No encontré ${p.executable}. Instálalo, o escribe su ruta en Ajustes › Mentor › Base Url.`);
  }
}

/** Elegir modelo: la lista del proveedor (o del programa de la suscripción), o escribir uno a mano. */
async function chooseModel(config: Config) {
  const cfg = await config.aiConfig();
  const result = await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: "Cargando modelos…" },
    () => core.listModels(cfg),
  );
  const custom = "$(edit) Escribir el nombre de otro modelo…";
  const models = result.ok ? result.models : [];
  if (!result.ok) void vscode.window.showWarningMessage(result.error);
  const pick = await vscode.window.showQuickPick([custom, ...models], { title: `Modelo (${core.provider(cfg.provider).label})`, placeHolder: config.model || "modelo por defecto" });
  if (!pick) return;
  const model = pick === custom ? await vscode.window.showInputBox({ title: "Nombre del modelo", value: config.model }) : pick;
  if (model !== undefined) await config.setModel(model.trim());
}
