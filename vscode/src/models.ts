/**
 * El desplegable de modelos del panel. Aquí solo se decide qué opciones se muestran (función pura, con tests); el
 * panel las dibuja y la extensión reacciona a la elegida.
 */

/** Lista de modelos de un proveedor: se pide una vez y se guarda mientras no cambies de proveedor, ruta o clave. */
export interface ModelList {
  loading: boolean;
  models: string[];
  error?: string;
}

export interface ModelOption {
  value: string;
  label: string;
  disabled?: boolean;
}

/** Valores especiales: no son modelos, son acciones. Empiezan con «__» para que no choquen con un nombre real. */
export const MODEL_ACTION = { reload: "__reload", custom: "__custom", provider: "__provider", loading: "__loading" } as const;

export function isModelAction(value: string): boolean {
  return value.startsWith("__");
}

/**
 * Opciones del desplegable: el modelo por defecto del proveedor, los modelos que informa (el elegido siempre aparece,
 * aunque no esté en la lista), el estado de la carga y, al final, las acciones para escribir otro o cambiar de
 * proveedor o clave.
 */
export function modelOptions(current: string, defaultModel: string, list: ModelList): ModelOption[] {
  const options: ModelOption[] = [{ value: "", label: defaultModel ? `Por defecto (${defaultModel})` : "Modelo por defecto" }];
  const models = list.models.filter((m) => m && m !== defaultModel);
  if (current && current !== defaultModel && !models.includes(current)) models.unshift(current);
  for (const m of models) options.push({ value: m, label: m });
  if (list.loading) options.push({ value: MODEL_ACTION.loading, label: "Cargando modelos…", disabled: true });
  else if (list.error) options.push({ value: MODEL_ACTION.reload, label: "No pude cargar la lista · reintentar" });
  options.push({ value: MODEL_ACTION.custom, label: "Escribir otro modelo…" });
  options.push({ value: MODEL_ACTION.provider, label: "Cambiar proveedor o clave…" });
  return options;
}

/** El valor que queda elegido en el desplegable: el modelo por defecto se elige con "" (sin modelo en Ajustes). */
export function selectedModel(current: string, defaultModel: string): string {
  return current === defaultModel ? "" : current;
}
