// Dithering — a port of Paper Shaders "Dithering" (Apache-2.0), the shader
// behind the 21st.dev Dithering · Bugs component.
//
// The reference renders one full-screen pass: transform the aspect-corrected
// coordinates (scale, rotate, organic warp), evaluate a five-octave value-noise
// field, then quantise it to four palette stops with a per-dither-cell golden
// ratio threshold. Every constant below is the reference recipe's value, so the
// screen pattern lands on the same cells at the same field values.
//
// The palette is the share card's own: stop 0 is the page, stop 3 the highlight,
// and the two middle stops are the cover's vivid and its lift toward the crest.
// That is the reference's grey ramp read through the artwork instead of a fixed
// recipe.

uniform float4 uBg;
uniform float4 uPrimary;
uniform float4 uCrest;
uniform float4 uDeep;
uniform float uTime;
uniform float uResX;
uniform float uResY;
uniform float uAspect;

// hash21 / grainHash are the reference's exact hashes (Dave Hoskins hash12 for
// the grain, which avoids the axis-aligned mesh the multiply hash shows).
float hash21(float2 p) {
  p = fract(p * float2(234.34, 435.345));
  p += dot(p, p + 34.23);
  return fract(p.x * p.y);
}

float grainHash(float2 p) {
  float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
  p3 += dot(p3, float3(p3.y, p3.z, p3.x) + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 p) {
  float2 i = floor(p);
  float2 f = fract(p);
  float2 u = f * f * (3.0 - 2.0 * f);
  return mix(
      mix(hash21(i), hash21(i + float2(1.0, 0.0)), u.x),
      mix(hash21(i + float2(0.0, 1.0)), hash21(i + float2(1.0, 1.0)), u.x),
      u.y);
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

// Scales a colour away from its own luma, leaving the print's contrast alone.
// The cover's vivid is sampled at 0.62 saturation; without this the middle
// stops stay close to the page and the halftone reads grey-pink.
float3 liftChroma(float3 c, float amount) {
  float l = dot(c, float3(0.2126, 0.7152, 0.0722));
  return clamp(mix(float3(l), c, amount), 0.0, 1.0);
}

half4 main(float2 fragCoord) {
  float2 res = float2(uResX, uResY);
  float minRes = min(res.x, res.y);
  float2 uv = fragCoord / res;
  float2 p = (fragCoord - 0.5 * res) / minRes;
  float t = uTime;

  // Palette: a four-stop print ramp built from the record's own colours, in the
  // reference's order — page, shadow, glow, highlight.
  //
  // The reference's ramp is a neutral grey ladder (#0b0b0c → #f8f9fa). Neutral is
  // exactly what made the card look dead: this is a portrait of the week's
  // record, so the ladder is the cover's instead. On its dark page that reads
  // near-black paper, the cover's vivid in the shadows, that vivid lifted toward
  // its crest as the glow, and its crest as the highlight — four inks from one
  // photograph rather than four greys from a preset.
  float pageLuma = dot(uBg.rgb, float3(0.2126, 0.7152, 0.0722));
  float3 c0 = uBg.rgb;
  float3 c1 = pageLuma > 0.5 ? mix(uBg.rgb, uDeep.rgb, 0.35) : mix(uBg.rgb, uPrimary.rgb, 0.42);
  // 80% crest left the glow a near-white grey-pink; 58% keeps the cover's ink
  // in the mass, and the highlight turns back toward the vivid instead of
  // dissolving into paper.
  float3 c2 = pageLuma > 0.5 ? mix(uBg.rgb, uDeep.rgb, 0.72) : mix(uPrimary.rgb, uCrest.rgb, 0.46);
  float3 c3 = pageLuma > 0.5 ? uDeep.rgb : mix(uCrest.rgb, uPrimary.rgb, 0.36);
  // The dark page is where the print looked dull. Lift the ramp's chroma, not
  // its contrast: the tone curve below is untouched.
  float inkBoost = pageLuma > 0.5 ? 1.0 : 1.34;
  c1 = liftChroma(c1, inkBoost);
  c2 = liftChroma(c2, inkBoost);
  c3 = liftChroma(c3, 1.0 + (inkBoost - 1.0) * 0.6);

  // Field transform: uniform scale, rotation, offset, drift, organic warp —
  // verbatim from the reference, including the warp's two extra fbm reads.
  //
  // p is normalised by the shorter side, which is the height on the reference's
  // landscape canvas but the width on a 9:16 card. Left alone, every cloud would
  // read twice as wide on the card; the zoom keeps the feature size relative to
  // the frame the reference has.
  float portrait = 1.0 - smoothstep(0.55, 1.0, uAspect);
  p *= 2.14 * (1.0 + 0.55 * portrait);
  float cr = cos(2.5831);
  float sr = sin(2.5831);
  p = float2(cr * p.x - sr * p.y, sr * p.x + cr * p.y);
  float2 warp = float2(
      fbm(p * 3.552 + float2(1.0, 1.0)),
      fbm(p * 3.552 + float2(5.2, 1.3)));
  p += 0.198 * (warp - 0.5);

  // shade(): 96.4 dither cells across, golden-ratio threshold per cell, a
  // 4.25-step quantiser, and the field drifting on its own clock. The drift was
  // the recipe's 0.08/-0.05 a second, which is roughly a fifth of a noise cell
  // over the whole 15 s story — the print looked like a still. Speeding it to
  // 0.24/-0.15 crosses a full cell in the same time, so the masses visibly
  // travel and reshape without turning the print into a strobe.
  float cells = 180.0 + (28.0 - 180.0) * 0.55;
  float2 pixel = floor(uv * cells);
  // The field is stretched before quantising: fbm alone spends most of its time
  // near 0.5, so every cell lands on a middle stop and the print reads as flat
  // camo. A smoothstep keeps the noise's masses but empties the paper between
  // them — the reference's cloud of dots over a dark page, rather than a wash.
  float field = smoothstep(0.28, 0.80, fbm(p * 3.5 + float2(t * 0.24, -t * 0.15)));
  float threshold = fract(dot(pixel, float2(0.75487766, 0.56984029)));
  float steps = 2.0 + 5.0 * 0.45;
  float dithered = floor(field * steps + threshold) / steps;

  // palette(): progressive mix through the four stops, exactly as the
  // reference walks its colour array. The quantiser only ever emits five levels
  // (0 / 0.24 / 0.47 / 0.71 / 0.94) and the field rarely reaches the ends, so
  // the levels are stretched across the ramp first. The offset is high because
  // the card's type sits on this print: at a lower one every level above paper
  // carried ink, and a rust ground swallowed the labels. Now the print keeps
  // real paper between its masses — and still puts the cover's colour in them.
  float tone = clamp((dithered - 0.30) / 0.62, 0.0, 1.0);
  float f = tone * 3.0;
  float3 col = c0;
  col = mix(col, c1, smoothstep(0.0, 1.0, clamp(f, 0.0, 1.0)));
  col = mix(col, c2, smoothstep(0.0, 1.0, clamp(f - 1.0, 0.0, 1.0)));
  col = mix(col, c3, smoothstep(0.0, 1.0, clamp(f - 2.0, 0.0, 1.0)));

  // Post: the recipe's contrast nudge and its 0.126 film grain. Vignette,
  // blur, hue and saturation are all neutral in this recipe, so they are not
  // carried (identity maths, and a shader branch is not free on a phone).
  col = (col - 0.5) * 1.005 + 0.5;
  col += (grainHash(fragCoord + float2(17.0, 31.0)) - 0.5) * 0.126;

  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
