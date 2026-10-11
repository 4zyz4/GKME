// GKME-Web — Android vector-icon port.
//
// Each entry is transposed 1:1 from android/src/main/res/drawable/<name>.xml:
//   w/h       = viewportWidth / viewportHeight
//   iw/ih     = declared dp size (intrinsic width/height, drives aspect)
//   paths     = one or more path elements, drawn in order
//                 s:"fill"   → filled with the tint colour (evenodd when fillRule:"evenOdd")
//                 s:"stroke" → stroked with sw (viewport units, same as android:strokeWidth)
//
// Android tints every gamepad icon to its configured icon colour, so drawIcon()
// always overrides android:fillColor / android:strokeColor with the caller colour.

export const ICONS = {
  settings: {
    w: 24, h: 24, iw: 24, ih: 24,
    paths: [{ d: "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35c-0.59,0.24-1.13,0.57-1.62,0.94L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87c-0.12,0.21-0.08,0.47,0.12,0.61l2.03,1.58c-0.05,0.3-0.07,0.62-0.07,0.94s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94zM12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z", s: "fill" }],
  },
  home: {
    w: 24, h: 24, iw: 24, ih: 24,
    paths: [{ d: "M10,20v-6h4v6h5v-8h3L12,3L2,12h3v8z", s: "fill" }],
  },
  home_xbox: {
    w: 24, h: 24, iw: 24, ih: 24,
    paths: [{ d: "M5.418 19.527A9.956 9.956 0 0 0 12 22a9.967 9.967 0 0 0 6.585-2.473c1.564-1.593-3.597-7.257-6.585-9.514-2.985 2.257-8.15 7.921-6.582 9.514zm9.3-12.005c2.084 2.468 6.237 8.595 5.064 10.76A9.952 9.952 0 0 0 22 12.003a9.958 9.958 0 0 0-2.975-7.113s-.022-.018-.068-.035a.686.686 0 0 0-.235-.038c-.493 0-1.654.362-4.004 2.705zM5.045 4.856c-.048.017-.068.034-.072.035A9.963 9.963 0 0 0 2 12.003c0 2.379.832 4.561 2.218 6.278C3.05 16.11 7.2 9.988 9.284 7.523 6.934 5.178 5.771 4.818 5.28 4.818a.604.604 0 0 0-.234.039v-.002zM12 4.959S9.546 3.523 7.63 3.455c-.753-.027-1.212.246-1.268.282C8.149 2.538 10.049 2 11.987 2H12c1.945 0 3.838.538 5.638 1.737-.056-.038-.512-.31-1.266-.282-1.917.068-4.372 1.5-4.372 1.5v.004z", s: "fill" }],
  },
  home_playstation: {
    w: 32, h: 32, iw: 24, ih: 24,
    paths: [{ d: "M3.262 24.248c-2.374-0.681-2.767-2.084-1.69-2.899 0.776-0.51 1.668-0.954 2.612-1.288l0.087-0.027 7.017-2.516v2.89l-5.030 1.839c-0.881 0.339-1.031 0.79-0.299 1.032 0.365 0.093 0.783 0.147 1.214 0.147 0.615 0 1.204-0.109 1.749-0.308l-0.035 0.011 2.422-0.882v2.592c-0.15 0.037-0.32 0.055-0.487 0.091-0.775 0.136-1.667 0.214-2.577 0.214-1.778 0-3.486-0.298-5.078-0.846l0.11 0.033zM18.049 24.544l7.868-2.843c0.893-0.322 1.032-0.781 0.307-1.022-0.363-0.089-0.779-0.14-1.208-0.14-0.622 0-1.22 0.108-1.774 0.305l0.037-0.011-5.255 1.874v-2.983l0.3-0.106c1.050-0.349 2.284-0.62 3.557-0.761l0.083-0.008c0.468-0.050 1.010-0.078 1.559-0.078 1.877 0 3.677 0.331 5.343 0.939l-0.108-0.035c2.309 0.751 2.549 1.839 1.969 2.589-0.559 0.557-1.235 0.998-1.988 1.282l-0.039 0.013-10.677 3.883v-2.869zM12.231 4.248v21.927l4.892 1.576v-18.39c0-0.862 0.38-1.438 0.992-1.238 0.795 0.225 0.95 1.017 0.95 1.881v7.342c3.050 1.491 5.451-0.003 5.451-3.939 0-4.045-1.407-5.842-5.546-7.282-1.785-0.648-4.04-1.294-6.347-1.805l-0.389-0.072z", s: "fill" }],
  },
  arrow_up: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M12,4L4,18h16z", s: "fill" }] },
  arrow_down: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M12,20L4,6h16z", s: "fill" }] },
  arrow_left: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M4,12L18,4v16z", s: "fill" }] },
  arrow_right: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M20,12L6,4v16z", s: "fill" }] },
  ps_cross: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M7,7 L17,17 M17,7 L7,17", s: "stroke", sw: 2 }] },
  ps_circle: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M12,5 A7,7 0 1,1 12,19 A7,7 0 1,1 12,5 Z", s: "stroke", sw: 2 }] },
  ps_square: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M7,7 L17,7 L17,17 L7,17 Z", s: "stroke", sw: 2 }] },
  ps_triangle: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M12,5 L19,18 L5,18 Z", s: "stroke", sw: 2 }] },
  ls: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M7,4 L17,4 L12,10 Z", s: "stroke", sw: 2 }] },
  rs: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M7,4 L17,4 L12,10 Z", s: "stroke", sw: 2 }] },
  view: { w: 20, h: 20, iw: 28, ih: 28, paths: [{ d: "M12,8V6H4v6h4m0,-3h8v6H8z", s: "stroke", sw: 2 }] },
  menu: { w: 24, h: 24, iw: 24, ih: 24, paths: [{ d: "M3,6h18v2H3V6zm0,5h18v2H3v-2zm0,5h18v2H3v-2z", s: "fill" }] },
  minus: { w: 14, h: 14, iw: 14, ih: 14, paths: [{ d: "M2,6h10v2H2z", s: "fill" }] },
  plus: { w: 14, h: 14, iw: 14, ih: 14, paths: [{ d: "M6,2v4H2v2h4v4h2V8h4V6H8V2H6z", s: "fill" }] },
  share_xbox: {
    w: 24, h: 24, iw: 24, ih: 24,
    paths: [
      { d: "M9,10 L3.5,10 L3.5,20.5 L20.5,20.5 L20.5,10 L15,10", s: "stroke", sw: 2 },
      { d: "M12,17.5 L12,4.5 M8.5,8 L12,4.5 L15.5,8", s: "stroke", sw: 2 },
    ],
  },
  screenshot_switch: {
    w: 24, h: 24, iw: 24, ih: 24,
    paths: [{ d: "M3,3 L21,3 L21,21 L3,21 Z M12,5 a7,7 0 1 0 0,14 a7,7 0 1 0 0,-14 Z", s: "fill", fillRule: "evenodd" }],
  },
  touchpad_grid: {
    w: 40, h: 30, iw: 30, ih: 23,
    paths: [
      { d: "M3,4 L37,4 A2,2 0 0,1 39,6 L39,26 A2,2 0 0,1 37,28 L3,28 A2,2 0 0,1 1,26 L1,6 A2,2 0 0,1 3,4 Z", s: "stroke", sw: 1.5 },
      {
        d: "M 4.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 8.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 12.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 16.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 20.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 24.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 28.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 32.5 12.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 4.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 8.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 12.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 16.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 20.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 24.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 28.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 32.5 17.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 4.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 8.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 12.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 16.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 20.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 24.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 28.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0 M 32.5 22.5 a 1.5 1.5 0 1 1 3 0 a 1.5 1.5 0 1 1 -3 0",
        s: "fill", alpha: 0.6,
      },
    ],
  },
  mic: {
    w: 24, h: 24, iw: 24, ih: 24,
    paths: [
      { d: "M12,15c1.66,0 3,-1.34 3,-3V6c0,-1.66 -1.34,-3 -3,-3S9,4.34 9,6v6c0,1.66 1.34,3 3,3z", s: "fill" },
      { d: "M17,12c0,2.76 -2.24,5 -5,5s-5,-2.24 -5,-5H5c0,3.53 2.61,6.43 6,6.92V22h2v-3.08c3.39,-0.49 6,-3.39 6,-6.92h-2z", s: "fill" },
    ],
  },
};

