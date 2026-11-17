// Gradient Waves — ported from react-bits GradientWaves (DavidHDev/react-bits, MIT).
// GPU fragment shader. Sampled at an arbitrary time, so the preview and the
// encoder share one definition of the scene.
uniform float4 uBg;
uniform float4 uPrimary;
uniform float4 uCrest;
uniform float4 uHorizon;
uniform float4 uTweak;
uniform float uTime;
uniform float uResX;
uniform float uResY;
uniform float uSeed;

const float FAR = 4000.0;
const float CAMERA_HEIGHT = 30.0;
const float HEIGHT = 5.5;
const float AMPLITUDE = 2.5;
const float WAVE_SCALE = 0.6;
const float WAVE_RATIO = 0.9;
const float SWELL = 35.0;
const float TURBULENCE = 20.0;
const float FOG_DEPTH = 15.0;
const float STRIDE = 0.9;
const int STEPS = 44;

float hash(float2 p) {
  float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
  p3 += dot(p3, float3(p3.y, p3.z, p3.x) + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// The wave height field: y, plus its slope in x and y.
float3 surface(float2 xy) {
  float2 freq = float2(WAVE_SCALE / 7.0, (WAVE_SCALE * WAVE_RATIO) / 3.0);
  float4 tc = float4(uTime / 0.130, uTime / 0.810, uTime / 0.200, uTime / 0.710);
  float a = xy.x + tc.x;
  float swellPhase = (xy.y + a) / 20.0 + tc.y;
  float bend = SWELL * cos(swellPhase) / 20.0;
  float mx = a + SWELL * sin(swellPhase);
  float crossPhase = xy.x / 23.0 + tc.w;
  float my = xy.y - tc.z + TURBULENCE * cos(crossPhase);
  float twist = -TURBULENCE * sin(crossPhase) / 23.0;
  float cx = cos(mx * freq.x);
  float cy = cos(my * freq.y);
  float h = (sin(mx * freq.x) + sin(my * freq.y)) * AMPLITUDE + HEIGHT;
  float dx = AMPLITUDE * (freq.x * cx * (1.0 + bend) + freq.y * cy * twist);
  float dy = AMPLITUDE * (freq.x * cx * bend + freq.y * cy);
  return float3(h, dx, dy);
}

float march(float3 origin, float3 dir) {
  float dist = 0.0;
  for (int i = 0; i < STEPS; i++) {
    float gap = origin.z + dist * dir.z - surface(origin.xy + dist * dir.xy).x;
    if (abs(gap) < 0.002 * dist + 0.01) break;
    dist += STRIDE * gap;
    if (abs(dist) > FAR) return FAR;
  }
  return dist;
}

half4 main(float2 fragCoord) {
  float3 sun = float3(
    cos(0.52) * cos(2.0943951),
    cos(0.52) * sin(2.0943951),
    sin(0.52)
  );

  float vfov = (3.14159265 / 2.3);
  float3 cam = float3(0.0, 0.0, CAMERA_HEIGHT);
  float2 uv = fragCoord / float2(uResX, uResY) - 0.5;
  // A card is a tall portrait frame; widen the frustum so the field still
  // reads instead of being cropped to a central slice.
  uv.x *= uResX / uResY;

  float ulen = length(uv);
  float xrot = vfov * ulen;
  float c = cos(xrot);
  float s = sin(xrot);
  // rotate (0,0,-1) about x
  float3 dir = float3(0.0, -s, -c);
  float2 nuv = ulen > 1e-5 ? uv / ulen : float2(1.0, 0.0);
  // rotate about z with (cos,sin) = (nuv.x, nuv.y), transposed signs
  dir = float3(nuv.x * dir.x + nuv.y * dir.y, -nuv.y * dir.x + nuv.x * dir.y, dir.z);
  // tilt pitch about y: rows [c,0,-s; 0,1,0; s,0,c]
  c = cos(1.1083914);
  s = sin(1.1083914);
  dir = float3(c * dir.x - s * dir.z, dir.y, s * dir.x + c * dir.z);

  float dist = march(cam, dir);
  float3 pos = cam + dist * dir;
  float3 field = surface(pos.xy);
  float3 view = -dir;

  float crest = clamp((field.x - HEIGHT) / max(2.0 * AMPLITUDE, 0.001) * 0.5 + 0.5, 0.0, 1.0);
  float3 body = mix(uPrimary.rgb, uCrest.rgb, smoothstep(0.45, 1.0, crest) * 0.5);
  float3 normal = normalize(float3(-field.y * 2.5, -field.z * 2.5, 1.0));
  float diffuse = clamp(dot(normal, sun) * 0.5 + 0.5, 0.0, 1.0);
  float lightness = diffuse * diffuse * (3.0 - 2.0 * diffuse);
  float rim = pow(1.0 - clamp(dot(normal, view), 0.0, 1.0), 3.0);

  float haze = 1.0 - exp(-dist / (FOG_DEPTH * 5.0));
  float fade = 1.0 - smoothstep(0.35, 1.0, haze);
  float3 lit = mix(uHorizon.rgb * 0.18, body, lightness);
  lit = mix(lit, uHorizon.rgb, rim * 0.15);
  float3 col = mix(lit, uHorizon.rgb, smoothstep(0.0, 1.0, haze)) * 0.85;
  float alpha = fade * uTweak.w;

  col = clamp(col, 0.0, 1.0);
  float grain = (hash(fragCoord + fract(uTime * 0.7) * 64.0) - 0.5) * uTweak.y;
  alpha = clamp(alpha * (1.0 + grain), 0.0, 1.0);
  // uBg is the card's own gradient; compositing here keeps the haze honest
  // instead of letting it thin out over an unrelated colour.
  float3 outRgb = mix(uBg.rgb, col, alpha);
  return half4(half3(outRgb), 1.0);
}
