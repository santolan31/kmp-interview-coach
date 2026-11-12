// Mensajes de error para VS Code (funciones puras, sin `vscode`, para probarlas con Node).

/**
 * Lo que se le ofrece a la persona según el error: poner la clave, iniciar sesión en la terminal, elegir otro
 * proveedor o volver a probar (con GitHub Copilot, para que VS Code muestre otra vez su ventana de permiso).
 */
export type FixAction = "key" | "login" | "provider" | "retry";

/** Los mensajes del core nombran los Ajustes de Android Studio; en VS Code se dice qué comando usar. */
export function forVsCode(error: string): string {
  return error
    .replace("Agrégala en Ajustes › Herramientas › Mentor.", "Pónla con «Mentor: Poner o cambiar la clave de API».")
    .replace(/Elige un modelo en Ajustes › Herramientas › Mentor[^.]*\./, "Elige un modelo con «Mentor: Elegir modelo».")
    .replace("Configúrala en Ajustes › Herramientas › Mentor.", "Configúrala en Ajustes › Mentor › Base Url.")
    .replace("escribe su ruta en Ajustes › Mentor", "escribe su ruta en Ajustes › Mentor › Base Url");
}

/** Qué arregla el error, si es de configuración; undefined si es otro (red, límite de uso…). */
export function actionFor(error: string): FixAction | undefined {
  if (/permiso para usar Copilot/i.test(error)) return "retry";
  if (/No encontré GitHub Copilot|no tiene el modelo/i.test(error)) return "provider";
  if (/clave/i.test(error)) return "key";
  if (/sesión iniciada|no tiene una sesión/i.test(error)) return "login";
  if (/No encontré `/.test(error)) return "provider";
  return undefined;
}
