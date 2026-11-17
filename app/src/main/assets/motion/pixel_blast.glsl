// Pixel Blast — a port of ReactBits "Pixel Blast".
//
// The reference is two passes: CELL paints one texel per 8×8-pixel cell with a
// five-octave fbm field (plus click ripples and an intro reveal), MAIN walks the
// pixels, thresholds the interpolated field against an 8×8 Bayer matrix and
// draws a soft square per pixel. Smoothing defaults to 1, so MAIN reads the cell
// texture bilinearly — i.e. the smooth field, not the per-cell constant. This
// port evaluates that same field where the fragment is (one fbm read instead of
// a texture fetch) and keeps MAIN verbatim: Bayer bits, soft square mask,
// twinkle, bloom, edge fade, colour mix.
//
// The field's third noise axis is folded into a slow offset, and two ripples
// fire per loop from seeded positions: a share card has no pointer, and a 6s
// export that never blasts would be a still frame.

uniform float4 uBg;
uniform float4 uPrimary;
uniform float4 uSecondary;
uniform float4 uCrest;
uniform float uTime;
uniform float uResX;
uniform float uResY;
uniform float uLoop;

// The reference's hash11 / vnoise lattice, with the 3D value noise reduced to
// 2D (four lattice reads per octave) and the slow z drift folded into p.
float hash11(float n) {
  return fract(sin(n) * 43758.5453);
}

float vnoise(float2 p) {
  float2 ip = floor(p);
  float2 fp = fract(p);
  float2 w = fp * fp * fp * (fp * (fp * 6.0 - 15.0) + 10.0);
  float n00 = hash11(dot(ip, float2(1.0, 57.0)));
  float n10 = hash11(dot(ip + float2(1.0, 0.0), float2(1.0, 57.0)));
  float n01 = hash11(dot(ip + float2(0.0, 1.0), float2(1.0, 57.0)));
  float n11 = hash11(dot(ip + float2(1.0, 1.0), float2(1.0, 57.0)));
  float x0 = mix(n00, n10, w.x);
  float x1 = mix(n01, n11, w.x);
  return mix(x0, x1, w.y) * 2.0 - 1.0;
}

// Five octaves, unit amplitude, 1.25 frequency step, sum seeded at 1 — the
// reference's fbm2, which is why the field sits off-centre instead of at 0.5.
float fbm(float2 p) {
  float sum = 1.0;
  float freq = 1.0;
  for (int i = 0; i < 5; i++) {
    sum += vnoise(p * freq);
    freq *= 1.25;
  }
  return sum * 0.5 + 0.5;
}

float bayer2(float x, float y) {
  return mod(floor(x) * 0.5 + floor(y) * floor(y) * 0.75, 1.0);
}

float bayer8(float x, float y) {
  return (bayer2(x * 0.25, y * 0.25) * 0.25 + bayer2(x * 0.5, y * 0.5)) * 0.25 +
      bayer2(x, y);
}

