// Gradient ASCII — a port of ReactBits "Gradient ASCII".
//
// The reference is four passes: a half-res FIELD (a `horizon` gradient scene in
// OKLab, plus a pointer-scramble mask), a quarter-res copy of the glyph layer
// blurred twice into a halo, and a full-res COMPOSITE. This port folds FIELD and
// GLYPHS into one pass — the field is evaluated at the cell's own centre, which
// is what the reference's glyph pass samples anyway — and replaces the blur with
// a cheap additive haze, because a quarter-res blur pass needs render targets a
// RuntimeShader cannot own.
//
// Everything that decides the picture is the reference's: the `horizon` shape
// with its arch, sun and rim (and the blank foot under the arch that comes out
// of its own alpha going to zero there); the two noise warps; the four-stop
// OKLab ramp; the tone→index lookup, the per-cell flicker tick and the density
// threshold. The ramp's stops are built from the card palette plus a hue
// rotation, which is what makes the scene take the artwork's colour and still
// read as several colours the way the reference's four fixed ones do.

uniform shader uAtlas;
uniform float4 uBg;
uniform float4 uPrimary;
uniform float4 uSecondary;
uniform float4 uCrest;
uniform float4 uHorizon;
uniform float4 uDeep;
uniform float uTime;
uniform float uResX;
uniform float uResY;
uniform float uAspect;
uniform float uGlyphPx;
uniform float uGlyphCount;
uniform float4 uTweak;

