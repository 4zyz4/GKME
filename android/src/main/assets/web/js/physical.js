// GKME-Web — physical-controller mapping model.
//
// Mirrors the Android model in model/PhysicalControllerMapping.kt: the catalogue of
// remappable inputs, the per-input mapping (outputs + chord timing), the analog stick
// shaping/routing settings and the physical touchpad settings.
//
// Outputs are stored as integers: a non-negative value is a GKME gamepad/mouse bit
// (controls.js BIT), a negative value is a HID keyboard scan code (`-code`).

import { BIT, DPAD, Kb, KB_MODIFIER_CODES, MODIFIER_BITS } from "./controls.js";
import { t } from "./i18n.js";

export const PHYSICAL_KIND = { BUTTON: "BUTTON", JOYSTICK: "JOYSTICK", TOUCHPAD: "TOUCHPAD" };

/** Physical-only back (paddle) bits; must match Android's PhysicalInputs. */
export const PADDLE_BITS = {
  PADDLE_R1: 0x400000,
  PADDLE_L1: 0x800000,
  PADDLE_R2: 0x1000000,
  PADDLE_L2: 0x2000000,
};

/**
 * Every remappable physical input.
 *
 * `gamepadButton` is the browser Gamepad API button index used to detect the press;
 * paddles are not exposed by the standard API, so they keep `gamepadButton: null`
 * and stay configurable for presets imported from Android.
 */
export const PHYSICAL_INPUTS = [
  { key: "A", label: "A", labelKey: "control.gamepadA", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.A], bitMask: BIT.A, gamepadButton: 0 },
  { key: "B", label: "B", labelKey: "control.gamepadB", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.B], bitMask: BIT.B, gamepadButton: 1 },
  { key: "X", label: "X", labelKey: "control.gamepadX", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.X], bitMask: BIT.X, gamepadButton: 2 },
  { key: "Y", label: "Y", labelKey: "control.gamepadY", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.Y], bitMask: BIT.Y, gamepadButton: 3 },
  { key: "LB", label: "LB", labelKey: "control.gamepadLB", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.LB], bitMask: BIT.LB, gamepadButton: 4 },
  { key: "RB", label: "RB", labelKey: "control.gamepadRB", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.RB], bitMask: BIT.RB, gamepadButton: 5 },
  { key: "LT", label: "LT", labelKey: "control.gamepadLT", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.LT], bitMask: BIT.LT, gamepadButton: 6, trigger: "left" },
  { key: "RT", label: "RT", labelKey: "control.gamepadRT", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.RT], bitMask: BIT.RT, gamepadButton: 7, trigger: "right" },
  { key: "SELECT", labelKey: "global.inputSelect", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.SELECT], bitMask: BIT.SELECT, gamepadButton: 8 },
  { key: "START", labelKey: "global.inputMenu", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.START], bitMask: BIT.START, gamepadButton: 9 },
  { key: "L3", labelKey: "global.inputL3", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.L3], bitMask: BIT.L3, gamepadButton: 10 },
  { key: "R3", labelKey: "global.inputR3", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.R3], bitMask: BIT.R3, gamepadButton: 11 },
  { key: "DPAD_UP", labelKey: "global.inputDpadUp", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.DPAD_BIT_UP], bitMask: BIT.DPAD_BIT_UP, gamepadButton: 12 },
  { key: "DPAD_DOWN", labelKey: "global.inputDpadDown", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.DPAD_BIT_DOWN], bitMask: BIT.DPAD_BIT_DOWN, gamepadButton: 13 },
  { key: "DPAD_LEFT", labelKey: "global.inputDpadLeft", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.DPAD_BIT_LEFT], bitMask: BIT.DPAD_BIT_LEFT, gamepadButton: 14 },
  { key: "DPAD_RIGHT", labelKey: "global.inputDpadRight", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.DPAD_BIT_RIGHT], bitMask: BIT.DPAD_BIT_RIGHT, gamepadButton: 15 },
  { key: "HOME", labelKey: "global.inputHome", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.HOME], bitMask: BIT.HOME, gamepadButton: 16 },
  { key: "SHARE", labelKey: "global.inputShare", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.TOUCHPAD_CLICK], bitMask: BIT.TOUCHPAD_CLICK, gamepadButton: 17 },
  { key: "MIC_MUTE", labelKey: "global.inputMic", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [BIT.MIC_MUTE], bitMask: BIT.MIC_MUTE, gamepadButton: null },
  { key: "PADDLE_R1", labelKey: "global.paddleR1", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [], bitMask: PADDLE_BITS.PADDLE_R1, gamepadButton: null, paddle: true },
  { key: "PADDLE_L1", labelKey: "global.paddleL1", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [], bitMask: PADDLE_BITS.PADDLE_L1, gamepadButton: null, paddle: true },
  { key: "PADDLE_R2", labelKey: "global.paddleR2", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [], bitMask: PADDLE_BITS.PADDLE_R2, gamepadButton: null, paddle: true },
  { key: "PADDLE_L2", labelKey: "global.paddleL2", kind: PHYSICAL_KIND.BUTTON, defaultOutputs: [], bitMask: PADDLE_BITS.PADDLE_L2, gamepadButton: null, paddle: true },
  { key: "LEFT_JOYSTICK", labelKey: "global.leftStick", kind: PHYSICAL_KIND.JOYSTICK },
  { key: "RIGHT_JOYSTICK", labelKey: "global.rightStick", kind: PHYSICAL_KIND.JOYSTICK },
  { key: "TOUCHPAD", labelKey: "global.touchpad", kind: PHYSICAL_KIND.TOUCHPAD },
];

