// Builds dist/alto-script-editor.html: one self-contained page with Blockly, the editor code, the CSS and the
// example scripts inlined. No network access at build time or at run time.
//
//   node build.mjs            build
//   node build.mjs --check    build into memory only and print the size

import * as esbuild from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(root, '..');
const examplesDir = path.join(repo, 'docs', 'alto', 'examples');

/** The example scripts and the license text as build-time modules. */
const virtualModules = {
  name: 'alto-virtual-modules',
  setup(build) {
    build.onResolve({ filter: /^alto:(examples|about)$/ }, (args) => ({ path: args.path, namespace: 'alto' }));
    build.onLoad({ filter: /.*/, namespace: 'alto' }, (args) => {
      if (args.path === 'alto:examples') {
        const files = fs.readdirSync(examplesDir).filter((f) => f.endsWith('.alto.json')).sort();
        const examples = files.map((file) => {
          const json = fs.readFileSync(path.join(examplesDir, file), 'utf8');
          const title = JSON.parse(json).name || file;
          return { file, title, json };
        });
        return { contents: `export const EXAMPLES = ${JSON.stringify(examples)};`, loader: 'js', watchFiles: files.map((f) => path.join(examplesDir, f)) };
      }
      const license = fs.readFileSync(path.join(root, 'licenses', 'APACHE-2.0.txt'), 'utf8');
      const version = JSON.parse(fs.readFileSync(path.join(root, 'node_modules', 'blockly', 'package.json'), 'utf8')).version;
      return { contents: `export const BLOCKLY_LICENSE = ${JSON.stringify(license)}; export const BLOCKLY_VERSION = ${JSON.stringify(version)};`, loader: 'js' };
    });
  },
};

export async function buildHtml() {
  const result = await esbuild.build({
    entryPoints: [path.join(root, 'src', 'app', 'entry.js')],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: ['es2020'],
    minify: true,
    write: false,
    legalComments: 'eof',
    charset: 'utf8',
    logLevel: 'warning',
    // the CommonJS build exposes Blockly as one object, which works the same in Node and in the bundle
    alias: { 'blockly/core': path.join(root, 'node_modules', 'blockly', 'blockly_compressed.js') },
    plugins: [virtualModules],
  });
  let js = result.outputFiles[0].text;
  // keep the inline script from ending early or switching parser state
  js = js.replace(/<\/script/gi, '<\/script').replace(/<!--/g, '<\!--');
  const css = fs.readFileSync(path.join(root, 'src', 'app', 'styles.css'), 'utf8');
  const template = fs.readFileSync(path.join(root, 'src', 'app', 'index.html'), 'utf8');
  const html = template.split('/*__CSS__*/').join(css).split('/*__JS__*/').join(js);
  return html;
}

async function main() {
  const html = await buildHtml();
  if (process.argv.includes('--check')) {
    console.log(`built in memory: ${(html.length / 1024).toFixed(0)} KiB`);
    return;
  }
  const out = path.join(root, 'dist', 'alto-script-editor.html');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, html);
  console.log(`wrote ${path.relative(root, out)} (${(Buffer.byteLength(html) / 1024).toFixed(0)} KiB)`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