half4 main(float2 fragCoord) {
  float2 res = float2(uResX, uResY);
  float aspect = res.x / res.y;
  // pixelSize 3 CSS px on a 360dp card: 120 distinct pixels across the card and
  // 15 cells, exactly the reference's proportion.
  float px = res.x / 120.0;
  float cps = 8.0 * px;
  float2 centered = fragCoord - 0.5 * res;
  float2 uv = (centered / res) * float2(aspect, 1.0);

  // Cell field: fbm at uTime * 0.05, drift on the 30° axis at 0.2/s, folded
  // into a slow offset rather than a third noise axis. On top of the drift the
  // field itself wanders: the reference's cloud is anchored to the frame, and a
  // share card watched for six seconds needs the blast to travel rather than
  // sit in the middle. The wander is periodic in uLoop so the exported video
  // has no seam where it repeats.
  float t = uTime * 0.05;
  float2 drift = float2(0.8660254, 0.5) * (0.2 * uTime * 0.05);
  float lt = mod(uTime, uLoop) * 6.2831853 / uLoop;
  float2 wander = float2(sin(lt), cos(lt * 2.0)) * float2(0.26, 0.42);
  float base = fbm(uv + drift + wander + float2(t * 0.9, -t * 0.6));
  // The reference's `base * 0.5 - 0.65` leaves the field's mean at 0, and its
  // Bayer margin (feed + bit - 0.5) then lights almost nothing — on a desktop
  // canvas the field is a sparse cloud that only reveals itself under a cursor.
  // A share card has no cursor, so the fbm is remapped onto coverage directly:
  // the whole card is the cloud, dense where the noise peaks, empty at the
  // fringes, exactly the reference's dithered read.
  float feed = clamp((base - 1.0) * 0.78 + 0.50, 0.0, 1.0);

  // A travelling core. The noise alone gives an even stretch of cells at card
  // scale — a screen, not a blast — so a soft hot spot rides a loop-periodic
  // path around the frame, and the cloud always has one mass crossing it. The
  // path is measured in screen space (square, so the spot stays round) rather
  // than in uv, which is aspect-corrected.
  float2 screenN = centered / res;
  float2 focus = float2(0.30 * sin(lt), 0.34 * cos(lt * 0.5 + 1.1));
  float spot = exp(-pow(distance(screenN, focus) / 0.34, 2.0));
  feed = clamp(feed + spot * 0.30, 0.0, 1.0);

  // Two ripples per loop, from the reference's click formula (speed 0.3,
  // thickness 0.1, intensity 1, age in half-seconds). The reference fires them
  // from a click; a card has no pointer, so each cycle seeds a fresh pair of
  // coordinates and the blasts keep landing in new places instead of pulsing
  // from one spot.
  float loopIndex = floor(uTime / uLoop);
  for (int k = 0; k < 2; k++) {
    float fk = float(k);
    float s1 = fract(sin((loopIndex + fk * 7.0) * 78.233) * 43758.5453);
    float s2 = fract(sin((loopIndex + fk * 3.0) * 39.425) * 24634.6345);
    float2 pos = res * (float2(0.16, 0.14) + float2(0.68, 0.72) * float2(s1, s2));
    float age = mod(uTime - (0.6 + fk * 2.6), uLoop) * 0.5;
    float2 cuv = ((pos - res * 0.5 - cps * 0.5) / res) * float2(aspect, 1.0);
    float r = distance(uv, cuv);
    float waveR = 0.3 * age;
    float ring = exp(-pow((r - waveR) / 0.1, 2.0));
    float atten = exp(-age) * exp(-10.0 * r);
    feed = max(feed, ring * atten);
  }

  // MAIN: one pixel of the grid, its Bayer bit, and the soft square mask.
  float2 pixelId = floor(centered / px);
  float2 pixelUV = centered / px - pixelId;
  float2 bits = mod(pixelId, 8.0);
  float bayer = bayer8(bits.x, bits.y) - 0.5;
  float margin = feed + bayer - 0.5;
  float bw = smoothstep(-0.072, 0.072, margin);

  float h = fract(sin(dot(pixelId, float2(127.1, 311.7))) * 43758.5453);
  float heat = max(0.0, clamp((feed - 1.0) * 2.5, 0.0, 1.0) * 0.8);
  float coverage = bw * (1.0 + heat * 0.35);

  // maskSquare: the coverage is a side length, and the coverage's own gradient
  // sets the antialias width. The draw is axis-aligned at 1:1, so the two
  // derivatives are the pixel footprint, not a dFdx probe.
  float side = 0.5 * sqrt(clamp(coverage, 0.0, 1.0));
  float2 c = pixelUV - 0.5;
  float2 q = abs(c) - side;
  float d = max(q.x, q.y);
  float2 gDx = float2(1.0 / px, 0.0);
  float2 gDy = float2(0.0, 1.0 / px);
  float2 grad = q.x > q.y ? float2(sign(c.x), 0.0) : float2(0.0, sign(c.y));
  float aa = max(abs(dot(grad, gDx)) + abs(dot(grad, gDy)), 1e-4);
  float M = 1.0 - smoothstep(-aa * 0.5, aa * 0.5, d);

  // Twinkle: per-pixel rate from the same hash, quarter strength at 0.25.
  float rate = 1.5 + 3.0 * fract(h * 7.31);
  float wave = 0.5 + 0.5 * sin(uTime * rate + h * 37.0);
  M *= 1.0 - 0.25 * 0.55 * wave * wave;

  float density = clamp(feed, 0.0, 1.0);
  float bloom = smoothstep(0.2, 1.0, density);
  float light = step(0.5, dot(uBg.rgb, float3(0.2126, 0.7152, 0.0722)));
  float glow = bloom * bloom * 0.4 * (light > 0.5 ? 0.12 : 0.22);

  // Edge fade: quintic falloff on both axes over 0.5 of the frame.
  float2 norm = fragCoord / res;
  float2 edgeA = clamp(norm / 0.5, 0.0, 1.0);
  float2 edgeB = clamp((1.0 - norm) / 0.5, 0.0, 1.0);
  float2 sa = edgeA * edgeA * edgeA * (edgeA * (edgeA * 6.0 - 15.0) + 10.0);
  float2 sb = edgeB * edgeB * edgeB * (edgeB * (edgeB * 6.0 - 15.0) + 10.0);
  float fade = sa.x * sa.y * sb.x * sb.y;
  M = clamp(M, 0.0, 1.0) * fade;
  glow *= fade;

  // Colour: the artwork's vivid tone, its light tone on the densest cells, and
  // a white-hot lift where a ripple passes.
  float hot = clamp(heat, 0.0, 1.0);
  float3 accent = mix(uSecondary.rgb, uCrest.rgb, 0.35);
  float tone = smoothstep(0.3, 1.0, density) * 0.85;
  float3 color = mix(uPrimary.rgb, accent, max(tone, hot));
  float3 lit = light > 0.5 ? color * (1.0 - 0.35 * hot) : mix(color, float3(1.0), 0.45 * hot);
  float3 haze = light > 0.5 ? mix(color, float3(1.0), 0.35) : color;

  // The reference composites premultiplied over a transparent canvas; a share
  // card needs the page behind it.
  float alpha = M + glow * (1.0 - M);
  float3 premul = lit * M + haze * glow * (1.0 - M);
  float3 outC = premul + uBg.rgb * (1.0 - alpha);
  return half4(half3(clamp(outC, 0.0, 1.0)), 1.0);
}
