import * as esbuild from 'esbuild';
import { existsSync, cpSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const isWatch = process.argv.includes('--watch');

// Parse --target flag (chrome or firefox)
const targetArg = process.argv.find(arg => arg.startsWith('--target='));
const target = targetArg ? targetArg.split('=')[1] : 'chrome';

if (target !== 'chrome' && target !== 'firefox') {
  console.error('❌ Invalid target. Use: --target=chrome or --target=firefox');
  process.exit(1);
}

// Background service worker — IIFE for both browsers (Firefox doesn't fully support ESM in service workers)
const bgOptions = {
  entryPoints: ['src/background/service-worker.ts'],
  bundle: true,
  outdir: 'dist/background',
  format: 'iife',
  target: 'chrome110',
  minify: !isWatch,
  sourcemap: isWatch ? 'inline' : false,
  drop: isWatch ? [] : ['debugger'],
};

// Content script — IIFE (content scripts DON'T support ESM)
const contentOptions = {
  entryPoints: ['src/content/media-probe.ts', 'src/content/yt-main-world-helper.ts'],
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

function stripDistPrefix(value) {
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
}

function buildChromeManifest(baseManifest) {
  const m = stripDistPrefix(baseManifest);
  // Remove "type": "module" since we bundle to IIFE
  if (m.background) delete m.background.type;
  return m;
}

function buildFirefoxManifest(baseManifest) {
  const m = stripDistPrefix(baseManifest);

  // Remove Chrome-only fields
  delete m.minimum_chrome_version;

  // Remove "type": "module" from background — Firefox IIFE doesn't need it
  if (m.background) delete m.background.type;

  // Firefox requires background.scripts as fallback for service_worker
  if (m.background?.service_worker) {
    m.background.scripts = [m.background.service_worker];
  }

  // Firefox doesn't support wildcard ports in optional_host_permissions
  // Remove it entirely - the extension requests specific IP permissions at runtime
  delete m.optional_host_permissions;

  // Add Firefox-specific settings
  // Extension doesn't collect/transmit data - it only tracks local playback history
  m.browser_specific_settings = {
    gecko: {
      id: 'tempo-stats@extension',
      strict_min_version: '140.0',
      data_collection_permissions: {
        required: ['none']
      }
    },
    gecko_android: {
      strict_min_version: '142.0'
    }
  };

  return m;
}

// Copy static assets to the target dist directory
function copyStaticAssets(distDir) {
  if (!existsSync(distDir)) mkdirSync(distDir, { recursive: true });

  const sourceManifestPath = resolve(__dirname, 'manifest.json');
  const baseManifest = JSON.parse(readFileSync(sourceManifestPath, 'utf8'));

  const manifest = target === 'firefox'
    ? buildFirefoxManifest(baseManifest)
    : buildChromeManifest(baseManifest);

  writeFileSync(resolve(distDir, 'manifest.json'), JSON.stringify(manifest, null, 2));

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
  const distDir = resolve(__dirname, 'dist', target);
  const browserLabel = target === 'firefox' ? 'Firefox' : 'Chrome';

  try {
    if (isWatch) {
      const [bgCtx, contentCtx, popupCtx] = await Promise.all([
        esbuild.context({ ...bgOptions, outdir: resolve(distDir, 'background') }),
        esbuild.context({ ...contentOptions, outdir: resolve(distDir, 'content') }),
        esbuild.context({ ...popupOptions, outdir: resolve(distDir, 'popup') }),
      ]);
      await Promise.all([bgCtx.watch(), contentCtx.watch(), popupCtx.watch()]);
      copyStaticAssets(distDir);
      console.log(`👀 Watching for changes (${browserLabel})...`);
    } else {
      await Promise.all([
        esbuild.build({ ...bgOptions, outdir: resolve(distDir, 'background') }),
        esbuild.build({ ...contentOptions, outdir: resolve(distDir, 'content') }),
        esbuild.build({ ...popupOptions, outdir: resolve(distDir, 'popup') }),
      ]);
      copyStaticAssets(distDir);
      console.log(`✅ Build complete → dist/${target}/ (${browserLabel})`);
    }
  } catch (err) {
    console.error('Build failed:', err);
    process.exit(1);
  }
}

build();