/**
 * Draw an icon centred at (cx, cy).
 *
 * Mirrors Android's CappedContentDrawable with fitAspect=true: `maxDim` is the
 * side of a square cap; a non-square icon is scaled so its diagonal matches that
 * square cap (so it looks the same size), bounded inside boxW × boxH.
 *
 * @param {CanvasRenderingContext2D} ctx
 * @param {string} name  key into ICONS
 * @param {number} cx    centre x (current transform space)
 * @param {number} cy    centre y
 * @param {number} maxDim square cap side (min(padded box, iconMaxSize))
 * @param {number} boxW  available content width (for the aspect-vs-box clamp)
 * @param {number} boxH  available content height
 * @param {string} color tint colour
 */
export function drawIcon(ctx, name, cx, cy, maxDim, boxW, boxH, color) {
  const ic = ICONS[name];
  if (!ic || maxDim <= 0) return;
  const iw = ic.iw;
  const ih = ic.ih;
  const targetScale = (maxDim * Math.SQRT2) / Math.sqrt(iw * iw + ih * ih);
  const scale = Math.min(targetScale, boxW / iw, boxH / ih);
  const dw = iw * scale;
  const dh = ih * scale;
  if (dw <= 0 || dh <= 0) return;
  const sx = dw / ic.w;
  const sy = dh / ic.h;

  ctx.save();
  ctx.translate(cx - dw / 2, cy - dh / 2);
  ctx.scale(sx, sy);
  ctx.lineJoin = "miter";
  for (const p of ic.paths) {
    const path = new Path2D(p.d);
    if (p.s === "stroke") {
      ctx.strokeStyle = color;
      ctx.lineWidth = p.sw || 2;
      ctx.stroke(path);
    } else {
      const prev = ctx.globalAlpha;
      if (p.alpha) ctx.globalAlpha = prev * p.alpha;
      ctx.fillStyle = color;
      ctx.fill(path, p.fillRule === "evenodd" ? "evenodd" : "nonzero");
      ctx.globalAlpha = prev;
    }
  }
  ctx.restore();
}
