// Generate Android Canvas data from the upstream MIT-licensed Canvas 2D geometry.
// Usage: node build_thinking_orbs.mjs <Libraries.dev repository>
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync, writeFileSync } from "node:fs";
import { registerHooks } from "node:module";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { gzipSync } from "node:zlib";

const revision = "14ae9d2dad18fcee251f4b06333b492a2a41d2ed";
const repo = resolve(process.argv[2] ?? "");
const geometry = "packages/thinking-orbs";
const git = (...args) => execFileSync("git", ["-C", repo, ...args], { encoding: "utf8" }).trim();
if (git("rev-parse", "HEAD") !== revision || git("status", "--porcelain", "--", geometry)) {
    throw new Error("Use an unmodified Thinking Orbs checkout at " + revision);
}
const source = resolve(repo, geometry);
const assets = fileURLToPath(new URL("../app/src/main/assets/", import.meta.url));
registerHooks({
    resolve(specifier, context, nextResolve) {
        if (context.parentURL?.startsWith(pathToFileURL(source).href) && /^\.\.?\/[\w/-]+$/.test(specifier)) {
            return nextResolve(specifier + ".ts", context);
        }
        return nextResolve(specifier, context);
    },
});
const { MODE_FRAMES } = await import(pathToFileURL(resolve(source, "src/engine/registry.ts")).href);
const { resolvePreset } = await import(pathToFileURL(resolve(source, "src/presets.ts")).href);
const size = 20;
const fps = 25;
const frameCount = 1 + 8 * fps; // One reduced-motion frame, followed by eight seconds.
const encodeUnit = value => Math.round(Math.max(0, Math.min(1, value)) * 255);
const encodePosition = value => Math.round(value * 1024);
function encodeFrame({ dots, lines }) {
    const buffer = Buffer.alloc(4 + lines.length * 13 + dots.length * 9);
    buffer.writeUInt16LE(lines.length, 0);
    buffer.writeUInt16LE(dots.length, 2);
    let offset = 4;
    function mark(values, white, alpha) {
        for (const value of values) {
            buffer.writeInt16LE(encodePosition(value), offset);
            offset += 2;
        }
        buffer.writeUInt8(encodeUnit(white), offset++);
        buffer.writeUInt8(encodeUnit(1 - white), offset++);
        buffer.writeUInt8(encodeUnit(alpha ?? 1), offset++);
    }
    for (const l of lines) mark([l.x1, l.y1, l.x2, l.y2, l.w], l.white, l.a);
    for (const d of dots) mark([d.x, d.y, d.r], d.white, d.a);
    return buffer;
}
mkdirSync(resolve(assets, "thinking-orbs"), { recursive: true });
mkdirSync(resolve(assets, "licenses"), { recursive: true });
for (const state of ["working", "solving", "connecting", "breathing"]) {
    const { mode, speed, opts } = resolvePreset(state, size);
    const header = Buffer.from([79, 82, 66, 49, size, fps, frameCount & 255, frameCount >> 8]);
    const frames = Array.from({ length: frameCount }, (_, index) => {
        const time = index === 0 ? 0.6 : (index - 1) / fps * speed;
        return encodeFrame(MODE_FRAMES[mode](size, time, opts));
    });
    writeFileSync(resolve(assets, "thinking-orbs", state + ".orb.gz"), gzipSync(Buffer.concat([header, ...frames]), { level: 9 }));
}
copyFileSync(resolve(source, "LICENSE"), resolve(assets, "licenses", "thinking-orbs-MIT.txt"));