const INPUT_BY_KEY = new Map(PHYSICAL_INPUTS.map((input) => [input.key, input]));

/** The same grouping/order as MainActivityPhysicalMapping.PHYSICAL_MAPPING_GROUPS. */
export const PHYSICAL_MAPPING_GROUPS = [
  { key: "face", labelKey: "global.groupFace", inputKeys: ["A", "B", "X", "Y"] },
  { key: "shoulder", labelKey: "global.groupShoulder", inputKeys: ["LB", "RB", "LT", "RT"] },
  { key: "stick_dpad", labelKey: "global.groupStickDpad", inputKeys: ["L3", "R3", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"] },
  { key: "function", labelKey: "global.groupFunction", inputKeys: ["SELECT", "START", "HOME", "SHARE", "MIC_MUTE"] },
  { key: "paddles", labelKey: "global.groupPaddles", inputKeys: ["PADDLE_R1", "PADDLE_L1", "PADDLE_R2", "PADDLE_L2"] },
  { key: "analog", labelKey: "global.groupAnalog", inputKeys: ["LEFT_JOYSTICK", "RIGHT_JOYSTICK", "TOUCHPAD"] },
  { key: "other", labelKey: "global.groupOther", inputKeys: [] },
];

export function physicalInput(key) {
  return INPUT_BY_KEY.get(key) || null;
}

export function physicalInputLabel(input) {
  return input.label || t(input.labelKey);
}

/** Button inputs only (the ones that expose a chip list). */
export const PHYSICAL_BUTTONS = PHYSICAL_INPUTS.filter((input) => input.kind === PHYSICAL_KIND.BUTTON);
export const PHYSICAL_ANALOG = PHYSICAL_INPUTS.filter((input) => input.kind !== PHYSICAL_KIND.BUTTON);

// ── Per-input mapping ────────────────────────────────────

export const DEFAULT_COMBO_TIMING = "SIMULTANEOUS";
export const DEFAULT_COMBO_INTERVAL_MS = 16;

/** A mapping object with every field filled in. */
export function normalizeMapping(raw) {
  return {
    outputs: Array.isArray(raw && raw.outputs) ? raw.outputs.slice() : [],
    gyroActivate: !!(raw && raw.gyroActivate),
    comboTiming: (raw && raw.comboTiming) || DEFAULT_COMBO_TIMING,
    comboIntervalMs: raw && raw.comboIntervalMs != null ? raw.comboIntervalMs : DEFAULT_COMBO_INTERVAL_MS,
  };
}

/** Current outputs for `key` (default outputs when nothing is stored). */
export function mappingOutputs(mappings, key) {
  const stored = mappings && mappings[key];
  if (stored && Array.isArray(stored.outputs)) return stored.outputs.slice();
  const input = physicalInput(key);
  return input && input.defaultOutputs ? input.defaultOutputs.slice() : [];
}

export function mappingTiming(mappings, key) {
  const stored = mappings && mappings[key];
  const mapping = normalizeMapping(stored);
  // Absent mappings still expose their default outputs, like Android's
  // `mapping?.outputs ?: input.defaultOutputs`.
  mapping.outputs = mappingOutputs(mappings, key);
  return mapping;
}

/** True when the mapping can be dropped from the preset (matches Android's writePhysicalMapping). */
export function isDefaultMapping(key, mapping) {
  const input = physicalInput(key);
  const defaults = input && input.defaultOutputs ? input.defaultOutputs : [];
  const m = normalizeMapping(mapping);
  return (
    m.outputs.length === defaults.length &&
    m.outputs.every((v, i) => v === defaults[i]) &&
    !m.gyroActivate &&
    m.comboTiming === DEFAULT_COMBO_TIMING &&
    m.comboIntervalMs === DEFAULT_COMBO_INTERVAL_MS
  );
}

// ── Physical stick settings ──────────────────────────────

export const STICK_MODE = {
  FOUR_WAY: "FOUR_WAY",
  EIGHT_WAY: "EIGHT_WAY",
  LEFT_STICK: "LEFT_STICK",
  RIGHT_STICK: "RIGHT_STICK",
  MOUSE: "MOUSE",
};

export const STICK_MODES = [
  STICK_MODE.FOUR_WAY,
  STICK_MODE.EIGHT_WAY,
  STICK_MODE.LEFT_STICK,
  STICK_MODE.RIGHT_STICK,
  STICK_MODE.MOUSE,
];

const STICK_MODE_KEYS = {
  [STICK_MODE.FOUR_WAY]: "global.fourWay",
  [STICK_MODE.EIGHT_WAY]: "global.eightWay",
  [STICK_MODE.LEFT_STICK]: "global.leftStick",
  [STICK_MODE.RIGHT_STICK]: "global.rightStick",
  [STICK_MODE.MOUSE]: "global.mouse",
};

export function stickModeLabel(mode) {
  return mode ? t(STICK_MODE_KEYS[mode] || "global.nativeMapping") : t("global.nativeMapping");
}

export const STICK_DIRECTION = {
  UP: "UP",
  DOWN: "DOWN",
  LEFT: "LEFT",
  RIGHT: "RIGHT",
  UP_LEFT: "UP_LEFT",
  UP_RIGHT: "UP_RIGHT",
  DOWN_LEFT: "DOWN_LEFT",
  DOWN_RIGHT: "DOWN_RIGHT",
};

/** Stored index = ordinal; mirrors PhysicalStickDirection.entries. */
export const STICK_DIRECTIONS = [
  STICK_DIRECTION.UP,
  STICK_DIRECTION.DOWN,
  STICK_DIRECTION.LEFT,
  STICK_DIRECTION.RIGHT,
  STICK_DIRECTION.UP_LEFT,
  STICK_DIRECTION.UP_RIGHT,
  STICK_DIRECTION.DOWN_LEFT,
  STICK_DIRECTION.DOWN_RIGHT,
];

const STICK_DIRECTION_KEYS = {
  [STICK_DIRECTION.UP]: "editor.up",
  [STICK_DIRECTION.DOWN]: "editor.down",
  [STICK_DIRECTION.LEFT]: "editor.left",
  [STICK_DIRECTION.RIGHT]: "editor.right",
  [STICK_DIRECTION.UP_LEFT]: "editor.upLeft",
  [STICK_DIRECTION.UP_RIGHT]: "editor.upRight",
  [STICK_DIRECTION.DOWN_LEFT]: "editor.downLeft",
  [STICK_DIRECTION.DOWN_RIGHT]: "editor.downRight",
};

export function stickDirectionLabel(direction) {
  return t(STICK_DIRECTION_KEYS[direction] || "global.groupOther");
}

/** Directions editable for a mode (mirrors PhysicalStickDirection.forMode). */
export function directionsForMode(mode) {
  if (mode === STICK_MODE.FOUR_WAY) {
    return [STICK_DIRECTION.UP, STICK_DIRECTION.DOWN, STICK_DIRECTION.LEFT, STICK_DIRECTION.RIGHT];
  }
  if (mode === STICK_MODE.EIGHT_WAY) return STICK_DIRECTIONS.slice();
  return [];
}

export const DEFAULT_MOUSE_SENSITIVITY = 100;
export const MIN_MOUSE_SENSITIVITY = 10;
export const MAX_MOUSE_SENSITIVITY = 300;

export function normalizeDirectionSettings(raw) {
  return {
    outputs: Array.isArray(raw && raw.outputs) ? raw.outputs.slice() : [],
    comboTiming: (raw && raw.comboTiming) || DEFAULT_COMBO_TIMING,
    comboIntervalMs: raw && raw.comboIntervalMs != null ? raw.comboIntervalMs : DEFAULT_COMBO_INTERVAL_MS,
  };
}

export function isDefaultDirectionSettings(raw) {
  const d = normalizeDirectionSettings(raw);
  return d.outputs.length === 0 && d.comboTiming === DEFAULT_COMBO_TIMING && d.comboIntervalMs === DEFAULT_COMBO_INTERVAL_MS;
}

export function normalizeStickSettings(raw) {
  const src = raw || {};
  return {
    deadZone: Number(src.deadZone) || 0,
    reverseDeadZone: Number(src.reverseDeadZone) || 0,
    // The Web build has no curve editor; the field is kept for preset round-trips.
    curve: Array.isArray(src.curve) && src.curve.length ? src.curve.slice() : null,
    mode: STICK_MODES.includes(src.mode) ? src.mode : null,
    mouseSensitivity: src.mouseSensitivity != null ? Number(src.mouseSensitivity) : DEFAULT_MOUSE_SENSITIVITY,
    directions: Array.isArray(src.directions) ? src.directions.map(normalizeDirectionSettings) : null,
  };
}

export function isDefaultStickSettings(raw) {
  if (!raw) return true;
  const s = normalizeStickSettings(raw);
  return (
    s.deadZone <= 0 &&
    s.reverseDeadZone <= 0 &&
    !s.curve &&
    s.mode == null &&
    s.mouseSensitivity === DEFAULT_MOUSE_SENSITIVITY &&
    (!s.directions || s.directions.length === 0)
  );
}

/** `null` when every field is neutral, otherwise a normalized copy. */
export function stripStickSettings(raw) {
  if (isDefaultStickSettings(raw)) return null;
  return normalizeStickSettings(raw);
}

export function directionAt(settings, direction) {
  const index = STICK_DIRECTIONS.indexOf(direction);
  const list = settings && settings.directions;
  return normalizeDirectionSettings(list ? list[index] : null);
}

/** Writes one direction and trims trailing defaults (mirrors withDirection). */
export function withDirection(settings, direction, value) {
  const base = normalizeStickSettings(settings);
  const list = STICK_DIRECTIONS.map((d) => {
    if (d === direction) return normalizeDirectionSettings(value);
    return normalizeDirectionSettings(base.directions ? base.directions[STICK_DIRECTIONS.indexOf(d)] : null);
  });
  while (list.length && isDefaultDirectionSettings(list[list.length - 1])) list.pop();
  return { ...base, directions: list.length ? list : null };
}

// ── Physical touchpad settings ──────────────────────────

export const TOUCHPAD_MODE = { NATIVE: "NATIVE", MOUSE: "MOUSE" };

export function normalizeTouchpadSettings(raw) {
  const src = raw || {};
  return {
    mode: src.mode === TOUCHPAD_MODE.MOUSE ? TOUCHPAD_MODE.MOUSE : TOUCHPAD_MODE.NATIVE,
    mouseSensitivity: src.mouseSensitivity != null ? Number(src.mouseSensitivity) : 100,
    scrollSensitivity: src.scrollSensitivity != null ? Number(src.scrollSensitivity) : 100,
    invertScrollV: !!src.invertScrollV,
    invertScrollH: !!src.invertScrollH,
    singleTapAction: src.singleTapAction || "LEFT_CLICK",
    twoFingerTapAction: src.twoFingerTapAction || "RIGHT_CLICK",
    doubleTapDragAction: src.doubleTapDragAction || "LEFT_DRAG",
    oneFingerSwipeAction: src.oneFingerSwipeAction || "MOVE_CURSOR",
    twoFingerSwipeAction: src.twoFingerSwipeAction || "SCROLL",
  };
}

export function isDefaultTouchpadSettings(raw) {
  if (!raw) return true;
  const s = normalizeTouchpadSettings(raw);
  return (
    s.mode === TOUCHPAD_MODE.NATIVE &&
    s.mouseSensitivity === 100 &&
    s.scrollSensitivity === 100 &&
    !s.invertScrollV &&
    !s.invertScrollH &&
    s.singleTapAction === "LEFT_CLICK" &&
    s.twoFingerTapAction === "RIGHT_CLICK" &&
    s.doubleTapDragAction === "LEFT_DRAG" &&
    s.oneFingerSwipeAction === "MOVE_CURSOR" &&
    s.twoFingerSwipeAction === "SCROLL"
  );
}

export function stripTouchpadSettings(raw) {
  return isDefaultTouchpadSettings(raw) ? null : normalizeTouchpadSettings(raw);
}

// ── Analog shaping / routing (input/StickShaping.kt, PhysicalStickRouting.kt) ──

export const STICK_FULL_SCALE = 32767;

/** Radial dead zone → anti dead zone → (curve skipped) shaping. */
export function shapeStick(settings, x, y) {
  if (!settings) return [x, y];
  const deadZone = Number(settings.deadZone) || 0;
  const reverseDeadZone = Number(settings.reverseDeadZone) || 0;
  const hasCurve = Array.isArray(settings.curve) && settings.curve.length >= 2;
  if (deadZone <= 0 && reverseDeadZone <= 0 && !hasCurve) {
    const mag = Math.hypot(x, y);
    if (mag <= STICK_FULL_SCALE) return [x, y];
    const scale = STICK_FULL_SCALE / mag;
    return [x * scale, y * scale];
  }
  const mag = Math.hypot(x, y);
  if (mag < 1e-6) return [0, 0];
  const normalized = Math.min(1, mag / STICK_FULL_SCALE);
  const dz = Math.min(0.99, Math.max(0, deadZone / 100));
  const rdz = Math.min(0.99, Math.max(0, reverseDeadZone / 100));
  const afterDeadZone = normalized <= dz ? 0 : (normalized - dz) / (1 - dz);
  let shaped = afterDeadZone === 0 ? 0 : afterDeadZone * (1 - rdz) + rdz;
  if (hasCurve) shaped = evalCurve(settings.curve, shaped);
  const scale = (shaped * STICK_FULL_SCALE) / mag;
  return [Math.trunc(x * scale), Math.trunc(y * scale)];
}

/** Piecewise curve evaluation (mirrors SensitivityCurve.evaluate). */
function evalCurve(points, value) {
  const knots = buildKnots(points);
  if (!knots) return Math.min(1, Math.max(0, value));
  const n = knots.length;
  const x = Math.min(1, Math.max(0, value));
  if (x <= knots[0][0]) return knots[0][1];
  if (x >= knots[n - 1][0]) return knots[n - 1][1];

  const h = new Array(n - 1);
  const d = new Array(n - 1);
  for (let i = 0; i < n - 1; i++) {
    h[i] = knots[i + 1][0] - knots[i][0];
    d[i] = h[i] > 1e-9 ? (knots[i + 1][1] - knots[i][1]) / h[i] : 0;
  }

  // Fritsch–Carlson tangents (monotone cubic Hermite).
  const m = new Array(n);
  m[0] = d[0];
  m[n - 1] = d[n - 2];
  for (let i = 1; i < n - 1; i++) {
    const d0 = d[i - 1];
    const d1 = d[i];
    if (d0 * d1 <= 0) {
      m[i] = 0;
    } else {
      const w1 = 2 * h[i] + h[i - 1];
      const w2 = h[i] + 2 * h[i - 1];
      m[i] = (w1 + w2) / (w1 / d0 + w2 / d1);
    }
  }
  for (let i = 0; i < n - 1; i++) {
    const di = d[i];
    if (di === 0) {
      m[i] = 0;
      m[i + 1] = 0;
      continue;
    }
    let a = m[i] / di;
    let b = m[i + 1] / di;
    if (a < 0) { m[i] = 0; a = 0; }
    if (b < 0) { m[i + 1] = 0; b = 0; }
    const s = a * a + b * b;
    if (s > 9) {
      const tau = 3 / Math.sqrt(s);
      m[i] = tau * a * di;
      m[i + 1] = tau * b * di;
    }
  }

  let seg = 0;
  for (let i = 0; i < n - 1; i++) {
    if (x >= knots[i][0] && x <= knots[i + 1][0]) {
      seg = i;
      break;
    }
  }
  const x0 = knots[seg][0];
  const y0 = knots[seg][1];
  const y1 = knots[seg + 1][1];
  const hh = h[seg];
  const tt = hh > 1e-9 ? (x - x0) / hh : 0;
  const t2 = tt * tt;
  const t3 = t2 * tt;
  return (
    (2 * t3 - 3 * t2 + 1) * y0 +
    (t3 - 2 * t2 + tt) * hh * m[seg] +
    (-2 * t3 + 3 * t2) * y1 +
    (t3 - t2) * hh * m[seg + 1]
  );
}

function buildKnots(curve) {
  if (!Array.isArray(curve) || curve.length < 2) return null;
  const raw = [];
  for (let i = 0; i + 1 < curve.length; i += 2) {
    const x = Number(curve[i]);
    const y = Number(curve[i + 1]);
    if (Number.isFinite(x) && Number.isFinite(y)) {
      raw.push([Math.min(1, Math.max(0, x)), Math.min(1, Math.max(0, y))]);
    }
  }
  if (!raw.length) return null;
  const knots = [[0, 0]];
  raw.sort((a, b) => a[0] - b[0]);
  for (const point of raw) {
    if (point[0] <= 1e-6 || point[0] >= 1 - 1e-6) continue;
    const last = knots[knots.length - 1];
    if (point[0] - last[0] <= 1e-6) knots[knots.length - 1] = point;
    else knots.push(point);
  }
  knots.push([1, 1]);
  return knots;
}

const MOUSE_VELOCITY_LIMIT = 10;

/** Routes the shaped stick vectors to the output axes or the mouse. */
export function routeSticks(leftSettings, lx, ly, rightSettings, rx, ry) {
  let leftX = 0;
  let leftY = 0;
  let rightX = 0;
  let rightY = 0;
  let mouseX = 0;
  let mouseY = 0;

  const targetOf = (mode, isLeft) => {
    if (mode === STICK_MODE.LEFT_STICK) return "LEFT";
    if (mode === STICK_MODE.RIGHT_STICK) return "RIGHT";
    if (mode === STICK_MODE.MOUSE) return "MOUSE";
    if (mode === STICK_MODE.FOUR_WAY || mode === STICK_MODE.EIGHT_WAY) return "NONE";
    return isLeft ? "LEFT" : "RIGHT";
  };
  const add = (target, x, y, sensitivity) => {
    if (target === "LEFT") { leftX += x; leftY += y; }
    else if (target === "RIGHT") { rightX += x; rightY += y; }
    else if (target === "MOUSE") {
      mouseX += (x / STICK_FULL_SCALE) * sensitivity;
      mouseY += (y / STICK_FULL_SCALE) * sensitivity;
    }
  };
  const clampAxis = (v) => Math.max(-32768, Math.min(32767, Math.round(v)));
  const sensOf = (s) => {
    const v = s ? Number(s.mouseSensitivity) : DEFAULT_MOUSE_SENSITIVITY;
    const clamped = Math.min(MAX_MOUSE_SENSITIVITY, Math.max(MIN_MOUSE_SENSITIVITY, Number.isFinite(v) ? v : DEFAULT_MOUSE_SENSITIVITY));
    return clamped / 100;
  };
  add(targetOf(leftSettings && leftSettings.mode, true), lx, ly, sensOf(leftSettings));
  add(targetOf(rightSettings && rightSettings.mode, false), rx, ry, sensOf(rightSettings));
  return {
    leftX: clampAxis(leftX),
    leftY: clampAxis(leftY),
    rightX: clampAxis(rightX),
    rightY: clampAxis(rightY),
    mouseX: Math.max(-MOUSE_VELOCITY_LIMIT, Math.min(MOUSE_VELOCITY_LIMIT, mouseX)),
    mouseY: Math.max(-MOUSE_VELOCITY_LIMIT, Math.min(MOUSE_VELOCITY_LIMIT, mouseY)),
  };
}

export const DIRECTION_THRESHOLD = 0.5;

/** 4-way: the dominant axis wins; returns null inside the centre. */
export function fourWayDirection(x, y) {
  const ax = Math.abs(x / STICK_FULL_SCALE);
  const ay = Math.abs(y / STICK_FULL_SCALE);
  if (ax >= ay) {
    if (ax < DIRECTION_THRESHOLD) return null;
    return x < 0 ? STICK_DIRECTION.LEFT : STICK_DIRECTION.RIGHT;
  }
  if (ay < DIRECTION_THRESHOLD) return null;
  return y < 0 ? STICK_DIRECTION.UP : STICK_DIRECTION.DOWN;
}

/** 8-way: each axis crosses the threshold independently, so diagonals work. */
export function eightWayDirection(x, y) {
  const ax = Math.abs(x / STICK_FULL_SCALE);
  const ay = Math.abs(y / STICK_FULL_SCALE);
  const horizontal = ax >= DIRECTION_THRESHOLD ? (x < 0 ? -1 : 1) : 0;
  const vertical = ay >= DIRECTION_THRESHOLD ? (y < 0 ? -1 : 1) : 0;
  if (vertical < 0 && horizontal < 0) return STICK_DIRECTION.UP_LEFT;
  if (vertical < 0 && horizontal > 0) return STICK_DIRECTION.UP_RIGHT;
  if (vertical > 0 && horizontal < 0) return STICK_DIRECTION.DOWN_LEFT;
  if (vertical > 0 && horizontal > 0) return STICK_DIRECTION.DOWN_RIGHT;
  if (vertical < 0) return STICK_DIRECTION.UP;
  if (vertical > 0) return STICK_DIRECTION.DOWN;
  if (horizontal < 0) return STICK_DIRECTION.LEFT;
  if (horizontal > 0) return STICK_DIRECTION.RIGHT;
  return null;
}

export function directionOf(mode, x, y) {
  if (mode === STICK_MODE.FOUR_WAY) return fourWayDirection(x, y);
  if (mode === STICK_MODE.EIGHT_WAY) return eightWayDirection(x, y);
  return null;
}

// ── Output helpers ───────────────────────────────────────

/** Splits outputs into gamepad/mouse bits, keyboard scan codes and modifier bits. */
export function splitOutputs(outputs) {
  let bits = 0;
  let modifiers = 0;
  const keys = new Set();
  for (const raw of outputs || []) {
    const v = Number(raw) | 0;
    if (v >= 0) {
      bits = (bits | v) >>> 0;
    } else {
      const code = -v;
      if (KB_MODIFIER_CODES.has(code)) modifiers |= modifierBitFor(code);
      else keys.add(code);
    }
  }
  return { bits: bits >>> 0, modifiers: modifiers >>> 0, keys };
}

export function modifierBitFor(code) {
  switch (code) {
    case Kb.LCtrl: return MODIFIER_BITS.LCtrl;
    case Kb.LShift: return MODIFIER_BITS.LShift;
    case Kb.LAlt: return MODIFIER_BITS.LAlt;
    case Kb.LGui: return MODIFIER_BITS.LGui;
    case Kb.RCtrl: return MODIFIER_BITS.RCtrl;
    case Kb.RShift: return MODIFIER_BITS.RShift;
    case Kb.RAlt: return MODIFIER_BITS.RAlt;
    case Kb.RGui: return MODIFIER_BITS.RGui;
    default: return 0;
  }
}

/** DPAD bits carried by a gamepad bitmask (the wire format has a separate field). */
export function dpadFromBits(bits) {
  let hat = 0;
  if (bits & BIT.DPAD_BIT_UP) hat |= DPAD.UP;
  if (bits & BIT.DPAD_BIT_DOWN) hat |= DPAD.DOWN;
  if (bits & BIT.DPAD_BIT_LEFT) hat |= DPAD.LEFT;
  if (bits & BIT.DPAD_BIT_RIGHT) hat |= DPAD.RIGHT;
  return hat >>> 0;
}