// Dave Hoskins hash12: no sin, no axis-aligned mesh in the noise.
float hash12(float2 p) {
  float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
  p3 += dot(p3, float3(p3.y, p3.z, p3.x) + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// Value noise with the reference's quintic fade. The reference uses gradient
// noise; at glyph-cell scale the two are the same soft cloud, and the value
// form costs four reads an octave instead of four dot products.
float noise(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = hash12(i);
  float b = hash12(i + float2(1.0, 0.0));
  float c = hash12(i + float2(0.0, 1.0));
  float d = hash12(i + float2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
  float v = 0.0;
  float a = 0.5;
  for (int i = 0; i < 5; i++) {
    v += a * noise(p);
    p = p * 2.03 + float2(17.0, 9.2);
    a *= 0.5;
  }
  return v;
}

// OKLab, because the reference converts its four ramp colours to OKLab before
// uploading them and mixes the tones there. Mixing independent hues in sRGB is
// what turns a green→blue ramp into a grey→mud ramp through the middle.
float3 srgbToLinear(float3 c) {
  c = clamp(c, 0.0, 1.0);
  return mix(c / 12.92, pow((c + 0.055) / 1.055, float3(2.4)), step(0.04045, c));
}

float3 linearToSrgb(float3 c) {
  c = clamp(c, 0.0, 1.0);
  return mix(c * 12.92, 1.055 * pow(c, float3(1.0 / 2.4)) - 0.055,
      step(0.0031308, c));
}

float3 srgbToOklab(float3 c) {
  float3 lc = srgbToLinear(c);
  float l = 0.4122214708 * lc.r + 0.5363325363 * lc.g + 0.0514459929 * lc.b;
  float m = 0.2119034982 * lc.r + 0.6806995451 * lc.g + 0.1073969566 * lc.b;
  float s = 0.0883024619 * lc.r + 0.2817188376 * lc.g + 0.6299787005 * lc.b;
  float3 lms = pow(max(float3(l, m, s), float3(0.0)), float3(1.0 / 3.0));
  return float3(
      0.2104542553 * lms.x + 0.7936177850 * lms.y - 0.0040720468 * lms.z,
      1.9779984951 * lms.x - 2.4285922050 * lms.y + 0.4505937099 * lms.z,
      0.0259040371 * lms.x + 0.7827717662 * lms.y - 0.8086757660 * lms.z);
}

float3 oklabToSrgb(float3 lab) {
  float l_ = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
  float m_ = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
  float s_ = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
  float l = l_ * l_ * l_;
  float m = m_ * m_ * m_;
  float s = s_ * s_ * s_;
  return linearToSrgb(float3(
      4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
      -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
      -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s));
}

// Same colour, rotated `dh` radians around the OKLab hue circle, lifted to at
// least `chroma` so a near-grey stop still separates, and re-lit at `lightness`.
// This is how the reference's fixed cool tail is rebuilt from the card's own
// hue. A rotation of the (a, b) pair instead of an atan round-trip keeps it to
// two multiplies and avoids the two-argument atan AGSL does not reliably have.
float3 okHue(float3 lab, float dh, float chroma, float lightness) {
  float2 ab = lab.yz;
  float len = length(ab);
  ab = len > 0.0001 ? ab / len * max(len, chroma) : float2(chroma, 0.0);
  float cs = cos(dh);
  float sn = sin(dh);
  return float3(lightness, ab.x * cs - ab.y * sn, ab.x * sn + ab.y * cs);
}

// The reference's `horizon` shape, verbatim: an arch, a 0.38 rim above it, and a
// sky that brightens away from a sun parked near the top edge. Returns
// (rim, sky, edge order).
float3 horizon(float2 p, float time) {
  float span = 0.5 * max(uAspect, 1.0);
  float x = p.x / span;
  // The arch is the reference's own, unmoved: -0.17 in the field's y-up space,
  // so the foot of a 9:16 card sits below it. This port used to push the arch
  // down by 0.30 in portrait so the sky filled the card; that is what painted
  // over the blank arc. Below the arch the rim term is 0 and shape.z is 0, so
  // `tone(0)` wins with its alpha of 0 and the field draws nothing there.
  float arch = -0.17 - 0.2 * x * x + 0.02 * sin(time * 0.6);
  float soft = 0.05 + (0.22 - 0.05) * 0.5;
  float edge = p.y - arch;
  float rim = 0.38 * smoothstep(-soft, 0.42, edge);
  // The sun is the field's hot spot: near it the ramp lands on the vivid stop,
  // far from it on the pale one, and that distance is what draws the scene's
  // colour chord. The reference places it at y = 0.58 in a 16:9 frame, where
  // the top edge is 0.5 — just off-frame, with its 0.95-wide falloff reaching
  // the whole canvas. On a 9:16 card the same numbers put the sun far outside
  // and leave every cell at "as far as possible": a flat pale sheet. So the
  // sun is pulled back inside the frame and the falloff is normalised to the
  // card's own half-extent, which puts the visible band back where the
  // reference has it (vivid under the sun, pale towards the edges).
  float portrait = 1.0 - smoothstep(0.55, 1.0, uAspect);
  float sunY = mix(0.58, 0.30, portrait);
  float2 sun = float2(0.08 * sin(time * 0.31) * span, sunY);
  float2 falloff = float2(mix(0.95 * span, 0.48, portrait), mix(0.75, 0.55, portrait));
  float spread = length((p - sun) / falloff);
  // The curve is what decides which stop the scene mostly lands on. The
  // reference's own 0.2..0.95 ramp spends most of a wide frame drifting across
  // its two palest stops; on a narrow card that becomes a sheet of near-white
  // with the saturated stop reduced to a hotspot. Starting the ramp at 0.62
  // keeps the field on its saturated stops for the body of the card and lets
  // the pale end appear only around the sun.
  float sky = mix(0.62, 1.0, smoothstep(0.15, 0.80, spread));
  return float3(rim, sky, smoothstep(0.18, 0.62, edge));
}

// The reference's `tone()` ramp: smoothstepped input across four stops, mixed
// pairwise by the fractional stop. Every argument and result here is OKLab.
float3 ramp(float t, float3 c0, float3 c1, float3 c2, float3 c3) {
  float x = smoothstep(0.3, 1.0, clamp(t, 0.0, 1.0)) * 3.0;
  float3 col = mix(c0, c1, clamp(x, 0.0, 1.0));
  col = mix(col, c2, clamp(x - 1.0, 0.0, 1.0));
  col = mix(col, c3, clamp(x - 2.0, 0.0, 1.0));
  return col;
}

float glyphCoverage(float index, float2 local) {
  float column = mod(index, 16.0);
  float row = floor(index / 16.0);
  float cellW = uGlyphPx * 0.6;
  float2 at = float2((column + local.x) * cellW, (row + local.y) * uGlyphPx);
  return uAtlas.eval(at).a;
}

half4 main(float2 fragCoord) {
  float2 res = float2(uResX, uResY);
  float aspect = res.x / res.y;

  // Cell grid. uTweak.z is the column count on a 1080-wide card, so the grid is
  // the same in the preview, in a chip and in the exported frame. Cells are
  // 60:100 like the atlas, and the leftover pixels split as a margin.
  float cellW = res.x / uTweak.z;
  float cellH = cellW / 0.6;
  float2 grid = float2(ceil(res.x / cellW), ceil(res.y / cellH));
  float2 offset = (res - grid * float2(cellW, cellH)) * 0.5;
  float2 coord = (fragCoord - offset) / float2(cellW, cellH);
  float2 cell = floor(coord);
  float2 local = coord - cell;

  // Field sample position: the cell centre, in the reference's y-up space.
  float2 center = (cell + 0.5) * float2(cellW, cellH) + offset;
  float2 p = float2(center.x / res.x - 0.5, 0.5 - center.y / res.y) *
      float2(aspect, 1.0);

  // Two warps of the field's own noise (flow 0.5, the shape's 0.18 amplitude).
  float2 q = p;
  float2 warp = float2(
      noise(q * 1.6 + float2(0.0, uTime * 0.3)),
      noise(q * 1.6 + float2(4.3, -uTime * 0.24))) - 0.5;
  q += warp * 0.5 * 0.18;

  float3 shape = horizon(q, uTime);

  // Ramp: four independent stops, ordered the way the reference orders its
  // own — the card's vivid at the foot of the tone curve, near-white above it,
  // then a cool pair. The reference's cool pair is fixed (#a5d8ff, #38bdf8).
  // Here each of those two is the average of itself and the card's own primary
  // turned away from it: the reference's sky anchors the chord, the cover's hue
  // tints it. A green cover therefore lands back on the reference's blues, a
  // warm cover lands on the teal across the circle from it, and the white stop
  // in between keeps the two from ever reading as two unrelated scenes.
  float3 labP = srgbToOklab(uPrimary.rgb);
  float3 labSky = srgbToOklab(float3(0.22, 0.74, 0.97));
  float3 labPale = srgbToOklab(float3(0.65, 0.85, 1.0));
  // The reference's first stop is its most saturated colour, and everything
  // under a tone of 0.3 is exactly that stop. So the deep end here is the
  // cover's vivid with only a little ink in it, not a darkened page tone.
  float3 c0 = mix(labP, srgbToOklab(uDeep.rgb), 0.22);
  float3 c1 = mix(srgbToOklab(uCrest.rgb), float3(1.0, 0.0, 0.0), 0.38);
  float3 c2 = mix(okHue(labP, 1.85, 0.105, 0.66), labPale, 0.58);
  float3 c3 = mix(okHue(labP, 1.45, 0.135, 0.56), labSky, 0.58);

  float3 colA = ramp(shape.x, c0, c1, c2, c3);
  float3 colB = ramp(shape.y, c0, c1, c2, c3);
  float3 fieldRgb = oklabToSrgb(mix(colA, colB, shape.z));
  float fieldA = mix(
      smoothstep(0.0, 0.38, shape.x),
      smoothstep(0.0, 0.38, shape.y),
      shape.z);
  // The reference stores the field premultiplied and reads .rgb afterwards.
  float3 field = fieldRgb * fieldA;

  float light = step(0.5, dot(uBg.rgb, float3(0.2126, 0.7152, 0.0722)));
  float3 visible = light > 0.5 ? field + (1.0 - fieldA) : field;
  float luma = dot(visible, float3(0.2126, 0.7152, 0.0722));
  float value = light > 0.5 ? clamp((1.0 - luma) * 1.5, 0.0, 1.0) : luma;

  // Glyph pick: a stable hash per cell decides presence, a 9 Hz tick wobbles
  // the tone so the field breathes the way the reference's flicker does.
  float pick = hash12(cell);
  float level = hash12(cell * 1.73 + float2(4.1, 4.1));
  float tick = floor(uTime * 9.0 + level * 9.0);
  float wobble = (hash12(cell * 2.31 + float2(tick * 17.7, tick * 17.7)) - 0.5) *
      0.2 * 0.4 + (level - 0.5) * 0.1;
  float tone = clamp(pow(value, 0.75) + wobble, 0.0, 1.0);
  float index = floor(tone * (uGlyphCount - 1.0) + 0.5);
  float present = step(pick, 0.8 * mix(1.0, 0.3 + tone * 1.1, 0.55));
  float cutoff = smoothstep(0.03, 0.1, value);
  float coverage = glyphCoverage(index, local);
  float alpha = 0.82 * present * cutoff * coverage *
      mix(1.0, 0.35 + 0.65 * level, 0.55);

  // Composite. The reference's dark path is `base + glyph + halo` over a black
  // page: `base` is its premultiplied field times gradientOpacity, and the
  // glyphs are additive light that carries the field's own hue
  // (`field.rgb * 0.55 + 0.03 * field.a`). Both are *additions* to the page, not
  // a mix towards the field, which is why the reference's page stays near-black
  // and only its marks and its cloud carry colour. The port used to mix the
  // whole card towards the field (`uBg * (1 - fieldA * gradient) + field *
  // gradient`, at 0.85) plus a second `field * 0.08` haze, so the card turned
  // into one flat wash of the cover's hue.
  //
  // This card's page is not black, it is the cover's own darkened tone, so a
  // bare addition would still drown the ramp: a warm cover turns the whole card
  // warm and the other three stops have nothing to show against. Holding the
  // page at a fifth of itself leaves the artwork's tone as a cast and lets the
  // four stops read as four colours, which is the point of the scene.
  float3 page = uBg.rgb * 0.22;
  float3 outc;
  if (light > 0.5) {
    // The reference's light path prints dark glyphs over the field.
    float3 base = uBg.rgb * (1.0 - fieldA * 0.55) + field * 0.55;
    outc = base * (1.0 - alpha) + visible * 0.8 * alpha;
  } else {
    // The cloud is added at 0.34 — enough to tint the page, not enough to become
    // its own bright sheet — while the marks go on top at 1.5, so the card reads
    // as light type on a dark ground instead of pale type on a pale ground. The
    // reference gets away with a brighter cloud because its page is black; on a
    // card whose page already carries the cover's tone the ground has to stay
    // the dark one for the marks to be the brightest thing in the frame.
    float3 base = page + field * 0.34;
    outc = base + (field * 1.5 + 0.02 * fieldA) * alpha;
  }

  // Film grain, as the reference's composite does on top of everything.
  float grain = (hash12(fragCoord + uTime * float2(17.0, 59.0)) +
      hash12(fragCoord * 1.31 + uTime * 7.0) - 1.0) * 0.08;
  outc += grain * (0.25 + fieldA) * 0.5;

  return half4(half3(clamp(outc, 0.0, 1.0)), 1.0);
}
