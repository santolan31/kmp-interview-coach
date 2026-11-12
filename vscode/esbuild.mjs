// Empaqueta la extensión y el core de Kotlin (compilado a JavaScript) en un solo archivo: dist/extension.js.
// `vscode` no se empaqueta: lo pone el editor al cargar la extensión.
import * as esbuild from "esbuild";
import * as fs from "fs";

// Los íconos de cada IA viven en el plugin de Android Studio (una sola fuente): se copian a media/brands.
const brands = "../src/main/resources/icons/brands";
fs.mkdirSync("media/brands", { recursive: true });
for (const f of fs.readdirSync(brands).filter((n) => n.endsWith(".svg"))) fs.copyFileSync(`${brands}/${f}`, `media/brands/${f}`);

const options = {
  entryPoints: ["src/extension.ts"],
  bundle: true,
  platform: "node",
  target: "node18",
  format: "cjs",
  outfile: "dist/extension.js",
  external: ["vscode"],
  sourcemap: true,
  logLevel: "info",
};

if (process.argv.includes("--watch")) {
  const ctx = await esbuild.context(options);
  await ctx.watch();
} else {
  await esbuild.build(options);
}
