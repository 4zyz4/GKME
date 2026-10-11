// GKME-Web — forced-landscape support.
//
// iOS Safari (including PWA standalone) does not implement the Screen
// Orientation API's lock(), so a gamepad UI cannot be locked to landscape by
// the browser. When such a browser is held upright we rotate the whole page 90°
// with a CSS transform on <body> (see `html.force-landscape body` in style.css)
// so the gamepad still renders in landscape.
//
// The transform is `rotate(90deg) translateY(-100%)` around the body's top-left
// corner. With the body sized to `width = innerHeight, height = innerWidth`, a
// body-local point (x, y) maps to viewport (client) coordinates as:
//     clientX = innerWidth - y
//     clientY = x
// The helpers below invert that mapping for pointer hit-testing.

const CLASS = "force-landscape";

export function isForceLandscape() {
  return document.documentElement.classList.contains(CLASS);
}

export function setForceLandscape(on) {
  document.documentElement.classList.toggle(CLASS, !!on);
}

/** Viewport (pointer) coordinates -> body-local coordinates. */
export function viewportToLocal(clientX, clientY) {
  if (isForceLandscape()) {
    return { x: clientY, y: window.innerWidth - clientX };
  }
  return { x: clientX, y: clientY };
}

/** Axis-aligned local rect of an element, correcting for the rotation. */
export function elementLocalRect(el) {
  const r = el.getBoundingClientRect();
  if (isForceLandscape()) {
    return {
      left: r.top,
      top: window.innerWidth - r.left - r.width,
      width: r.height,
      height: r.width,
    };
  }
  return { left: r.left, top: r.top, width: r.width, height: r.height };
}

/** Dimensions of the body-local coordinate space. */
export function localViewport() {
  if (isForceLandscape()) {
    return { w: window.innerHeight, h: window.innerWidth };
  }
  return { w: window.innerWidth, h: window.innerHeight };
}
