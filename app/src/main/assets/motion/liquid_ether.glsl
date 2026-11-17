// Liquid Ether — ported from react-bits LiquidEther (DavidHDev/react-bits, MIT).
// The reference runs a real fluid solve in ping-pong targets. A share card is
// sampled at an arbitrary time — it cannot own persistent state — so the look
// is reproduced analytically: a domain-warped fbm whose two warps drift at
// different speeds, which is what the fluid's advection produces visually, then
// shaded like a glossy liquid.
uniform float4 uBg;
uniform float4 uPrimary;
uniform float4 uSecondary;
uniform float4 uCrest;
uniform float4 uHorizon;
uniform float4 uDeep;
uniform float4 uTweak;
uniform float uTime;
uniform float uResX;
uniform float uResY;
uniform float uSeed;

float hash12(float2 p) {
  float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
  q += dot(q, float3(q.y, q.z, q.x) + 33.33);
  return fract((q.x + q.y) * q.z);
}

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
  float s = 0.0;
  float a = 0.55;
  for (int i = 0; i < 3; i++) {
    s += a * noise(p);
    p = p * 2.03 + 11.7;
    a *= 0.5;
  }
  return s;
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord / float2(uResX, uResY);
  float aspect = uResX / uResY;
  float2 p = float2(uv.x * aspect, uv.y) * 2.4;

  // Two slow, incommensurate drifts: the fluid never repeats, it only folds.
  float2 warp = float2(
    fbm(p + float2(uTime * 0.055, uTime * -0.031)),
    fbm(p + float2(4.7, 1.3) + float2(uTime * -0.042, uTime * 0.06))
  );
  float2 q = p + (warp - 0.5) * 2.6;

  float body = fbm(q * 1.35 + float2(0.0, uTime * 0.02));
  float sharp = smoothstep(0.25, 0.85, body);

  // A second, tighter sample gives the thin bright folds that read as the
  // fluid's surface tension.
  float edge = fbm(q * 2.9 - float2(uTime * 0.02, 0.0));
  float sheen = smoothstep(0.55, 0.78, edge) * (1.0 - sharp * 0.6);

  float3 col = mix(uDeep.rgb, uPrimary.rgb, sharp * 0.95);
  col = mix(col, uSecondary.rgb, smoothstep(0.30, 0.92, body) * 0.85);
  col = mix(col, uCrest.rgb, sheen * sheen * 0.42);
  // The folds that face away from the light sink back into the deep tone.
  col = mix(uDeep.rgb * 0.75, col, smoothstep(0.12, 0.62, body));

  // Vignette into the card's own background so the scene sits in the frame
  // instead of filling it edge to edge.
  float2 v = (uv - 0.5) * float2(1.05, 1.0);
  float vig = 1.0 - smoothstep(0.42, 0.95, length(v)) * 0.55;
  col = mix(uBg.rgb, col, vig * smoothstep(0.1, 0.75, body) * uTweak.w);
  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
