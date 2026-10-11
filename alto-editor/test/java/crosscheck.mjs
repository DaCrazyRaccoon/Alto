// Optional cross-check against the real Java implementation: compiles Alto's pure script package
// (src/main/java/baritone/alto/script + ItemKey) with a local JDK 21+ and Gson, runs it on the same scripts as the
// editor and compares diagnostics (severity, code, path, message), canonical JSON, pretty JSON, hash and capability
// sentences. Everything is written to alto-editor/test-output/ (git-ignored). Skips when no JDK 21 or Gson is found.
//
// Configure with ALTO_JDK (a JDK home) and ALTO_GSON_JAR (path to a gson-*.jar) if auto-detection fails.

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const editorRoot = path.resolve(here, '..', '..');
export const repoRoot = path.resolve(editorRoot, '..');
const outDir = path.join(editorRoot, 'test-output', 'java-check');

function javaMajor(javaExe) {
  const r = spawnSync(javaExe, ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 30000 });
  return parseVersion(String(r.stderr || '') + String(r.stdout || ''));
}
function parseVersion(text) {
  const m = /version "(\d+)/.exec(text || '');
  return m ? Number(m[1]) : 0;
}

function exe(home, name) {
  const file = path.join(home, 'bin', process.platform === 'win32' ? name + '.exe' : name);
  return fs.existsSync(file) ? file : null;
}

/** A JDK home with java and javac of version 21 or later, or null. */
export function findJdk() {
  const candidates = [];
  if (process.env.ALTO_JDK) candidates.push(process.env.ALTO_JDK);
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);
  const jdks = path.join(os.homedir(), '.jdks');
  if (fs.existsSync(jdks)) {
    for (const d of fs.readdirSync(jdks).sort().reverse()) candidates.push(path.join(jdks, d));
  }
  const gradleJdks = path.join(os.homedir(), '.gradle', 'jdks');
  if (fs.existsSync(gradleJdks)) {
    for (const d of fs.readdirSync(gradleJdks).sort().reverse()) candidates.push(path.join(gradleJdks, d));
  }
  for (const home of candidates) {
    const java = exe(home, 'java');
    const javac = exe(home, 'javac');
    if (java && javac && javaMajor(java) >= 21) return { java, javac };
  }
  return null;
}

/** A Gson jar from the environment or the Gradle cache, or null. */
export function findGson() {
  if (process.env.ALTO_GSON_JAR && fs.existsSync(process.env.ALTO_GSON_JAR)) return process.env.ALTO_GSON_JAR;
  const base = path.join(os.homedir(), '.gradle', 'caches', 'modules-2', 'files-2.1', 'com.google.code.gson', 'gson');
  if (!fs.existsSync(base)) return null;
  const versions = fs.readdirSync(base).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).reverse();
  for (const v of versions) {
    for (const hash of fs.readdirSync(path.join(base, v))) {
      const jar = path.join(base, v, hash, `gson-${v}.jar`);
      if (fs.existsSync(jar)) return jar;
    }
  }
  return null;
}

function javaSources() {
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(p);
      else if (entry.name.endsWith('.java')) out.push(p);
    }
  };
  walk(path.join(repoRoot, 'src', 'main', 'java', 'baritone', 'alto', 'script'));
  out.push(path.join(repoRoot, 'src', 'main', 'java', 'baritone', 'alto', 'core', 'ItemKey.java'));
  out.push(path.join(here, 'CrossCheck.java'));
  return out;
}

