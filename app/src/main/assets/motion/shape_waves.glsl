// Shape Waves — ported from react-bits ShapeWaves (DavidHDev/react-bits, MIT).
// A grid of cells, each one a dot/square/triangle whose size follows a noise
// field, so the shapes read as moving waves. The reference keeps a per-cell
// "charge" buffer for pointer ripples; a share card has no pointer, so the
// field alone drives the size and one pass is enough.
uniform float4 uBg;
uniform float4 uPrimary;
uniform float4 uSecondary;
uniform float4 uCrest;
uniform float4 uDeep;
uniform float4 uTweak;
uniform float uTime;
uniform float uResX;
uniform float uResY;

const float CELLS = 27.0;      // cells across the card
const float SEED_X = 12.9898;
const float SEED_Y = 78.233;

float hash21(float2 p) {
  return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float vnoise(float3 p) {
  float3 ip = floor(p);
  float3 fp = fract(p);
  float n000 = hash21(ip.xy + ip.z * 31.0);
  float n100 = hash21(ip.xy + float2(1.0, 0.0) + ip.z * 31.0);
  float n010 = hash21(ip.xy + float2(0.0, 1.0) + ip.z * 31.0);
  float n110 = hash21(ip.xy + float2(1.0, 1.0) + ip.z * 31.0);
  float n001 = hash21(ip.xy + (ip.z + 1.0) * 31.0);
  float n101 = hash21(ip.xy + float2(1.0, 0.0) + (ip.z + 1.0) * 31.0);
  float n011 = hash21(ip.xy + float2(0.0, 1.0) + (ip.z + 1.0) * 31.0);
  float n111 = hash21(ip.xy + float2(1.0, 1.0) + (ip.z + 1.0) * 31.0);
  float3 w = fp * fp * fp * (fp * (fp * 6.0 - 15.0) + 10.0);
  float x00 = mix(n000, n100, w.x);
  float x10 = mix(n010, n110, w.x);
  float x01 = mix(n001, n101, w.x);
  float x11 = mix(n011, n111, w.x);
  float y0 = mix(x00, x10, w.y);
  float y1 = mix(x01, x11, w.y);
  return mix(y0, y1, w.z) * 2.0 - 1.0;
}

float fbm(float3 p) {
  float sum = 0.0;
  float amp = 0.55;
  for (int i = 0; i < 2; i++) {
    sum += amp * vnoise(p);
    p *= 2.1;
    amp *= 0.55;
  }
  return sum;
}

// round dot
float sdDot(float2 p, float r) { return length(p) - r; }
// square with a rounded feel
float sdSquare(float2 p, float r) { return max(abs(p.x), abs(p.y)) - r; }
// equilateral triangle (iq)
float sdTri(float2 p, float r) {
  float k = 1.7320508;
  p.x = abs(p.x) - r;
  p.y = p.y + r / k;
  if (p.x + k * p.y > 0.0) p = float2(p.x - k * p.y, -k * p.x - p.y) * 0.5;
  p.x -= clamp(p.x, -2.0 * r, 0.0);
  return -length(p) * sign(p.y);
}

half4 main(float2 fragCoord) {
  float cellPx = uResX / CELLS;
  float cols = ceil(uResX / cellPx);
  float2 cell = floor(fragCoord / cellPx);
  float2 center = (cell + 0.5) * cellPx;
  float2 local = (fragCoord - center) / (cellPx * 0.5);

  float n = fbm(float3(center / (cellPx * 9.0) + float2(SEED_X, SEED_Y), uTime * 0.12));
  float tone = clamp(n * 0.95 + 0.5, 0.0, 1.0);
  float band = floor(min(tone, 0.9999) * 3.0);

  // Shapes step between dot, triangle and square as the tone crosses a band,
  // which is what makes the field look carved rather than merely shaded.
  float shape = mod(band, 3.0);
  float size = 0.40 + 0.16 * band + 0.10 * fract(tone * 3.0);

  float d = shape < 0.5 ? sdDot(local, size * 0.9)
          : shape < 1.5 ? sdTri(float2(local.x, local.y + size * 0.62), size * 0.86)
                        : sdSquare(local, size * 0.82);

  float aa = 2.0 / (cellPx * 0.5);
  float coverage = smoothstep(aa, -aa, d);

  float3 tint = mix(uDeep.rgb, uPrimary.rgb, smoothstep(0.15, 0.7, tone));
  tint = mix(tint, uCrest.rgb, smoothstep(0.72, 1.0, tone) * 0.85);
  tint = mix(tint, uSecondary.rgb, 0.12);

  // A faint bloom around each shape keeps the grid from looking like a chart.
  float halo = exp(-max(d, 0.0) * 2.2) * 0.16;
  float3 col = uBg.rgb + tint * (coverage * 0.92 + halo) * uTweak.w;
  return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
