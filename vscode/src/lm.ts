// GitHub Copilot por la API de modelos de VS Code: la parte pura (sin `vscode`, para probarla con Node).
import type { ChatMessage } from "./core";

/** Identificador del proveedor en el core (ProviderPreset.COPILOT_EDITOR). */
export const COPILOT_PROVIDER = "copilot-vscode";

/** Lo que aparece en la ventana de permiso de VS Code la primera vez: para qué quiere el Mentor usar Copilot. */
export const COPILOT_JUSTIFICATION = "El Mentor usa tu suscripción de GitHub Copilot para guiarte con pistas y preguntas, sin escribirte la solución.";

export interface LmMessage {
  role: "user" | "assistant";
  text: string;
}

/**
 * La API de modelos de VS Code no admite mensajes de sistema: las instrucciones del Mentor van al principio del
 * primer mensaje del estudiante, separadas para que el modelo las distinga de la pregunta.
 */
export function toLmMessages(system: string, messages: ChatMessage[]): LmMessage[] {
  const out: LmMessage[] = messages.map((m) => ({ role: m.role, text: m.content }));
  const instructions = `Instrucciones que debes seguir en toda la conversación:\n\n${system.trim()}\n\n---\n\n`;
  if (out[0]?.role === "user") out[0] = { role: "user", text: instructions + out[0].text };
  else out.unshift({ role: "user", text: instructions.trimEnd() });
  return out;
}

/**
 * El error de la API (su `code`: NoPermissions, Blocked, NotFound o Unknown) en palabras para la persona. Los textos
 * llevan las frases que `actionFor` reconoce para ofrecer el botón que lo arregla.
 */
export function copilotError(code: string | undefined, message: string): string {
  switch (code) {
    case "NoPermissions":
      return "El Mentor todavía no tiene permiso para usar Copilot. Pulsa «Permitir y probar de nuevo» y acepta la ventana de VS Code.";
    case "Blocked":
      return "GitHub Copilot bloqueó al Mentor por ahora (demasiados pedidos seguidos o una política de tu organización). Espera un momento y vuelve a intentarlo.";
    case "NotFound":
      return noCopilot();
  }
  if (/quota|rate limit|limit exceeded|429/i.test(message)) return `Llegaste al límite de uso de tu plan de GitHub Copilot. Espera o revisa tu plan en github.com/settings/copilot. (${message})`;
  return `GitHub Copilot no pudo responder: ${message || "error desconocido"}.`;
}

/** Sin modelos de Copilot: no está instalado, no hay sesión, o es un editor sin Copilot (Cursor, Antigravity). */
export function noCopilot(): string {
  return "No encontré GitHub Copilot en este editor. Instala la extensión GitHub Copilot Chat e inicia sesión con tu cuenta de GitHub (funciona en VS Code; en Cursor o Antigravity no hay Copilot).";
}

/** Nombres de modelo únicos y ordenados para el desplegable (Copilot puede listar el mismo id con versiones distintas). */
export function modelIds(models: { id: string }[]): string[] {
  return [...new Set(models.map((m) => m.id).filter(Boolean))].sort();
}
