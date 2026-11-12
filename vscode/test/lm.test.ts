// GitHub Copilot por la API de modelos de VS Code: cómo se arman los mensajes y cómo se explican los errores.
import { test } from "node:test";
import assert from "node:assert/strict";
import { copilotError, modelIds, toLmMessages } from "../src/lm";
import { actionFor } from "../src/messages";

test("las instrucciones del Mentor van al inicio del primer mensaje (la API no tiene mensajes de sistema)", () => {
  const out = toLmMessages("Eres un mentor.", [
    { role: "user", content: "¿Por qué falla?" },
    { role: "assistant", content: "Mira la línea 3." },
    { role: "user", content: "Ya veo" },
  ]);
  assert.equal(out.length, 3, "no se agregan mensajes: se conserva la alternancia");
  assert.ok(out[0].text.startsWith("Instrucciones") && out[0].text.includes("Eres un mentor.") && out[0].text.endsWith("¿Por qué falla?"));
  assert.deepEqual(out.slice(1), [{ role: "assistant", text: "Mira la línea 3." }, { role: "user", text: "Ya veo" }]);
});

test("si la conversación no empieza por el estudiante, las instrucciones van en un mensaje propio", () => {
  const out = toLmMessages("Eres un mentor.", [{ role: "assistant", content: "Hola" }]);
  assert.equal(out[0].role, "user");
  assert.ok(out[0].text.includes("Eres un mentor."));
  assert.equal(out[1].text, "Hola");
});

test("cada error de Copilot dice qué hacer y ofrece el botón que lo arregla", () => {
  const noPermission = copilotError("NoPermissions", "");
  assert.equal(actionFor(noPermission), "retry", "sin permiso: probar de nuevo para que VS Code vuelva a preguntar");
  assert.equal(actionFor(copilotError("NotFound", "")), "provider", "sin Copilot: elegir otro proveedor");
  assert.ok(copilotError("Blocked", "").includes("bloqueó"));
  assert.ok(copilotError("Unknown", "Rate limit exceeded").includes("límite de uso"));
  assert.equal(actionFor(copilotError("Unknown", "Rate limit exceeded")), undefined, "un límite no se arregla con un botón");
  assert.ok(copilotError(undefined, "boom").includes("boom"));
});

test("los modelos se listan una vez cada uno y ordenados", () => {
  assert.deepEqual(modelIds([{ id: "gpt-5" }, { id: "claude-sonnet" }, { id: "gpt-5" }, { id: "" }]), ["claude-sonnet", "gpt-5"]);
});
