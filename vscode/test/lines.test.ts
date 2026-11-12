// Los comentarios del Mentor siguen a su línea mientras escribes.
import { test } from "node:test";
import assert from "node:assert/strict";
import { around, followLine } from "../src/lines";

test("las líneas de después se mueven con lo que insertas o borras", () => {
  assert.equal(followLine(10, 2, 2, 5, +3), 13);   // 3 líneas nuevas arriba
  assert.equal(followLine(10, 2, 4, 0, -2), 8);    // 2 líneas borradas arriba
  assert.equal(followLine(10, 12, 12, 3, +1), 10); // un cambio más abajo no la toca
  assert.equal(followLine(10, 10, 10, 4, 0), 10);  // escribir en la misma línea no la mueve
});

test("Enter al inicio de la línea comentada la baja; en medio, no", () => {
  assert.equal(followLine(10, 10, 10, 0, +1), 11);
  assert.equal(followLine(10, 10, 10, 6, +1), 10);
});

test("si borras el bloque que la contenía, queda al inicio del bloque", () => {
  assert.equal(followLine(10, 8, 12, 0, -4), 8);
});

test("la ventana alrededor de un comentario es su línea, una antes y dos después", () => {
  assert.deepEqual(around(["a", "b", "c", "d", "e"], 2), ["b", "c", "d", "e"]);
  assert.deepEqual(around(["a", "b"], 0), ["a", "b"]);
});
