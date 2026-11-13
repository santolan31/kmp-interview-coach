// La barra del paso y las notas en línea como datos (funciones puras, sin `vscode`, para probarlas con Node).
// Android Studio las dibuja con su barra sobre el editor y sus bloques bajo la línea; VS Code no deja poner paneles
// dentro del editor, así que la misma información se dibuja en tres sitios: arriba del panel del Mentor, en la barra de
// estado y como CodeLens (una línea pequeña sobre el código).

/** Una acción de la barra: su texto y el comando de VS Code que la ejecuta. */
export interface BannerAction {
  label: string;
  command: string;
  args: unknown[];
  tooltip?: string;
}

/** «info» mientras trabajas, «warn» si falta algo según el Mentor, «ok» con el paso completo (como en Android Studio). */
export type BannerLevel = "info" | "warn" | "ok";

export interface StepBanner {
  level: BannerLevel;
  /** «Paso 5.3 (3 de 4)». */
  head: string;
  /** La frase completa de la barra: «Paso 5.3 (3 de 4) · El Mentor dice que falta: …». */
  text: string;
  /** Lo que falta, corto, para la barra de estado (o null si no falta nada). */
  missing: string | null;
  /** Al pasar el ratón: el paso completo, cada TODO con su estado y cómo verificarlo. */
  tooltip: string;
  actions: BannerAction[];
}

/** Texto de la barra de estado: corto, con el ícono que dice cómo vas. */
export function statusText(b: StepBanner): string {
  const icon = b.level === "ok" ? "$(pass-filled)" : b.level === "warn" ? "$(warning)" : "$(mortar-board)";
  return `${icon} ${b.head}` + (b.missing ? ` · falta: ${clip(b.missing, 40)}` : b.level === "ok" ? " · completo" : "");
}

/** Tipos de nota en línea: los de la revisión y las marcas del paso. Mismo color que en Android Studio. */
export type NoteKind = "error" | "mejora" | "pregunta" | "bien" | "stale";

export const NOTE_COLORS: Record<NoteKind, string> = {
  error: "#EC7C69", mejora: "#E3A443", pregunta: "#8DB8F2", bien: "#5DBE8A", stale: "#8B949E",
};

const NOTE_ICONS: Record<NoteKind, string> = {
  error: "$(error)", mejora: "$(lightbulb)", pregunta: "$(question)", bien: "$(pass-filled)", stale: "$(history)",
};

/**
 * El título de una nota sobre su línea: «$(error) Falta (Mentor): a onNotificationClick le debes pasar null…».
 * Un CodeLens no salta de renglón: si el texto es largo, se corta y el completo queda en el tooltip.
 */
export function noteTitle(kind: NoteKind, label: string, message: string, max = 130): string {
  return `${NOTE_ICONS[kind]} ${label}: ${clip(message, max)}`;
}

export function clip(s: string, max: number): string {
  const t = s.replace(/`/g, "").replace(/\*\*/g, "").replace(/\s+/g, " ").trim();
  return t.length > max ? t.slice(0, max - 1) + "…" : t;
}

/**
 * Qué plan mostrar en un archivo: el más reciente que lo menciona ([found]); pero si el plan que ya sigues
 * ([followed]) tiene un cambio en este archivo y no es más viejo, se queda ese. Así, al ir al archivo del paso, sigues
 * en el mismo plan y en el mismo paso aunque otra conversación también nombre el archivo.
 */
export function chooseSource<T extends { at: string }>(found: T | null, followed: T | null, followedHasFile: boolean): T | null {
  if (followed && followedHasFile && (!found || found.at <= followed.at)) return followed;
  return found;
}

/** La misma ruta aunque una tenga la «ó» de una pieza (NFC) y la otra «o» + tilde suelta (NFD). */
export function samePath(a: string, b: string): boolean {
  return a === b || a.normalize("NFC") === b.normalize("NFC");
}
