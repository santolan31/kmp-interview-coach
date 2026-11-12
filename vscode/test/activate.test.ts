// La extensión empaquetada arranca sin errores y registra lo que promete el package.json. Se carga con un `vscode`
// simulado: no abre el editor, pero atrapa errores de arranque y comandos que faltan.
import { test } from "node:test";
import assert from "node:assert/strict";
import * as path from "path";
// eslint-disable-next-line @typescript-eslint/no-require-imports
const Module = require("module");

const registered: string[] = [];
const views: string[] = [];
const noop = () => ({ dispose() {} });
const emitter = class { event = () => noop(); fire() {} dispose() {} };
const vscodeStub = {
  EventEmitter: emitter,
  StatusBarAlignment: { Right: 2 },
  OverviewRulerLane: { Right: 4 },
  ThemeColor: class { constructor(public id: string) {} },
  comments: { createCommentController: () => ({ options: {}, dispose() {} }) },
  languages: { registerCodeLensProvider: noop, onDidChangeDiagnostics: noop, getDiagnostics: () => [] },
  commandsExecuted: [] as string[],
  ConfigurationTarget: { Global: 1 },
  ProgressLocation: { Notification: 15 },
  window: {
    activeTextEditor: undefined,
    createStatusBarItem: () => ({ show() {}, hide() {}, dispose() {} }),
    registerWebviewViewProvider: (id: string) => { views.push(id); return noop(); },
    createTextEditorDecorationType: () => ({ dispose() {} }),
    onDidChangeActiveTextEditor: noop,
    onDidChangeVisibleTextEditors: noop,
    visibleTextEditors: [],
    showInformationMessage: async () => undefined,
  },
  workspace: {
    workspaceFolders: undefined,
    getConfiguration: () => ({ get: (_k: string, d: unknown) => d, update: async () => {} }),
    onDidChangeConfiguration: noop,
    onDidChangeTextDocument: noop,
  },
  commands: {
    registerCommand: (id: string) => { registered.push(id); return noop(); },
    executeCommand: async () => undefined,
  },
};

const load = Module._load;
Module._load = function (request: string, ...rest: unknown[]) {
  return request === "vscode" ? vscodeStub : load.call(this, request, ...rest);
};

test("activa y registra el panel y todos los comandos del package.json", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const ext = require(path.resolve(__dirname, "../../dist/extension.js"));
  const subscriptions: unknown[] = [];
  ext.activate({ subscriptions, extensionPath: path.resolve(__dirname, "../.."), storageUri: undefined,
    workspaceState: { get: () => undefined, update: async () => {} }, secrets: { get: async () => undefined, store: async () => {} }, globalState: { get: (_k: string, d: unknown) => d, update: async () => {} } });
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const pkg = require(path.resolve(__dirname, "../../package.json"));
  const declared = pkg.contributes.commands.map((c: { command: string }) => c.command).sort();
  assert.deepEqual([...registered].sort(), declared);
  assert.deepEqual(views, ["mentor.panel"]);
});

test("cada proveedor y cada fuente de plan tiene su ícono", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const api = require(path.resolve(__dirname, "../../../core/build/dist/js/productionLibrary/mentor-core.js")).dev.kmpcoach.core.MentorApi;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const fs = require("fs");
  const ids: string[] = JSON.parse(api.providers()).map((p: { id: string }) => JSON.parse(api.brandOfProvider(p.id)).id);
  ids.push(api.brandOfPlanFile("/Users/x/.gemini/antigravity/brain/1/implementation_plan.md"), api.brandOfPlanFile("/Users/x/.claude/projects/p/s.jsonl"));
  for (const id of ids) assert.ok(fs.existsSync(path.resolve(__dirname, `../../media/brands/${id}.svg`)), `falta el ícono ${id}`);
  assert.equal(JSON.parse(api.brandOfProvider("claude-cli")).id, "claude");
  assert.equal(api.brandOfPlanFile("/Users/x/.gemini/antigravity/brain/1/implementation_plan.md"), "antigravity");
});
