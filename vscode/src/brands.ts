import * as fs from "fs";
import * as path from "path";
import * as vscode from "vscode";
import { core } from "./core";

/**
 * Íconos de cada IA (los mismos SVG del plugin de Android Studio, copiados a media/brands al compilar): en las listas
 * para elegir proveedor y conversación, y dentro del panel.
 */
let root = "";
const svgCache = new Map<string, string>();

export function initBrands(extensionPath: string) {
  root = path.join(extensionPath, "media", "brands");
}

export const brandOfProvider = (providerId: string): string => JSON.parse(core.raw.brandOfProvider(providerId)).id;
export const brandOfPlanFile = (file: string): string => core.raw.brandOfPlanFile(file);

/** Para las listas de VS Code (QuickPick): el archivo del ícono. */
export function brandIcon(id: string): vscode.Uri {
  return vscode.Uri.file(path.join(root, `${id}.svg`));
}

/** Para el panel (HTML): el SVG en línea, del tamaño del texto. */
export function brandSvg(id: string): string {
  let svg = svgCache.get(id);
  if (svg === undefined) {
    try {
      svg = fs.readFileSync(path.join(root, `${id}.svg`), "utf8").replace("<svg ", '<svg class="brand" ');
    } catch {
      svg = "";
    }
    svgCache.set(id, svg);
  }
  return svg;
}
