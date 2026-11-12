// Puente tipado hacia la lógica del Mentor, escrita en Kotlin Multiplatform (módulo :core) y compilada a JavaScript.
// La misma lógica que usa el plugin de Android Studio. Todo cruza como texto JSON.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const lib = require("../../core/build/dist/js/productionLibrary/mentor-core.js");
const api = lib.dev.kmpcoach.core.MentorApi as import("../../core/build/dist/js/productionLibrary/mentor-core").dev.kmpcoach.core.MentorApi;

export interface Provider {
  id: string;
  label: string;
  subscription: boolean;
  needsKey: boolean;
  defaultModel: string;
  baseUrl: string;
  billingUrl: string | null;
  executable: string | null;
}

/** Lo que el core necesita para llamar a la IA. */
export interface AiConfig {
  provider: string;
  model: string;
  baseUrl: string;
  apiKey?: string;
}

export interface PromptOptions {
  level: string;
  language: string;
  maxComments: number;
}

export interface Rules {
  source: string;
  text: string;
}

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

export type Reply =
  | { ok: true; text: string; inputTokens: number | null; outputTokens: number | null; costUsd: number | null }
  | { ok: false; error: string };

export interface Turn {
  role: string;
  text: string;
  at: string;
}

const json = (v: unknown) => JSON.stringify(v);
const rules = (r: Rules | null | undefined) => (r ? json(r) : null);

export const core = {
  providers: (): Provider[] => JSON.parse(api.providers()),
  provider: (id: string): Provider => core.providers().find((p) => p.id === id) ?? core.providers()[0],
  validate: (c: AiConfig): string | null => api.validate(json(c)) ?? null,
  findCli: (providerId: string, path: string): string | null => api.findCli(providerId, path || null) ?? null,

  complete: async (c: AiConfig, system: string, messages: ChatMessage[], maxTokens: number): Promise<Reply> =>
    JSON.parse(await api.complete(json(c), system, json(messages), maxTokens)),
  listModels: async (c: AiConfig): Promise<{ ok: true; models: string[] } | { ok: false; error: string }> =>
    JSON.parse(await api.listModels(json(c))),

  chatSystem: (o: PromptOptions, path: string | null, code: string, session: string | null, r: Rules | null) =>
    api.chatSystem(json(o), path, code, session, rules(r)),

  projectDirName: (basePath: string) => api.projectDirName(basePath),
  parseSession: (lines: string[]): string => api.parseSession(lines),
  sessionExcerpt: (turns: string, fileName: string | null): string | null => api.sessionExcerpt(turns, fileName) ?? null,
  lastMention: (turns: string, fileName: string): string | null => api.lastMention(turns, fileName) ?? null,
  lastRequest: (turns: string): string | null => api.lastRequest(turns) ?? null,
  hasSteps: (markdown: string) => api.hasSteps(markdown),
  extractRules: (markdown: string): string | null => api.extractRules(markdown) ?? null,

  formatTokens: (n: number) => api.formatTokens(n),
  formatUsd: (v: number) => api.formatUsd(v),
  languageOf: (path: string | null) => api.languageOf(path),

  /** Para la fase de revisión y pistas: acceso directo a la fachada completa. */
  raw: api,
};