/** Runs the Java loader on [{name, json}] and returns its reports, or null when no JDK 21 / Gson is available. */
export function runJava(cases) {
  const jdk = findJdk();
  const gson = findGson();
  if (!jdk || !gson) return null;
  const classes = path.join(outDir, 'classes');
  fs.mkdirSync(classes, { recursive: true });
  const argFile = path.join(outDir, 'sources.txt');
  fs.writeFileSync(argFile, javaSources().map((s) => `"${s.replace(/\\/g, '/')}"`).join('\n'));
  execFileSync(jdk.javac, ['-encoding', 'UTF-8', '-nowarn', '-d', classes, '-cp', gson, '@' + argFile], { stdio: 'pipe' });
  const input = path.join(outDir, 'cases.json');
  const output = path.join(outDir, 'reports.json');
  fs.writeFileSync(input, JSON.stringify(cases));
  execFileSync(jdk.java, ['-cp', [classes, gson].join(path.delimiter), 'CrossCheck', input, output], { stdio: 'pipe', maxBuffer: 1 << 28 });
  return JSON.parse(fs.readFileSync(output, 'utf8'));
}

/** Differences between a JS report (loadScript) and a Java report, as readable strings. */
export function compareReports(js, java) {
  const problems = [];
  const fmt = (d) => `${d.severity} ${d.code} at '${d.path}': ${d.message}`;
  const jsD = js.diagnostics.map((d) => (d.code === 'json_syntax' ? { ...d, message: '(syntax)' } : d)).map(fmt);
  const javaD = java.diagnostics.map((d) => (d.code === 'json_syntax' ? { ...d, message: '(syntax)' } : d)).map(fmt);
  if (JSON.stringify(jsD) !== JSON.stringify(javaD)) {
    problems.push(`diagnostics differ\n  js:   ${jsD.join('\n        ')}\n  java: ${javaD.join('\n        ')}`);
  }
  if (Boolean(js.runnable) !== Boolean(java.runnable)) problems.push(`runnable: js ${js.runnable}, java ${java.runnable}`);
  for (const key of ['canonical', 'pretty', 'hash']) {
    if ((js[key] ?? null) !== (java[key] ?? null)) problems.push(`${key} differs\n  js:   ${js[key]}\n  java: ${java[key]}`);
  }
  const jsS = js.capabilities ? js.capabilities.sentences : null;
  const javaS = java.sentences ?? null;
  if (JSON.stringify(jsS) !== JSON.stringify(javaS)) problems.push(`capability sentences differ\n  js:   ${JSON.stringify(jsS)}\n  java: ${JSON.stringify(javaS)}`);
  return problems;
}

async function main() {
  const { CASES, EVERYTHING } = await import(pathToFileURL(path.join(editorRoot, 'test', 'fixtures', 'cases.mjs')).href);
  const { loadScript } = await import(pathToFileURL(path.join(editorRoot, 'src', 'lang', 'index.js')).href);
  const cases = CASES.map((c) => ({ name: c.name, json: c.json }));
  cases.push({ name: 'EVERYTHING', json: EVERYTHING });
  const examples = path.join(repoRoot, 'docs', 'alto', 'examples');
  for (const f of fs.readdirSync(examples)) cases.push({ name: 'example ' + f, json: fs.readFileSync(path.join(examples, f), 'utf8') });
  const generated = path.join(editorRoot, 'test-output');
  if (fs.existsSync(generated)) {
    for (const f of fs.readdirSync(generated).filter((x) => x.endsWith('.alto.json'))) {
      cases.push({ name: 'generated ' + f, json: fs.readFileSync(path.join(generated, f), 'utf8') });
    }
  }
  const reports = runJava(cases);
  if (!reports) {
    console.log('skipped: no JDK 21+ or Gson jar found (set ALTO_JDK and ALTO_GSON_JAR)');
    return;
  }
  let failed = 0;
  reports.forEach((java, i) => {
    const problems = compareReports(loadScript(cases[i].json), java);
    if (problems.length) {
      failed++;
      console.log(`DIFF ${cases[i].name}\n  ${problems.join('\n  ')}`);
    }
  });
  console.log(`${cases.length - failed}/${cases.length} scripts identical in JavaScript and Java`);
  process.exitCode = failed ? 1 : 0;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main();
}
