// Funciones puras sobre líneas (sin `vscode`), para poder probarlas con Node.

/**
 * Dónde queda la línea [line] de un comentario tras un cambio que reemplazó las líneas [start]..[end] (terminando en la
 * columna [endChar]) y sumó [delta] líneas. Las de después se mueven; Enter al inicio de la línea la baja; si borras un
 * bloque que la contenía, queda al inicio del bloque.
 */
export function followLine(line: number, start: number, end: number, endChar: number, delta: number): number {
  if (delta === 0) return line;
  if (line > end) return line + delta;
  if (line === end && endChar === 0 && delta > 0 && start === end) return line + delta; // Enter al inicio de la línea
  if (delta < 0 && line > start) return start; // la línea estaba dentro de lo borrado
  return line;
}

/** La línea [line], una antes y dos después: lo que mira un comentario para saber si su código cambió. */
export function around(lines: string[], line: number): string[] {
  return lines.slice(Math.max(0, line - 1), Math.min(lines.length, line + 3));
}
