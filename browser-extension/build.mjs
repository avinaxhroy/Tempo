import * as esbuild from 'esbuild';
import { existsSync, cpSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const isWatch = process.argv.includes('--watch');

// Background service worker — ESM (Manifest V3 supports "type": "module")
const bgOptions = {
  entryPoints: ['src/background/service-worker.ts'],
  bundle: true,
  outdir: 'dist/background',
  format: 'esm',
  target: 'chrome110',
  minify: !isWatch,
  sourcemap: isWatch ? 'inline' : false,
  drop: isWatch ? [] : ['debugger'],
};

// Content script — IIFE (content scripts DON'T support ESM)
const contentOptions = {
  entryPoints: ['src/content/media-probe.ts'],
  bundle: true,
  outdir: 'dist/content',
  format: 'iife',
  target: 'chrome110',
  minify: !isWatch,
  sourcemap: isWatch ? 'inline' : false,
  drop: isWatch ? [] : ['debugger'],
};

// Popup — ESM (loaded via <script type="module">)
const popupOptions = {
  entryPoints: ['src/popup/popup.ts'],
  bundle: true,
  outdir: 'dist/popup',
  format: 'esm',
  target: 'chrome110',
  minify: !isWatch,
  sourcemap: isWatch ? 'inline' : false,
  drop: isWatch ? [] : ['debugger'],
};

// Copy static assets to dist
function copyStaticAssets() {
  const distDir = resolve(__dirname, 'dist');
  if (!existsSync(distDir)) mkdirSync(distDir, { recursive: true });

  // manifest.json for dist strips the root-level dist/ prefixes so both
  // browser-extension/ and browser-extension/dist are loadable after build.
  const sourceManifestPath = resolve(__dirname, 'manifest.json');
  const manifest = JSON.parse(readFileSync(sourceManifestPath, 'utf8'));
  const stripDistPrefix = (value) => {
    if (typeof value === 'string' && value.startsWith('dist/')) {
      return value.slice(5);
    }
    if (Array.isArray(value)) {
      return value.map(stripDistPrefix);
    }
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, stripDistPrefix(item)]));
    }
    return value;
  };
  writeFileSync(resolve(distDir, 'manifest.json'), JSON.stringify(stripDistPrefix(manifest), null, 2));

  // icons
  const iconsDir = resolve(__dirname, 'icons');
  if (existsSync(iconsDir)) {
    cpSync(iconsDir, resolve(distDir, 'icons'), { recursive: true });
  }

  // popup HTML and CSS
  const popupDir = resolve(distDir, 'popup');
  if (!existsSync(popupDir)) mkdirSync(popupDir, { recursive: true });
  
  const popupHtml = resolve(__dirname, 'src/popup/popup.html');
  if (existsSync(popupHtml)) cpSync(popupHtml, resolve(popupDir, 'popup.html'));
  
  const popupCss = resolve(__dirname, 'src/popup/popup.css');
  if (existsSync(popupCss)) cpSync(popupCss, resolve(popupDir, 'popup.css'));
}

async function build() {
  try {
    if (isWatch) {
      const [bgCtx, contentCtx, popupCtx] = await Promise.all([
        esbuild.context(bgOptions),
        esbuild.context(contentOptions),
        esbuild.context(popupOptions),
      ]);
      await Promise.all([bgCtx.watch(), contentCtx.watch(), popupCtx.watch()]);
      copyStaticAssets();
      console.log('👀 Watching for changes...');
    } else {
      await Promise.all([
        esbuild.build(bgOptions),
        esbuild.build(contentOptions),
        esbuild.build(popupOptions),
      ]);
      copyStaticAssets();
      console.log('✅ Build complete → dist/');
    }
  } catch (err) {
    console.error('Build failed:', err);
    process.exit(1);
  }
}

build();
