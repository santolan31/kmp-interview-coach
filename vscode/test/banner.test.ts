// La barra del paso y las notas en línea: cómo se resumen para la barra de estado y para un CodeLens.
import { test } from "node:test";
import assert from "node:assert/strict";
import { chooseSource, noteTitle, samePath, statusText, StepBanner } from "../src/banner";

const banner = (over: Partial<StepBanner>): StepBanner =>
  ({ level: "info", head: "Paso 5.3 (3 de 4)", text: "", missing: null, tooltip: "", actions: [], ...over });

test("la barra de estado dice el paso, cómo vas y qué falta, corto", () => {
  assert.equal(statusText(banner({})), "$(mortar-board) Paso 5.3 (3 de 4)");
  assert.equal(statusText(banner({ level: "ok" })), "$(pass-filled) Paso 5.3 (3 de 4) · completo");
  const warn = statusText(banner({ level: "warn", missing: "A onNotificationClick le debes pasar null directamente; { null } es una lambda" }));
  assert.ok(warn.startsWith("$(warning) Paso 5.3 (3 de 4) · falta: A onNotificationClick"));
  assert.ok(warn.endsWith("…") && warn.length < 80, "se corta para que quepa en la barra de estado");
});

test("una nota en línea lleva su ícono y etiqueta, y se corta si es larga (el texto completo va en el tooltip)", () => {
  assert.equal(noteTitle("error", "Falta (Mentor)", "Cambia el `TODO 6` por tu comentario"), "$(error) Falta (Mentor): Cambia el TODO 6 por tu comentario");
  const long = noteTitle("mejora", "Mejora (Mentor)", "x".repeat(300), 50);
  assert.ok(long.startsWith("$(lightbulb) Mejora (Mentor): ") && long.endsWith("…"));
});

test("al ir al archivo del paso se sigue en el mismo plan, salvo que haya uno más nuevo", () => {
  const viejo = { id: "viejo", at: "2026-11-10T10:00:00Z" };
  const actual = { id: "actual", at: "2026-11-11T10:00:00Z" };
  const nuevo = { id: "nuevo", at: "2026-11-12T10:00:00Z" };
  assert.equal(chooseSource(viejo, actual, true), actual, "otra conversación más vieja nombra el archivo: sigues en tu plan");
  assert.equal(chooseSource(null, actual, true), actual, "la búsqueda aún no termina: sigues en tu plan");
  assert.equal(chooseSource(nuevo, actual, true), nuevo, "tu IA te dio un plan nuevo: manda el nuevo");
  assert.equal(chooseSource(viejo, actual, false), viejo, "el archivo no es de tu plan: el que lo menciona");
  assert.equal(chooseSource(null, null, false), null);
});

test("la misma ruta con la tilde escrita de las dos formas es el mismo archivo", () => {
  assert.ok(samePath("/Users/s/Facturación/A.kt", "/Users/s/Facturación/A.kt"));
  assert.ok(!samePath("/Users/s/A.kt", "/Users/s/B.kt"));
});
