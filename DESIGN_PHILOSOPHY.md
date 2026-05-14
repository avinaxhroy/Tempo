# Tempo: Central Design Philosophy

## 1. Core Aesthetic: "Ethereal Dark"
Tempo’s design language is built around a premium, immersive dark mode experience. We avoid harsh, pure blacks in favor of deep, rich space-grays, combined with vibrant, glowing neon accents. The app should feel modern, lightweight, and slightly "glassy."

**Key Principles:**
- **Dark, Not Black:** Backgrounds are deep grays/blues (`#0a0a0f`) to reduce eye strain and provide depth.
- **Neon Glows:** Actions and active states are highlighted with glowing purple accents (`#7c5cff`) to create a tech-forward, premium feel.
- **Tactile Depth:** We use subtle 1px borders (`#2a2a35`) and soft shadows, rather than flat planes, to separate cards from the background.
- **Consistency:** Both the Android app and the Browser Extension MUST use these exact same tokens and paradigms.

---

## 2. Color Palette
Use these exact hex codes across both platforms to ensure a unified experience. 

### Backgrounds (Surfaces)
- **Primary Background:** `#0A0A0F` (App background, bottom layer)
- **Secondary Background:** `#111118` (Nav bars, sidebars)
- **Card/Surface Background:** `#16161F` (Main content cards)
- **Hover/Active Surface:** `#1E1E2A` (Buttons, hovered list items)
- **Input Background:** `#1A1A25` (Text fields)

### Typography
- **Primary Text:** `#F0F0F5` (Headings, primary body text)
- **Secondary Text:** `#A0A0B0` (Subtitles, secondary info)
- **Muted Text:** `#606070` (Placeholders, disabled text, tertiary info)

### Accents & Status
- **Primary Accent:** `#7C5CFF` (Primary buttons, active tabs, progress bars)
- **Accent Light:** `#9D84FF` (Hover states, highlights)
- **Accent Glow (Soft):** `rgba(124, 92, 255, 0.15)` (Active tab backgrounds, subtle highlights)
- **Accent Glow (Strong):** `rgba(124, 92, 255, 0.30)` (Glow shadows, intense highlights)
- **Success:** `#34D399` (Connected status, synced)
- **Danger:** `#F87171` (Errors, disconnects, destructive actions)
- **Warning:** `#FBBF24` (Pending states, warnings)

### Borders
- **Border Default:** `#2A2A35` (Dividers, card outlines)
- **Border Light:** `#3A3A48` (Focused inputs, active card outlines)

---

## 3. Typography
**Typeface:** `Inter` (Web) / `Roboto` or `System Default` (Android)
- Ensure `-webkit-font-smoothing: antialiased;` on web to keep text crisp.

**Hierarchy:**
- **Hero/Header:** 18px-24px, Font Weight: 700 (Bold)
- **Title:** 15px, Font Weight: 600 (Semi-bold)
- **Body:** 13px, Font Weight: 400 (Regular)
- **Caption/Small:** 11px, Font Weight: 500 (Medium)
- **Micro (Badges/Tags):** 10px, Font Weight: 600 (Semi-bold), Uppercase, Letter-spacing: 0.5px.

---

## 4. UI Components & Shapes

### Corner Radii
We use smooth, rounded corners consistently to soften the dark aesthetic.
- **Small (Badges, Inputs):** `6px`
- **Standard (Buttons, Menus):** `10px`
- **Large (Cards, Modals, Dialogs):** `14px`

### Shadows & Elevation
Instead of heavy drop shadows, we rely on 1px borders (`#2A2A35`) to define edges. Shadows are reserved for elevated elements.
- **Card Shadow:** `0 4px 20px rgba(0, 0, 0, 0.4)`
- **Accent Glow Shadow:** `0 0 12px rgba(124, 92, 255, 0.3)` (Used on primary buttons or active scanning/pairing elements)

### Buttons
- **Primary Button:** Gradient background (`linear-gradient(135deg, #7c5cff, #9d84ff)`), no border, glowing shadow.
- **Secondary/Ghost Button:** Transparent background, 1px border (`#2a2a35`), text color `#a0a0b0`. On hover, background shifts to `#1e1e2a`.
- **Danger Button:** Muted red background (`rgba(248, 113, 113, 0.1)`), 1px red border (`rgba(248, 113, 113, 0.3)`), red text (`#f87171`).

---

## 5. Interactions & Animation
- **Transitions:** All hover, focus, and state changes should use a consistent easing curve.
  - Recommended Timing: `0.2s cubic-bezier(0.4, 0, 0.2, 1)`
- **Feedback:** Every interaction (click, tap, hover) must have visual feedback. Buttons should scale down slightly on active click (`transform: translateY(1px)` or `scale(0.98)`).
- **Empty States:** Empty states should never be blank. Use a muted icon (opacity 0.6) with secondary text explaining what goes there.

---

## 6. Platform Implementation

### Browser Extension (Web)
- Use standard CSS variables (`:root`) as defined above.
- Flexbox/Grid for layout. Keep max-width constraints in popups.
- Scrollbars must be custom styled to match the dark theme (4px width, thumb: `#2a2a35`).

### Android App (Kotlin/Compose)
- Implement a custom `MaterialTheme` (or completely custom `CompositionLocal`) mapping these exact hex codes.
- Do not use default Material Purple (`#6200EE`) or default Android dark gray (`#121212`). Override them with our palette.
- Map our corner radii to `Shapes` in Compose (Small: 6.dp, Medium: 10.dp, Large: 14.dp).
- Use `Modifier.background(Brush.linearGradient(...))` for primary buttons and text gradients.
