// Exercise the real popup handlers and port responses with a minimal DOM fixture.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const out = join(mkdtempSync(join(tmpdir(), 'tempo-drive-popup-')), 'popup.mjs');
await esbuild.build({
  entryPoints: ['src/popup/drive-popup.ts'], bundle: true, format: 'esm', platform: 'browser',
  target: 'es2022', outfile: out, logLevel: 'silent',
  define: { __TEMPO_BROWSER_TARGET__: JSON.stringify('chrome') },
});
const status = {
  enabled: true, configured: true, connected: false, needsInteractiveAuth: true,
  accountEmail: 'test@example.com', lastError: null, lastSyncTime: null, lastUploaded: 0, lastImported: 0,
};
const tick = () => new Promise(resolve => setImmediate(resolve));
async function settle() { for (let i = 0; i < 5; i++) await tick(); }

async function openPopup(initial, responseStatus, instance) {
  const commands = [];
  const nodes = new Map();
  const makeNode = () => ({ style: {}, disabled: false, textContent: '', handlers: {}, attributes: {},
    addEventListener(type, callback) { this.handlers[type] = callback; },
    setAttribute(name, value) { this.attributes[name] = value; },
    focus() { this.focused = true; },
    querySelector() { return null; },
    appendChild(child) { nodes.set(child.id, child); },
  });
  for (const id of ['tab-settings', 'drive-sync-account', 'drive-sync-status',
    'drive-sync-details', 'drive-sync-detail-text',
    'btn-drive-connect', 'btn-drive-sync', 'btn-drive-restore', 'btn-drive-share',
    'btn-drive-disconnect', 'btn-drive-delete', 'drive-sync-confirmation',
    'drive-sync-confirm-title', 'drive-sync-confirm-description',
    'btn-drive-confirm', 'btn-drive-cancel']) {
    nodes.set(id, makeNode());
  }
  const documentHandlers = {};
  const emittedEvents = [];
  globalThis.document = {
    getElementById: id => nodes.get(id) ?? null, createElement: makeNode,
    addEventListener(type, fn) { documentHandlers[type] = fn; },
    dispatchEvent(event) {
      emittedEvents.push({ type: event.type, detail: event.detail });
      documentHandlers[event.type]?.(event);
      return true;
    },
  };
  globalThis.CustomEvent = class {
    constructor(type, options) { this.type = type; this.detail = options?.detail; }
  };
  globalThis.window = { setTimeout, clearTimeout };
  globalThis.chrome = { runtime: { connect() {
    let receive;
    return {
      onMessage: { addListener(fn) { receive = fn; } },
      onDisconnect: { addListener() {} }, disconnect() {},
      postMessage({ command }) {
        commands.push(command);
        queueMicrotask(() => receive({ ok: true, status: command === 'status' ? initial : responseStatus }));
      },
    };
  } } };
  await import(`${pathToFileURL(out).href}?instance=${instance}`);
  await settle();
  return { nodes, commands, documentHandlers, emittedEvents };
}

let popup = await openPopup(status, { ...status, connected: true, needsInteractiveAuth: false }, 1);
assert.equal(popup.nodes.get('btn-drive-connect').style.display, '');
assert.equal(popup.nodes.get('btn-drive-connect').textContent, 'Reconnect Google');
assert.equal(popup.nodes.get('btn-drive-sync').style.display, 'none');
assert.equal(popup.nodes.get('btn-drive-delete').style.display, 'none');
popup.nodes.get('btn-drive-connect').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status', 'connect']);
assert.equal(popup.nodes.get('btn-drive-connect').style.display, 'none');
assert.equal(popup.nodes.get('btn-drive-sync').style.display, '');
assert.ok(popup.emittedEvents.some(event =>
  event.type === 'tempo-drive-status' && event.detail.connected === true),
  'Google Drive connectivity is published to Home even without LAN pairing');
console.log('  ✓ expired authorization offers an interactive reconnect and restores sync controls');

popup = await openPopup({ ...status, connected: true, needsInteractiveAuth: false },
  { ...status, configured: false }, 2);
popup.nodes.get('btn-drive-sync').handlers.click();
await settle();
assert.equal(popup.nodes.get('btn-drive-connect').disabled, true);
assert.equal(popup.nodes.get('btn-drive-sync').style.display, 'none');
console.log('  ✓ operation completion preserves the unavailable OAuth configuration state');
// The first click opens a dedicated review panel. A separate Confirm action
// executes the sensitive command; Cancel never sends it.
popup = await openPopup({ ...status, connected: true, needsInteractiveAuth: false },
  { ...status, enabled: false, connected: false, needsInteractiveAuth: false }, 3);
