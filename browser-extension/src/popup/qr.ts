// ============================================================================
// QR Code generator wrapper for Tempo Stats browser extension.
// Uses the `qrcode-generator` npm package — battle-tested, ZXing-compatible.
// ============================================================================

import qrcodegen from 'qrcode-generator';

/**
 * Generate a QR code as an SVG data-URL.
 * @param text  Payload string.
 * @param size  Desired output px size (default 200).
 */
export function generateQrDataUrl(text: string, size = 200): string {
  const svg = buildSvg(text, size);
  return 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svg)));
}

function buildSvg(text: string, size: number): string {
  // typeNumber 0 = auto-pick smallest version; ECC M
  const qr = qrcodegen(0, 'M');
  qr.addData(text);
  qr.make();

  const count  = qr.getModuleCount();
  const margin = 4; // quiet-zone cells
  const total  = count + margin * 2;
  const cellPx = Math.max(1, Math.floor(size / total));
  const dim    = total * cellPx;

  let rects = '';
  for (let r = 0; r < count; r++) {
    for (let c = 0; c < count; c++) {
      if (qr.isDark(r, c)) {
        const x = (c + margin) * cellPx;
        const y = (r + margin) * cellPx;
        rects += `<rect x="${x}" y="${y}" width="${cellPx}" height="${cellPx}"/>`;
      }
    }
  }

  return (
    `<svg xmlns="http://www.w3.org/2000/svg" version="1.1" ` +
    `viewBox="0 0 ${dim} ${dim}" width="${dim}" height="${dim}" ` +
    `shape-rendering="crispEdges">` +
    `<rect width="100%" height="100%" fill="#FFFFFF"/>` +
    `<g fill="#000000">${rects}</g>` +
    `</svg>`
  );
}
