import * as vscode from "vscode";
import { AiConfig, core, PromptOptions } from "./core";

/**
 * Ajustes del Mentor. La clave de API nunca va en los ajustes (que pueden sincronizarse o compartirse): se guarda en
 * el almacén seguro de VS Code (SecretStorage: el Llavero en Mac, el administrador de credenciales en Windows),
 * una por proveedor. Con una suscripción no hay clave: el programa del servicio usa tu propia sesión.
 */
export class Config {
  constructor(private readonly secrets: vscode.SecretStorage) {}

  private get settings() {
    return vscode.workspace.getConfiguration("mentor");
  }

  get providerId(): string {
    return this.settings.get<string>("provider", "anthropic");
  }

  get model(): string {
    return this.settings.get<string>("model", "").trim();
  }

  get readAiSession(): boolean {
    return this.settings.get<boolean>("readAiSession", true);
  }

  options(): PromptOptions {
    return {
      level: this.settings.get<string>("level", "junior"),
      language: this.settings.get<string>("language", "es"),
      maxComments: this.settings.get<number>("maxComments", 5),
    };
  }

  /** Lo que el core necesita para llamar a la IA, con la clave leída del almacén seguro. */
  async aiConfig(): Promise<AiConfig> {
    const provider = core.provider(this.providerId);
    return {
      provider: provider.id,
      model: this.model,
      baseUrl: this.settings.get<string>("baseUrl", "").trim(),
      apiKey: provider.needsKey ? (await this.secrets.get(keyName(provider.id))) ?? "" : undefined,
    };
  }

  /** «Anthropic (Claude) · claude-haiku-5-5», para el panel. */
  label(): string {
    const p = core.provider(this.providerId);
    const model = this.model || p.defaultModel || (p.subscription ? "modelo por defecto" : "sin modelo");
    return `${this.providerLabel()} · ${model}`;
  }

  /** «Anthropic (Claude)», sin las aclaraciones largas de la lista de proveedores. */
  providerLabel(): string {
    return core.provider(this.providerId).label.replace(/ \(suscripción.*\)$/, "").replace(" · experimental", "");
  }

  async setProvider(id: string) {
    await this.settings.update("provider", id, vscode.ConfigurationTarget.Global);
    await this.settings.update("model", "", vscode.ConfigurationTarget.Global);
  }

  async setModel(model: string) {
    await this.settings.update("model", model, vscode.ConfigurationTarget.Global);
  }

  async hasKey(providerId: string): Promise<boolean> {
    return !!(await this.secrets.get(keyName(providerId)));
  }

  async setKey(providerId: string, key: string) {
    await this.secrets.store(keyName(providerId), key.trim());
  }
}

const keyName = (providerId: string) => `mentor.apiKey.${providerId}`;
