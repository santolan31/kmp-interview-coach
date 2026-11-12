// Encender y apagar el Mentor: el botón cambia al instante, aunque buscar la conversación tarde. Se usa un `vscode`
// simulado y una búsqueda falsa que solo termina cuando el test lo decide.
import { test } from "node:test";
import assert from "node:assert/strict";
// eslint-disable-next-line @typescript-eslint/no-require-imports
const Module = require("module");

let fires = 0;
const vscodeStub = {
  EventEmitter: class { event = () => ({ dispose() {} }); fire() { fires++; } dispose() {} },
};
const load = Module._load;
Module._load = function (request: string, ...rest: unknown[]) {
  return request === "vscode" ? vscodeStub : load.call(this, request, ...rest);
};
// eslint-disable-next-line @typescript-eslint/no-require-imports
const { Mentor } = require("../src/mentor") as typeof import("../src/mentor");

const memento = { get: () => undefined, update: async () => {} };
const doc = { uri: { toString: () => "file:///p/A.kt", fsPath: "/p/A.kt", scheme: "file" } } as never;

/** Un Mentor cuya búsqueda de conversación queda esperando hasta llamar a `finish`. */
function slowMentor() {
  const mentor = new Mentor({ readAiSession: true } as never, memento as never, memento as never, null, null);
  let finish = (_found: unknown) => {};
  (mentor as unknown as { sessions: unknown }).sessions = {
    find: () => new Promise((resolve) => { finish = resolve; }),
    latestOld: async () => null,
  };
  return { mentor, finish: (found: unknown) => finish(found) };
}

test("al encenderlo, el panel se entera antes de que termine la búsqueda", async () => {
  const { mentor, finish } = slowMentor();
  fires = 0;
  const searching = mentor.toggle(doc);
  assert.equal(mentor.isOn(doc), true);
  assert.ok(fires >= 1, "el panel debe redibujarse ya, no después de buscar");
  assert.equal(mentor.isLoading(doc), true);
  assert.equal(mentor.foundFor(doc), undefined);
  finish({ source: "Claude Code" });
  await searching;
  assert.equal(mentor.isLoading(doc), false);
  assert.deepEqual(mentor.foundFor(doc), { source: "Claude Code" });
});

test("si lo apagas mientras busca, queda apagado y no guarda el resultado tardío", async () => {
  const { mentor, finish } = slowMentor();
  const searching = mentor.toggle(doc);
  await mentor.toggle(doc);
  assert.equal(mentor.isOn(doc), false);
  finish({ source: "Claude Code" });
  await searching;
  assert.equal(mentor.isOn(doc), false);
  assert.equal(mentor.isLoading(doc), false);
  assert.equal(mentor.foundFor(doc), undefined);
});