assert.equal(popup.nodes.get('btn-drive-delete').style.display, '');
popup.nodes.get('btn-drive-delete').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status']);
assert.equal(popup.nodes.get('drive-sync-confirmation').style.display, '');
assert.match(popup.nodes.get('drive-sync-confirm-description').textContent, /Local listening history/);
assert.equal(popup.nodes.get('btn-drive-cancel').focused, true);
assert.equal(popup.documentHandlers['tempo-drive-status'], undefined,
  'consumer-less test harness must safely ignore Drive home updates');
popup.nodes.get('btn-drive-cancel').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status', 'status']);
assert.equal(popup.nodes.get('drive-sync-confirmation').style.display, 'none',
  'Cancel closes the review without executing a delete');
assert.equal(popup.nodes.get('btn-drive-delete').focused, true,
  'Cancel restores keyboard focus to the destructive-action trigger');
popup.nodes.get('btn-drive-delete').handlers.click();
await settle();
popup.nodes.get('btn-drive-confirm').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status', 'status', 'delete']);
assert.match(popup.nodes.get('drive-sync-status').textContent, /Deleted 0 Drive history batch/);
assert.equal(popup.nodes.get('drive-sync-confirmation').style.display, 'none');
console.log('  ✓ review panel explains deletion; only Confirm executes; Cancel is safe');

popup = await openPopup({ ...status, connected: true, needsInteractiveAuth: false },
  { ...status, connected: true, needsInteractiveAuth: false,
    lastSyncTime: Date.now(), lastUploaded: 3, lastImported: 2 }, 4);
popup.nodes.get('btn-drive-share').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status']);
assert.match(popup.nodes.get('drive-sync-confirm-description').textContent, /another account/);
popup.nodes.get('btn-drive-confirm').handlers.click();
await settle();
assert.deepEqual(popup.commands, ['status', 'share-history']);
assert.match(popup.nodes.get('drive-sync-status').textContent, /operation completed/,
  'an action outcome must not be immediately replaced by Last sync');
console.log('  ✓ older-history sharing reviews account risk and preserves outcome');

popup = await openPopup({ ...status, connected: true, needsInteractiveAuth: false },
  { ...status, enabled: false, connected: false }, 5);
popup.nodes.get('btn-drive-delete').handlers.click();
await settle();
popup.nodes.get('btn-drive-share').handlers.click();
await settle();
assert.match(popup.nodes.get('drive-sync-confirm-title').textContent, /Share older/);
popup.nodes.get('btn-drive-cancel').handlers.click();
await settle();
assert.equal(popup.commands.filter(x => x === 'delete' || x === 'share-history').length, 0,
  'switching or cancelling a pending action cannot leak an earlier destructive command');
console.log('  ✓ switching confirmation targets never executes the previous action');

popup.nodes.get('btn-drive-delete').handlers.click();
await settle();
let escapePrevented = false;
popup.documentHandlers.keydown({
  key: 'Escape',
  preventDefault() { escapePrevented = true; },
});
await settle();
assert.equal(escapePrevented, true);
assert.equal(popup.nodes.get('drive-sync-confirmation').style.display, 'none');
assert.equal(popup.nodes.get('btn-drive-delete').focused, true,
  'Escape restores keyboard focus to the original trigger');
assert.equal(popup.commands.filter(x => x === 'delete').length, 0,
  'Escape must cancel the review without deleting any cloud data');
console.log('  ✓ keyboard Escape cancels a review without issuing a command');

popup = await openPopup({ ...status, enabled: true, connected: true, needsInteractiveAuth: false,
    lastError: '2 invalid Google Drive history batch(es) were skipped (e.g. history_invalid.json: checksum mismatch). Repair or remove affected files, then Restore all history.' },
  { ...status, connected: true, needsInteractiveAuth: false }, 6);
assert.match(popup.nodes.get('drive-sync-status').textContent, /history records need attention/);
assert.equal(popup.nodes.get('drive-sync-details').style.display, '');
assert.match(popup.nodes.get('drive-sync-detail-text').textContent, /checksum mismatch/);
console.log('  ✓ long repair warnings remain readable and their full detail is accessible');

popup = await openPopup({ ...status, connected: true, needsInteractiveAuth: false },
  { ...status, connected: true, needsInteractiveAuth: false }, 7);
assert.equal(typeof popup.documentHandlers['tempo-drive-sync-request'], 'function',
  'Queue must be able to use the same real cloud command as Drive Settings');
popup.documentHandlers['tempo-drive-sync-request']();
await settle();
assert.deepEqual(popup.commands, ['status', 'sync'],
  'the Queue Drive button must issue cloud sync, not the phone-only SYNC_NOW message');
console.log('  ✓ Queue Drive action calls the authenticated cloud transport');

console.log('8 Drive popup scenarios passed');
