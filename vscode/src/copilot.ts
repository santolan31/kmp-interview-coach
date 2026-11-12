import * as vscode from "vscode";
import type { ChatMessage, Reply } from "./core";
import { COPILOT_JUSTIFICATION, copilotError, modelIds, noCopilot, toLmMessages } from "./lm";

/**
 * GitHub Copilot con la suscripción de la persona, por la API oficial de modelos de VS Code (`vscode.lm`). No hay
 * clave ni programa externo: VS Code usa la sesión de Copilot que ya tienes iniciada y, la primera vez, te pide permiso.
 * Cada pedido gasta tu cuota de Copilot, como cuando usas su chat.
 */
export async function completeWithCopilot(system: string, messages: ChatMessage[], model: string): Promise<Reply> {
  try {
    const chat = await pick(model);
    if (!chat) return { ok: false, error: model ? `Tu GitHub Copilot no tiene el modelo «${model}». Elige otro en el desplegable del panel.` : noCopilot() };
    const lm = toLmMessages(system, messages).map((m) =>
      m.role === "user" ? vscode.LanguageModelChatMessage.User(m.text) : vscode.LanguageModelChatMessage.Assistant(m.text));
    const response = await chat.sendRequest(lm, { justification: COPILOT_JUSTIFICATION });
    let text = "";
    for await (const part of response.text) text += part;
    if (!text.trim()) return { ok: false, error: "GitHub Copilot devolvió una respuesta vacía. Vuelve a intentarlo." };
    // VS Code no informa el consumo: se cuentan los tokens con el contador del propio modelo (aproximado).
    const [input, output] = await Promise.all([
      Promise.all(lm.map((m) => chat.countTokens(m))).then((n) => n.reduce((a, b) => a + b, 0)),
      chat.countTokens(text),
    ]).catch(() => [null, null] as const);
    return { ok: true, text, inputTokens: input, outputTokens: output, costUsd: null };
  } catch (e) {
    const code = e instanceof vscode.LanguageModelError ? e.code : undefined;
    return { ok: false, error: copilotError(code, e instanceof Error ? e.message : String(e)) };
  }
}

/** Los modelos de Copilot de tu plan, para el desplegable del panel. */
export async function copilotModels(): Promise<{ ok: true; models: string[] } | { ok: false; error: string }> {
  try {
    const models = await vscode.lm.selectChatModels({ vendor: "copilot" });
    return models.length ? { ok: true, models: modelIds(models) } : { ok: false, error: noCopilot() };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** El modelo elegido o, sin elegir, el primero que ofrezca Copilot. */
async function pick(model: string): Promise<vscode.LanguageModelChat | undefined> {
  if (model) return (await vscode.lm.selectChatModels({ vendor: "copilot", id: model }))[0];
  return (await vscode.lm.selectChatModels({ vendor: "copilot" }))[0];
}
