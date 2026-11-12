// Los errores en VS Code dicen qué comando usar (no la ruta de Ajustes de Android Studio) y qué botón ofrecer.
import { test } from "node:test";
import assert from "node:assert/strict";
import { actionFor, forVsCode } from "../src/messages";

test("la clave que falta se pone con un comando de VS Code, no en los Ajustes de Android Studio", () => {
  const m = forVsCode("Falta la clave de API de Anthropic (Claude). Agrégala en Ajustes › Herramientas › Mentor.");
  assert.ok(m.includes("«Mentor: Poner o cambiar la clave de API»"));
  assert.ok(!m.includes("Herramientas"), "en VS Code no existe Ajustes › Herramientas");
  assert.equal(actionFor(m), "key");
});

test("cada error de configuración ofrece el botón que lo arregla", () => {
  assert.equal(actionFor("La clave de Anthropic (Claude) no es válida o no tiene permiso para este modelo (HTTP 401)."), "key");
  assert.equal(actionFor("claude no tiene una sesión iniciada. Abre una terminal, ejecuta `claude` una vez e inicia sesión con tu cuenta."), "login");
  assert.equal(actionFor("No encontré `claude`. Instálalo o escribe su ruta en Ajustes › Mentor."), "provider");
  assert.equal(actionFor("El proveedor tardó demasiado en responder."), undefined, "un problema de red no es de configuración");
  assert.ok(forVsCode("No encontré `claude`. Instálalo o escribe su ruta en Ajustes › Mentor. Abre una terminal").includes("Ajustes › Mentor › Base Url"));
});
