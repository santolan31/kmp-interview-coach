// Opciones del desplegable de modelos del panel.
import { test } from "node:test";
import assert from "node:assert/strict";
import { isModelAction, MODEL_ACTION, modelOptions, selectedModel } from "../src/models";

const values = (current: string, def: string, list: Parameters<typeof modelOptions>[2]) => modelOptions(current, def, list).map((o) => o.value);

test("primero el modelo por defecto, luego la lista y al final las acciones", () => {
  assert.deepEqual(values("", "haiku", { loading: false, models: ["opus", "sonnet"] }),
    ["", "opus", "sonnet", MODEL_ACTION.custom, MODEL_ACTION.provider]);
  assert.equal(modelOptions("", "haiku", { loading: false, models: [] })[0].label, "Por defecto (haiku)");
  assert.equal(modelOptions("", "", { loading: false, models: [] })[0].label, "Modelo por defecto");
});

test("el modelo por defecto no se repite aunque venga en la lista", () => {
  assert.deepEqual(values("", "haiku", { loading: false, models: ["haiku", "opus"] }),
    ["", "opus", MODEL_ACTION.custom, MODEL_ACTION.provider]);
});

test("el modelo elegido aparece aunque el proveedor no lo informe (lo escribiste a mano)", () => {
  assert.deepEqual(values("mi-modelo", "haiku", { loading: false, models: ["opus"] }),
    ["", "mi-modelo", "opus", MODEL_ACTION.custom, MODEL_ACTION.provider]);
});

test("mientras carga se dice, sin dejarlo elegir; si falló, se ofrece reintentar", () => {
  const loading = modelOptions("", "", { loading: true, models: [] }).find((o) => o.value === MODEL_ACTION.loading);
  assert.equal(loading?.disabled, true);
  assert.ok(values("", "", { loading: false, models: [], error: "sin clave" }).includes(MODEL_ACTION.reload));
});

test("el modelo por defecto queda elegido como \"\" y las acciones se distinguen de los modelos", () => {
  assert.equal(selectedModel("haiku", "haiku"), "");
  assert.equal(selectedModel("", "haiku"), "");
  assert.equal(selectedModel("opus", "haiku"), "opus");
  assert.ok(isModelAction(MODEL_ACTION.provider));
  assert.ok(!isModelAction("claude-opus-5-5"));
});
