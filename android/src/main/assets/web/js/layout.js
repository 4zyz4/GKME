// GKME-Web — the gamepad layout engine.
//
// Re-implements the essential behaviour of com.zyz4.gkme.view.GamepadLayout:
// a fixed 120-column grid, custom-drawn controls, grid-coordinate hit testing
// and a runtime touch dispatch. Rendering is canvas-based; input is handled
// with pointer events on the same canvas and manually hit-tested so multi-touch
// works exactly like the Android GestureDetector-free pipeline.

import {
  BIT, DPAD, Kb, CONTROLS, baseId, getControl, isKeypadId, lockAspectFor,
  controlLabel, keyboardCaption, KB_MODIFIER_CODES, MODIFIER_BITS,
} from "./controls.js";
import { drawIcon } from "./icons.js";
import { t, keypadDefaultTexts, localizeKeypadText } from "./i18n.js";
import { viewportToLocal } from "./orientation.js";

export const GRID_COLS = 120;

// sans-serif-medium (Android) ≈ weight 500 of the platform UI font.
const UI_FONT = '"Segoe UI", Roboto, system-ui, -apple-system, "Microsoft YaHei", sans-serif';

const DEFAULTS = {
  visible: true,
  lockAspect: false,
  swipeTrigger: false,
  rotation: 0,
  isCustom: false,
  customText: null,
  customBits: null,
  comboTiming: "SIMULTANEOUS",
  comboIntervalMs: 16,
  roundShape: false,
  doubleClickEnable: true,
  isKeypad: false,
  isKeyboard: false,
  keypadEightWay: false,
  centerShape: "SQUARE",
  centerSize: -1,
  joystickSensitivity: 100,
  touchpadMode: false,
  prediction: false,
  deadZone: 0,
  reverseDeadZone: 0,
  idleOpacity: 100,
  activeOpacity: 100,
  followAreaEnabled: false,
  followAreaX: 0,
  followAreaY: 0,
  followAreaW: 0,
  followAreaH: 0,
  followAreaOpacity: 100,
  followAreaOverlapTrigger: false,
  overlapTrigger: true,
  linearTriggerEnabled: false,
  slideDirection: "DOWN",
  travelDistance: 10,
  autoHold: false,
  gyroActivate: false,
  // Mousepad (mirrors ButtonPosition + MouseGestureAction defaults).
  mouseSensitivity: 1,
  mouseAcceleration: null,
  scrollSensitivity: 0.1,
  mouseMoveSlop: 4,
  invertScrollV: false,
  invertScrollH: false,
  singleTapAction: "LEFT_CLICK",
  twoFingerTapAction: "RIGHT_CLICK",
  threeFingerTapAction: "MIDDLE_CLICK",
  doubleTapDragAction: "LEFT_DRAG",
  oneFingerSwipeAction: "MOVE_CURSOR",
  twoFingerSwipeAction: "SCROLL",
  threeFingerSwipeAction: "SCROLL",
  sensitivityCurve: null,
  keypadTexts: null,
  keypadBits: null,
  keypadComboTimings: null,
  keypadComboIntervals: null,
};

/** On-screen grid size, accounting for the 90/270° non-locked aspect swap. */
export function onScreenGridSize(b) {
  const swapped = !b.lockAspect && (b.rotation === 90 || b.rotation === 270);
  return swapped ? [b.h, b.w] : [b.w, b.h];
}

export function normalizeButton(raw) {
  const b = Object.assign({}, DEFAULTS, raw);
  if (raw.width !== undefined && raw.w === undefined) b.w = raw.width;
  if (raw.height !== undefined && raw.h === undefined) b.h = raw.height;
  if (b.w === undefined) b.w = 10;
  if (b.h === undefined) b.h = 10;
  b.lockAspect = raw.lockAspect !== undefined ? raw.lockAspect : lockAspectFor(b.id);
  const c = getControl(b.id);
  if (c && c.isKeyboard) { b.isKeyboard = true; b.keyCode = c.keyCode; }
  if (c && c.isKeypad) b.isKeypad = true;
  if (c && c.isCustom) b.isCustom = true;
  if (c && c.isTrigger) b.isTrigger = true;
  if (c && c.isJoystick) { b.isJoystick = true; b.axis = c.axis; }
  if (c && c.isTouchpad) b.isTouchpad = true;
  if (c && c.isMousepad) b.isMousepad = true;
  if (c && c.isDpadPad) b.isDpadPad = true;
  if (c && c.isDpad) b.isDpad = true;
  if (c && c.isSettings) b.isSettings = true;
  if (b.isCustom && !b.customBits) b.customBits = [];
  if (b.isCustom && b.customText == null) b.customText = t("common.custom");
  return b;
}

// Appearance defaults, transposed 1:1 from AppSettings.kt (SOLID_COLOR fills).
// 描边宽度（*OutlineWidth）以「实际屏幕像素」为准，绘制时经 _outlineW 换算；
// 其余尺寸单位为 CSS px（页面以 viewport width=device-width 渲染，近似 dp）。
export const DEFAULT_APPEARANCE = {
  // ── 背景 ──
  bgFillType: 0, // 0 = SOLID_COLOR, 1 = IMAGE
  bgColor: "#000000",
  bgImage: null,
  // ── 按钮 ──
  btnFillType: 0,
  btnColor: "#1A1A1A",
  btnImage: null,
  btnOutlineColor: "#666666",
  btnOutlineWidth: 4,
  // ── 摇杆 ──
  joyBaseFillType: 0,
  joyBaseColor: "#222222",
  joyBaseImage: null,
  joyBaseOutlineColor: "#555555",
  joyBaseOutlineWidth: 4,
  joyCapFillType: 0,
  joyCapColor: "#555555",
  joyCapImage: null,
  joyCapOutlineColor: "#777777",
  joyCapOutlineWidth: 4,
  // ── 触发区描边 ──
  joyTriggerOutlineColor: "#666666",
  joyTriggerOutlineWidth: 4,
  tpTriggerOutlineColor: "#666666",
  tpTriggerOutlineWidth: 4,
  dpadPadTriggerOutlineColor: "#666666",
  dpadPadTriggerOutlineWidth: 4,
  linearTriggerBoxOutlineColor: "#888888",
  linearTriggerBoxOutlineWidth: 4,
  // ── 触摸板 ──
  tpFillType: 0,
  tpColor: "#121212",
  tpImage: null,
  tpOutlineColor: "#666666",
  tpOutlineWidth: 4,
  // ── 一体十字键 / 自定义按键盘 ──
  dpadPadFillType: 0,
  dpadPadColor: "#1A1A1A",
  dpadPadImage: null,
  dpadPadOutlineColor: "#666666",
  dpadPadOutlineWidth: 4,
  // ── 图标颜色 ──
  btnIconColor: "#CCCCCC",
  joyIconColor: "#CCCCCC",
  tpIconColor: "#666666",
  dpadPadIconColor: "#CCCCCC",
  // ── 内容最大尺寸（sp）──
  iconMaxSize: 24,
};

// Corer radius / rounding constants from AppearanceApplier.kt (× density).
export const CORNER_RADIUS_DP = 12; // buttons
export const TOUCHPAD_RADIUS_DP = 10; // touchpad
export const HANDLE_SIZE_DP = 8;
export const MARKER_DIST_DP = 4;

// Fallback extrapolation frame for 快速响应模式 (ButtonPosition.PREDICTION_FRAME_MS).
const PREDICTION_FRAME_MS = 16;

// ── Mousepad gesture timing (mirrors MainActivityControls.attachMousepadGestures) ──
const MOUSEPAD_DOUBLE_TAP_WINDOW_MS = 200;
const MOUSEPAD_HOLD_FOR_DOUBLE_MS = 200;
const MOUSEPAD_CLICK_HOLD_MS = 40;
const MOUSEPAD_TAP_THRESHOLD_MS = 200;
const MOUSEPAD_EMA_ALPHA = 0.4;
const MOUSEPAD_RESEED_GAP_MS = 50;

// ── 摇杆触摸板（速度）模式常量，镜像 JoystickView.kt ──
// 摇杆偏移 = 手指速度 × 该时间常数（再按最大行程归一化、乘灵敏度）。
// 100% 灵敏度下，手指以当前速度再移动 0.1s 的距离恰好对应满量程。
const VELOCITY_TIME_CONSTANT_MS = 100;
// 尚未识别到采样率时，归零看门狗使用的兜底采样间隔（ms，约一帧）。
const FALLBACK_SAMPLE_INTERVAL_MS = 16;
// 采样间隔识别的夹取下限（ms）。
const MIN_SAMPLE_INTERVAL_MS = 2;
// 手指停下后等待多少个采样间隔归零回中（至少 1 个间隔）。
const VELOCITY_RESET_INTERVALS = 2;
// 速度 EMA 平滑系数与停顿后重新播种的间隔（镜像 EmaVelocityTracker）。
const EMA_ALPHA = 0.4;
const EMA_RESEED_GAP_MS = 50;

// Circle-shaped buttons (AppearanceApplier.isCircleButton).
const ROUND_IDS = new Set([
  "btnA", "btnB", "btnX", "btnY",
  "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
  "btnHome", "btnSelect", "btnMenu", "btnLS", "btnRS", "btnShare", "btnMic",
]);

function isCircleButton(id) {
  const b = baseId(id);
  return ROUND_IDS.has(b) || b.startsWith("btnCustomCircle");
}

// ── CustomKeypadView region tables ──
// Region index: 0=up, 1=down, 2=left, 3=right, 4=centre, 5=up-left, 6=up-right,
// 7=down-left, 8=down-right (mirrors ButtonPosition.KEYPAD_*).
// 8-direction mode: 3×3 grid cell (row*3+col) → region; -1 = centre cell.
const KEYPAD_GRID_REGION = [[5, 0, 6], [2, -1, 3], [7, 1, 8]];
// region index → grid cell index.
const KEYPAD_REGION_CELL = [1, 7, 3, 5, 4, 0, 2, 6, 8];
// Circular-centre mode: polar sector (0=right, clockwise) → region index.
const KEYPAD_CIRCLE_SECTOR_8 = [3, 8, 1, 7, 2, 5, 0, 6];
const KEYPAD_CIRCLE_SECTOR_4 = [3, 1, 2, 0];
// region → [startDeg, sweepDeg] sector ring (Android canvas: 0° = right, CW).
const KEYPAD_SECTORS = {
  "4": { 0: [225, 90], 1: [45, 90], 2: [135, 90], 3: [-45, 90] },
  "8": {
    0: [247.5, 45], 1: [67.5, 45], 2: [157.5, 45], 3: [-22.5, 45],
    5: [202.5, 45], 6: [292.5, 45], 7: [112.5, 45], 8: [22.5, 45],
  },
};

// ── 中心区域大小（%） ──
// 圆形中心半径 = 控件半径 × 百分比；方形中心边长 = 控件直径 × 百分比。
// -1 / 缺省 = 自动：一体十字键 33%，自定义按键盘 4 方向 40% / 8 方向 33%。
// 0 = 完全不显示中心（方向区域改为径向扇区）。
const CENTER_SIZE_AUTO = -1;
const CENTER_SIZE_MIN = 0;
const CENTER_SIZE_MAX = 70;
export function centerSizeOf(b) {
  const v = Math.round(b.centerSize == null ? CENTER_SIZE_AUTO : b.centerSize);
  if (v >= 0) return Math.max(CENTER_SIZE_MIN, Math.min(CENTER_SIZE_MAX, v));
  if (baseId(b.id) === "customKeypad") return b.keypadEightWay ? 33 : 40;
  return 33;
}

// 一体十字键圆形中心：扇区序号（0=右，顺时针）→ D-pad 位。
const DPAD_SECTOR_BITS = [
  DPAD.RIGHT,
  DPAD.DOWN | DPAD.RIGHT,
  DPAD.DOWN,
  DPAD.DOWN | DPAD.LEFT,
  DPAD.LEFT,
  DPAD.UP | DPAD.LEFT,
  DPAD.UP,
  DPAD.UP | DPAD.RIGHT,
];

// AppearanceApplier.highlightColor — move `factor` of the way toward white.
export function highlightColor(color, factor) {
  const { r, g, b, a } = parseColor(color);
  const mix = (c) => Math.max(0, Math.min(255, Math.round(c + (255 - c) * factor)));
  return toCss(mix(r), mix(g), mix(b), a);
}

function parseColor(color) {
  if (typeof color !== "string") return { r: 0, g: 0, b: 0, a: 1 };
  let s = color.trim();
  if (s.startsWith("#")) {
    if (s.length === 9) {
      const a = parseInt(s.slice(1, 3), 16);
      return { r: parseInt(s.slice(3, 5), 16), g: parseInt(s.slice(5, 7), 16), b: parseInt(s.slice(7, 9), 16), a: a / 255 };
    }
    if (s.length === 7) {
      return { r: parseInt(s.slice(1, 3), 16), g: parseInt(s.slice(3, 5), 16), b: parseInt(s.slice(5, 7), 16), a: 1 };
    }
    if (s.length === 4) {
      const a = parseInt(s[1] + s[1], 16);
      return { r: parseInt(s[2] + s[2], 16), g: parseInt(s[3] + s[3], 16), b: parseInt(s[4] + s[4], 16), a: a / 255 };
    }
  }
  const m = s.match(/rgba?\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(",").map((v) => parseFloat(v));
    return { r: p[0] || 0, g: p[1] || 0, b: p[2] || 0, a: p.length > 3 ? p[3] : 1 };
  }
  return { r: 0, g: 0, b: 0, a: 1 };
}

function toCss(r, g, b, a) {
  if (a >= 1) {
    return "#" + [r, g, b].map((n) => n.toString(16).padStart(2, "0")).join("");
  }
  return `rgba(${r},${g},${b},${a})`;
}

// Android content cap: settings.iconMaxSize sp → px (density 1); 100 = unlimited.
function contentCapPx(appearance) {
  const sp = appearance.iconMaxSize;
  if (sp == null || sp >= 100) return Infinity;
  return Math.max(1, sp);
}

// Content box: button minus the 10% adaptive padding applied to content controls.
function contentBox(w, h) {
  const pad = Math.min(w, h) * 0.1;
  return { w: Math.max(1, w - pad * 2), h: Math.max(1, h - pad * 2) };
}

export class GamepadLayout {
  constructor(canvas, callbacks) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.cb = callbacks || {};
    this.preset = { buttons: [] };
    this.buttons = [];
    this.displayMode = "XBOX";
    this.appearance = JSON.parse(JSON.stringify(DEFAULT_APPEARANCE));
    this.editMode = false;
    this.selectedId = null;
    this.pressedBits = 0;
    this.stickVisual = { left: { x: 0, y: 0 }, right: { x: 0, y: 0 } };
    this.controlActive = new Map();
    this.autoHoldState = new Map();

    this._pointers = new Map();
    this._dpr = Math.max(1, window.devicePixelRatio || 1);
    this._layout = { originX: 0, originY: 0, cell: 1 };

    // Grid overlay fade (mirrors GamepadLayout.animateGridTo): 0 hidden, 1 shown.
    // It only fades in while a control is being dragged/resized in edit mode.
    this._gridAlpha = 0;
    this._gridAnim = null;

    this._bind();
    this.resize();
  }

  // ── geometry ──
  resize() {
    // offsetWidth/offsetHeight ignore CSS transforms, so they stay correct while
    // the page is force-rotated to landscape (see js/orientation.js).
    const cssW = Math.max(1, this.canvas.offsetWidth);
    const cssH = Math.max(1, this.canvas.offsetHeight);
    this._dpr = Math.max(1, window.devicePixelRatio || 1);
    this.canvas.width = Math.round(cssW * this._dpr);
    this.canvas.height = Math.round(cssH * this._dpr);
    this.cssW = cssW;
    this.cssH = cssH;

    const cell = cssW / GRID_COLS;
    const rows = cssH / cell;
    this._layout = {
      cell,
      rows,
      originX: 0,
      originY: 0,
    };
    this.render();
  }

  cellPx() {
    return this._layout.cell;
  }

  gridRect(b) {
    const { cell, originX, originY } = this._layout;
    const [w, h] = onScreenGridSize(b);
    return {
      x: originX + b.x * cell,
      y: originY + b.y * cell,
      w: w * cell,
      h: h * cell,
    };
  }

  setPreset(preset) {
    this.preset = preset || { buttons: [] };
    // Mirrors GamepadLayout.loadPreset: every layout always carries a settings
    // button, appended when the preset (e.g. a freshly created one) lacks it.
    const raw = (this.preset.buttons || []).slice();
    if (!raw.some((b) => baseId(b.id) === "btnSettings")) {
      raw.push({ id: "btnSettings", x: (GRID_COLS - 6) / 2, y: 0, w: 6, h: 6, lockAspect: true });
    }
    this.buttons = raw.map(normalizeButton);
    this.selectedId = null;
    this.render();
  }

  getButton(id) {
    return this.buttons.find((b) => b.id === id);
  }

  setDisplayMode(mode) {
    this.displayMode = mode;
    this.render();
  }

  setAppearance(app) {
    if (app) this.appearance = app;
    this.render();
  }

  setPressedBits(bits) {
    this.pressedBits = bits >>> 0;
  }

  setStickVisual(axis, x, y) {
    this.stickVisual[axis] = { x, y };
  }

  serialize() {
    // Mirrors LayoutPreset.toJson(): every non-default field is written so a
    // save/discard round-trip preserves the full editing state.
    const DOUBLE = new Set(["leftJoystick", "rightJoystick", "touchpad"]);
    const JOY = new Set(["leftJoystick", "rightJoystick"]);
    const AREA = new Set(["leftJoystick", "rightJoystick", "touchpad", "dpadPad", "customKeypad"]);
    return {
      version: this.preset.version || 1,
      buttons: this.buttons.map((b) => {
        const base = baseId(b.id);
        const o = { id: b.id, x: b.x, y: b.y, width: b.w, height: b.h, rotation: b.rotation || 0 };
        if (!b.visible) o.visible = false;
        if (b.swipeTrigger) o.swipeTrigger = true;
        if (b.isCustom) {
          o.isCustom = true;
          o.customText = b.customText || t("common.custom");
          o.customBits = b.customBits || [];
        }
        if (b.comboTiming && b.comboTiming !== "SIMULTANEOUS") o.comboTiming = b.comboTiming;
        if (b.comboIntervalMs !== 16) o.comboIntervalMs = b.comboIntervalMs;
        if (b.roundShape) o.roundShape = true;
        if (b.idleOpacity !== 100) o.idleOpacity = b.idleOpacity;
        if (b.activeOpacity !== 100) o.activeOpacity = b.activeOpacity;
        if (b.followAreaOpacity !== 100) o.followAreaOpacity = b.followAreaOpacity;
        if (b.overlapTrigger === false) o.overlapTrigger = false;
        if (b.followAreaOverlapTrigger) o.followAreaOverlapTrigger = true;
        if (DOUBLE.has(base) && b.doubleClickEnable === false) o.doubleClickEnable = false;
        if (JOY.has(base)) {
          if (b.joystickSensitivity !== 100) o.joystickSensitivity = b.joystickSensitivity;
          if (b.touchpadMode) o.touchpadMode = true;
          if (b.deadZone) o.deadZone = b.deadZone;
          if (b.reverseDeadZone) o.reverseDeadZone = b.reverseDeadZone;
          if (b.sensitivityCurve && b.sensitivityCurve.length) o.sensitivityCurve = b.sensitivityCurve;
        }
        if (b.prediction) o.prediction = true;
        if (base === "mousepad") {
          if (b.mouseSensitivity !== 1) o.mouseSensitivity = b.mouseSensitivity;
          if (Array.isArray(b.mouseAcceleration) && b.mouseAcceleration.length) {
            o.mouseAcceleration = b.mouseAcceleration.slice();
          }
          if (b.scrollSensitivity !== 0.1) o.scrollSensitivity = b.scrollSensitivity;
          if (b.mouseMoveSlop !== 4) o.mouseMoveSlop = b.mouseMoveSlop;
          if (b.invertScrollV) o.invertScrollV = true;
          if (b.invertScrollH) o.invertScrollH = true;
          if (b.singleTapAction !== "LEFT_CLICK") o.singleTapAction = b.singleTapAction;
          if (b.twoFingerTapAction !== "RIGHT_CLICK") o.twoFingerTapAction = b.twoFingerTapAction;
          if (b.threeFingerTapAction !== "MIDDLE_CLICK") o.threeFingerTapAction = b.threeFingerTapAction;
          if (b.doubleTapDragAction !== "LEFT_DRAG") o.doubleTapDragAction = b.doubleTapDragAction;
          if (b.oneFingerSwipeAction !== "MOVE_CURSOR") o.oneFingerSwipeAction = b.oneFingerSwipeAction;
          if (b.twoFingerSwipeAction !== "SCROLL") o.twoFingerSwipeAction = b.twoFingerSwipeAction;
          if (b.threeFingerSwipeAction !== "SCROLL") o.threeFingerSwipeAction = b.threeFingerSwipeAction;
        }
        if (AREA.has(base) && b.followAreaEnabled) {
          o.followAreaEnabled = true;
          o.followAreaX = b.followAreaX | 0;
          o.followAreaY = b.followAreaY | 0;
          o.followAreaW = b.followAreaW | 0;
          o.followAreaH = b.followAreaH | 0;
        }
        if (b.linearTriggerEnabled) {
          o.linearTriggerEnabled = true;
          if (b.slideDirection !== "DOWN") o.slideDirection = b.slideDirection;
          if (b.travelDistance !== 10) o.travelDistance = b.travelDistance;
        }
        if (b.isKeypad) {
          o.keypadTexts = b.keypadTexts;
          o.keypadBits = b.keypadBits;
          const timings = b.keypadComboTimings || [];
          if (timings.some((v) => v && v !== "SIMULTANEOUS")) {
            o.keypadComboTimings = timings;
          }
          const intervals = b.keypadComboIntervals || [];
          if (intervals.some((v) => v != null && v !== 16)) {
            o.keypadComboIntervals = intervals;
          }
          if (b.keypadEightWay) o.keypadEightWay = true;
        }
        if (b.centerShape && b.centerShape !== "SQUARE") o.centerShape = b.centerShape;
        if (b.centerSize >= 0) o.centerSize = Math.max(CENTER_SIZE_MIN, Math.min(CENTER_SIZE_MAX, Math.round(b.centerSize)));
        if (b.gyroActivate) o.gyroActivate = true;
        if (b.autoHold) o.autoHold = true;
        return o;
      }),
    };
  }

  // ── edit-mode lifecycle ──
  enterEditMode() {
    this._snapshot = this.serialize();
    this.editMode = true;
    this.selectedId = null;
    this._adjustingFollowArea = false;
    this._resetGrid();
    this.render();
  }

  exitEditMode() {
    this.editMode = false;
    this.selectedId = null;
    this._edit = null;
    this._adjustingFollowArea = false;
    this._resetGrid();
    this.render();
  }

  discardToSnapshot() {
    if (this._snapshot) this.setPreset(this._snapshot);
  }

  hasUnsavedChanges() {
    if (!this._snapshot) return false;
    return JSON.stringify(this.serialize()) !== JSON.stringify(this._snapshot);
  }

  enterFollowAreaAdjust(id) {
    this._adjustingFollowArea = true;
    this.selectedId = id;
    this.render();
  }

  exitFollowAreaAdjust() {
    this._adjustingFollowArea = false;
    this.render();
  }

  // Grid overlay fade — mirrors GamepadLayout.animateGridTo (200 ms). Fades in
  // only while a control drag/resize is active, fades out once it ends.
  _animateGridTo(target) {
    if (this._gridAnim) {
      cancelAnimationFrame(this._gridAnim);
      this._gridAnim = null;
    }
    const from = this._gridAlpha;
    if (from === target) return;
    const start = performance.now();
    const duration = 200;
    const step = (now) => {
      const t = Math.min(1, (now - start) / duration);
      this._gridAlpha = from + (target - from) * t;
      this.render();
      if (t < 1) this._gridAnim = requestAnimationFrame(step);
      else this._gridAnim = null;
    };
    this._gridAnim = requestAnimationFrame(step);
  }

  _resetGrid() {
    if (this._gridAnim) {
      cancelAnimationFrame(this._gridAnim);
      this._gridAnim = null;
    }
    this._gridAlpha = 0;
  }

  // Partial update of an existing button, preserving canvas-only fields (w/h).
  updateButton(id, props) {
    const b = this.getButton(id);
    if (!b) return;
    Object.assign(b, props);
    this.render();
  }

  // ── rendering ──
  // Mirrors com.zyz4.gkme.view.GamepadLayout (background + trigger areas) and
  // GamepadRenderer (grid + selection) plus each control view's own onDraw.
  render() {
    const ctx = this.ctx;
    ctx.save();
    ctx.setTransform(this._dpr, 0, 0, this._dpr, 0, 0);
    ctx.clearRect(0, 0, this.cssW, this.cssH);

    const app = this.appearance;
    if (app.bgFillType === 1 && app.bgImage) {
      this._drawCoverImage(app.bgImage);
    } else {
      ctx.fillStyle = app.bgColor || "#000000";
      ctx.fillRect(0, 0, this.cssW, this.cssH);
    }

    // Extended follow-area / linear-trigger outlines drawn under the controls.
    this._drawFollowAreas();
    this._drawLinearTriggerBoxes();

    if (this.editMode) this._drawGrid();

    for (const b of this.buttons) {
      if (!b.visible) continue;
      this._drawControl(b);
    }

    if (this.editMode && this.selectedId) this._drawSelection(this.getButton(this.selectedId));
    ctx.restore();
  }

  _drawCoverImage(img) {
    const ctx = this.ctx;
    const iw = img.naturalWidth || img.width;
    const ih = img.naturalHeight || img.height;
    if (!iw || !ih) return;
    const scale = Math.max(this.cssW / iw, this.cssH / ih);
    const w = iw * scale;
    const h = ih * scale;
    ctx.drawImage(img, (this.cssW - w) / 2, (this.cssH - h) / 2, w, h);
  }

  // GamepadRenderer.drawGrid — 1px #787878 lines at gridAlpha (170/255), the
  // overlay only visible while dragging/resizing (see _animateGridTo).
  _drawGrid() {
    if (this._gridAlpha <= 0) return;
    const ctx = this.ctx;
    const { cell } = this._layout;
    ctx.save();
    ctx.globalAlpha *= this._gridAlpha * 0.667;
    ctx.strokeStyle = "rgb(120,120,120)";
    ctx.lineWidth = 1;
    for (let x = 0; x <= GRID_COLS; x++) {
      ctx.beginPath();
      ctx.moveTo(x * cell, 0);
      ctx.lineTo(x * cell, this.cssH);
      ctx.stroke();
    }
    for (let y = 0; y * cell <= this.cssH; y++) {
      ctx.beginPath();
      ctx.moveTo(0, y * cell);
      ctx.lineTo(this.cssW, y * cell);
      ctx.stroke();
    }
    ctx.restore();
  }

  // 控件描边粗细以「实际屏幕像素」为准：canvas 已按 _dpr 缩放，
  // 因此把像素值换算成当前变换下的用户单位需除以 _dpr。
  _outlineW(px) {
    return px / this._dpr;
  }

  // GamepadRenderer follow-area rectangles (joystick / touchpad / dpadPad / keypad).
  _drawFollowAreas() {
    const ctx = this.ctx;
    const { cell } = this._layout;
    for (const b of this.buttons) {
      if (!b.followAreaEnabled) continue;
      if (b.followAreaW == null || b.followAreaH == null) continue;
      const bid = baseId(b.id);
      let color;
      let width;
      if (b.isTouchpad) {
        color = this.appearance.tpTriggerOutlineColor;
        width = this.appearance.tpTriggerOutlineWidth;
      } else if (b.isDpadPad || b.isKeypad) {
        color = this.appearance.dpadPadTriggerOutlineColor;
        width = this.appearance.dpadPadTriggerOutlineWidth;
      } else if (b.isJoystick) {
        color = this.appearance.joyTriggerOutlineColor;
        width = this.appearance.joyTriggerOutlineWidth;
      } else {
        continue;
      }
      if (!(width > 0)) continue;
      const l = b.followAreaX * cell;
      const t = b.followAreaY * cell;
      const w = b.followAreaW * cell;
      const h = b.followAreaH * cell;
      const op = (b.followAreaOpacity == null ? 100 : b.followAreaOpacity) / 100;
      ctx.save();
      ctx.globalAlpha *= op;
      ctx.strokeStyle = color;
      ctx.lineWidth = this._outlineW(width);
      ctx.strokeRect(l, t, w, h);
      ctx.restore();
    }
  }

  // GamepadRenderer linear-trigger travel boxes (#888888), extended by the travel distance.
  _drawLinearTriggerBoxes() {
    const ctx = this.ctx;
    const { cell } = this._layout;
    const lw = this._outlineW(this.appearance.linearTriggerBoxOutlineWidth);
    if (!(lw > 0)) return;
    ctx.strokeStyle = this.appearance.linearTriggerBoxOutlineColor;
    ctx.lineWidth = lw;
    for (const b of this.buttons) {
      if (!b.linearTriggerEnabled) continue;
      const r = this.gridRect(b);
      let left = r.x;
      let top = r.y;
      let w = r.w;
      let h = r.h;
      const travel = (b.travelDistance || 10) * cell;
      switch (b.slideDirection) {
        case "UP": top -= travel; h += travel; break;
        case "LEFT": left -= travel; w += travel; break;
        case "RIGHT": w += travel; break;
        default: h += travel; break;
      }
      ctx.strokeRect(left, top, w, h);
    }
  }

  _active(b) {
    if (this.controlActive.get(b.id)) return true;
    if (b._doubleClick) return true;
    const c = getControl(b.id);
    if (c && c.bit) return (this.pressedBits & c.bit) !== 0;
    return false;
  }

  // Centered shape path (origin at the control centre).
  _shape(round, w, h, radius) {
    const ctx = this.ctx;
    ctx.beginPath();
    if (round) {
      ctx.arc(0, 0, Math.min(w, h) / 2, 0, Math.PI * 2);
      return;
    }
    const x = -w / 2;
    const y = -h / 2;
    const r = Math.min(radius, w / 2, h / 2);
    if (r <= 0) {
      ctx.rect(x, y, w, h);
      return;
    }
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  _drawControl(b) {
    const box = this.gridRect(b);
    if (box.w <= 0 || box.h <= 0) return;
    const ctx = this.ctx;
    const active = this._active(b);
    const opacity = (active ? b.activeOpacity : b.idleOpacity) / 100;
    if (opacity <= 0) return;

    // Android lays the child out in its visual (swapped) box, then rotates the
    // original width/height around the box centre. Mirror that by passing the
    // unswapped dimensions to the per-control painters.
    const cell = this._layout.cell;
    const r = { x: 0, y: 0, w: b.w * cell, h: b.h * cell };

    ctx.save();
    ctx.globalAlpha *= opacity;
    ctx.translate(box.x + box.w / 2, box.y + box.h / 2);
    // Linear trigger: slide the button along its axis by the drag travel
    // (LinearTriggerView setTranslationX/Y).
    if (b.isTrigger && b.linearTriggerEnabled && b._triggerOffset) {
      if (b.slideDirection === "LEFT" || b.slideDirection === "RIGHT") {
        ctx.translate(b._triggerOffset, 0);
      } else {
        ctx.translate(0, b._triggerOffset);
      }
    }
    if (b.rotation) ctx.rotate((b.rotation * Math.PI) / 180);

    if (b.isJoystick) this._drawJoystick(b, r);
    else if (b.isDpadPad) this._drawDpadPad(b, r, active);
    else if (b.isKeypad) this._drawKeypad(b, r);
    else if (b.isTouchpad || b.isMousepad) this._drawPad(b, r, active);
    else this._drawButton(b, r, active);

    ctx.restore();
  }

  // ── standard button (AppearanceApplier.applyToButton) ──
  _drawButton(b, r, active) {
    const ctx = this.ctx;
    const app = this.appearance;
    const w = r.w;
    const h = r.h;
    const round = b.roundShape || isCircleButton(b.id);
    const radius = round ? Math.min(w, h) / 2 : CORNER_RADIUS_DP;

    if (app.btnFillType === 1 && app.btnImage) {
      ctx.save();
      this._shape(round, w, h, radius);
      ctx.clip();
      const img = app.btnImage;
      const iw = img.naturalWidth || img.width;
      const ih = img.naturalHeight || img.height;
      if (iw && ih) {
        const s = Math.max(w / iw, h / ih);
        ctx.drawImage(img, (-iw * s) / 2, (-ih * s) / 2, iw * s, ih * s);
      }
      ctx.restore();
    } else {
      ctx.fillStyle = active ? highlightColor(app.btnColor, 0.3) : app.btnColor;
      this._shape(round, w, h, radius);
      ctx.fill();
    }

    if (app.btnOutlineWidth > 0) {
      ctx.strokeStyle = active ? highlightColor(app.btnOutlineColor, 0.3) : app.btnOutlineColor;
      ctx.lineWidth = this._outlineW(app.btnOutlineWidth);
      this._shape(round, w, h, radius);
      ctx.stroke();
    }

    this._drawButtonContent(b, w, h);
  }

  _drawButtonContent(b, w, h) {
    const ctx = this.ctx;
    const app = this.appearance;
    const cap = contentCapPx(app);
    const box = contentBox(w, h);
    const maxDim = Math.min(box.w, box.h, cap);

    // Settings gear: ImageButton with 8dp padding + CENTER_INSIDE (not adaptive content).
    if (baseId(b.id) === "btnSettings") {
      const bw = Math.max(1, w - 16);
      const bh = Math.max(1, h - 16);
      const md = Math.min(bw, bh, 24);
      drawIcon(ctx, "settings", 0, 0, md, bw, bh, "#CCCCCC");
      return;
    }

    if (b.isCustom) {
      this._drawLabel(b.customText || "", w, h, cap, app.btnIconColor, 500);
      return;
    }
    if (b.isKeyboard) {
      this._drawLabel(keyboardCaption(b.keyCode, this.keyboardShift), w, h, cap, app.btnIconColor, 500);
      return;
    }
    const id = baseId(b.id);
    if (id === "btnLS" || id === "btnRS") {
      this._drawLetterIcon(id, w, h, cap, app.btnIconColor);
      return;
    }
    const icon = this._iconFor(b.id);
    if (icon) {
      drawIcon(ctx, icon, 0, 0, maxDim, box.w, box.h, app.btnIconColor);
      return;
    }
    const label = controlLabel(b.id, this.displayMode);
    if (label) this._drawLabel(label, w, h, cap, app.btnIconColor, 500);
  }

  // LetterIconDrawable — triangle in the top band, L/R letter centered below.
  _drawLetterIcon(id, w, h, cap, color) {
    const ctx = this.ctx;
    const name = id === "btnLS" ? "ls" : "rs";
    const s = Math.min(w * 0.5, h * 0.24, cap);
    if (s > 0) {
      drawIcon(ctx, name, 0, -h / 2 + h * 0.05 + s / 2, s, s, s, color);
    }
    const letter = id === "btnLS" ? "L" : "R";
    this._drawLabel(letter, w, h, Math.min(w * 0.55, h * 0.45, cap), color, 700);
  }

  // Android auto-fit text capped at iconMaxSize (TextView.applyContentSizeCap).
  _drawLabel(label, w, h, cap, color, weight) {
    if (!label) return;
    const ctx = this.ctx;
    const box = contentBox(w, h);
    ctx.fillStyle = color;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    const ref = 100;
    ctx.font = `${weight || 500} ${ref}px ${UI_FONT}`;
    const m = ctx.measureText(label);
    const tw = Math.max(0.01, m.width);
    const th = Math.max(
      0.01,
      (m.actualBoundingBoxAscent || ref * 0.72) + (m.actualBoundingBoxDescent || ref * 0.2)
    );
    let fs = Math.min(ref * (box.w / tw), ref * (box.h / th));
    if (cap !== Infinity) fs = Math.min(fs, cap);
    ctx.font = `${weight || 500} ${Math.max(1, fs)}px ${UI_FONT}`;
    ctx.fillText(label, 0, 0);
  }

  // AppearanceApplier.getIconDrawable + updateButtonLabels.
  _iconFor(id) {
    const b = baseId(id);
    const m = this.displayMode;
    switch (b) {
      case "btnSettings": return "settings";
      case "btnDpadUp": return "arrow_up";
      case "btnDpadDown": return "arrow_down";
      case "btnDpadLeft": return "arrow_left";
      case "btnDpadRight": return "arrow_right";
      case "btnMic": return "mic";
      case "btnHome":
        return m === "XBOX" ? "home_xbox" : m === "SWITCH" ? "home" : "home_playstation";
      case "btnSelect":
        return m === "XBOX" ? "view" : m === "SWITCH" ? "minus" : null;
      case "btnMenu":
        return m === "XBOX" ? "menu" : m === "SWITCH" ? "plus" : null;
      case "btnShare":
        return m === "XBOX" ? "share_xbox" : m === "SWITCH" ? "screenshot_switch" : "touchpad_grid";
      case "btnA": return m === "PS" ? "ps_cross" : null;
      case "btnB": return m === "PS" ? "ps_circle" : null;
      case "btnX": return m === "PS" ? "ps_square" : null;
      case "btnY": return m === "PS" ? "ps_triangle" : null;
      default: return null;
    }
  }

  // ── joystick (JoystickView.onDraw) ──
  _drawJoystick(b, r) {
    const ctx = this.ctx;
    const app = this.appearance;
    const active = this._active(b);
    const baseR = Math.min(r.w, r.h) / 2;
    const knobR = baseR * 0.32;

    // Follow-area mode shifts the base + cap with the finger, mirroring
    // JoystickView.onDraw's translate(effectiveCenter). The layout does not clip
    // (setClipChildren(false)), so the moved control may overlap its neighbours.
    const fdx = b._followDx || 0;
    const fdy = b._followDy || 0;
    if (fdx || fdy) {
      ctx.save();
      ctx.translate(fdx, fdy);
    }

    // Base fill
    if (app.joyBaseFillType === 1 && app.joyBaseImage) {
      ctx.save();
      ctx.beginPath();
      ctx.arc(0, 0, baseR, 0, Math.PI * 2);
      ctx.clip();
      const img = app.joyBaseImage;
      const iw = img.naturalWidth || img.width;
      const ih = img.naturalHeight || img.height;
      if (iw && ih) {
        const s = Math.max((baseR * 2) / iw, (baseR * 2) / ih);
        ctx.drawImage(img, (-iw * s) / 2, (-ih * s) / 2, iw * s, ih * s);
      }
      ctx.restore();
    } else {
      ctx.beginPath();
      ctx.arc(0, 0, baseR, 0, Math.PI * 2);
      ctx.fillStyle = active ? highlightColor(app.joyBaseColor, 0.3) : app.joyBaseColor;
      ctx.fill();
    }
    // Base outline
    if (app.joyBaseOutlineWidth > 0) {
      const lw = this._outlineW(app.joyBaseOutlineWidth);
      ctx.beginPath();
      ctx.arc(0, 0, baseR - lw / 2, 0, Math.PI * 2);
      ctx.strokeStyle = active ? highlightColor(app.joyBaseOutlineColor, 0.3) : app.joyBaseOutlineColor;
      ctx.lineWidth = lw;
      ctx.stroke();
    }

    // Cap
    const vis = this.stickVisual[b.axis] || { x: 0, y: 0 };
    const maxD = baseR - knobR;
    const kx = (vis.x / 32767) * maxD;
    const ky = (vis.y / 32767) * maxD;
    if (app.joyCapFillType === 1 && app.joyCapImage) {
      ctx.save();
      ctx.beginPath();
      ctx.arc(kx, ky, knobR, 0, Math.PI * 2);
      ctx.clip();
      const img = app.joyCapImage;
      const iw = img.naturalWidth || img.width;
      const ih = img.naturalHeight || img.height;
      if (iw && ih) {
        const s = Math.max((knobR * 2) / iw, (knobR * 2) / ih);
        ctx.drawImage(img, kx - (iw * s) / 2, ky - (ih * s) / 2, iw * s, ih * s);
      }
      ctx.restore();
    } else {
      ctx.beginPath();
      ctx.arc(kx, ky, knobR, 0, Math.PI * 2);
      ctx.fillStyle = active ? highlightColor(app.joyCapColor, 0.3) : app.joyCapColor;
      ctx.fill();
    }
    if (app.joyCapOutlineWidth > 0) {
      const lw = this._outlineW(app.joyCapOutlineWidth);
      ctx.beginPath();
      ctx.arc(kx, ky, knobR - lw / 2, 0, Math.PI * 2);
      ctx.strokeStyle = active ? highlightColor(app.joyCapOutlineColor, 0.3) : app.joyCapOutlineColor;
      ctx.lineWidth = lw;
      ctx.stroke();
    }

    // L/R label — JoystickView.onDraw draws the letter centred on the cap ("L" for the left
    // stick, "R" for the right), sized at knobRadius × 1.1 and capped by iconMaxSize.
    const label = b.axis === "left" ? "L" : "R";
    const labelCap = contentCapPx(app);
    let fs = knobR * 1.1;
    if (labelCap !== Infinity) fs = Math.min(fs, labelCap);
    if (fs > 0) {
      ctx.fillStyle = app.joyIconColor;
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.font = `${fs}px ${UI_FONT}`;
      ctx.fillText(label, kx, ky);
    }

    // Dead-zone (yellow #FFEE00) / reverse dead-zone (blue #0088FF) indicators.
    // Only shown while editing the layout with this control selected.
    if (this.editMode && this.selectedId === b.id) {
      if (b.deadZone > 0) {
        ctx.beginPath();
        ctx.arc(0, 0, baseR * (b.deadZone / 100), 0, Math.PI * 2);
        ctx.strokeStyle = "#FFEE00";
        ctx.lineWidth = this._outlineW(3);
        ctx.stroke();
      }
      if (b.reverseDeadZone > 0) {
        ctx.beginPath();
        ctx.arc(0, 0, baseR * (b.reverseDeadZone / 100), 0, Math.PI * 2);
        ctx.strokeStyle = "#0088FF";
        ctx.lineWidth = this._outlineW(3);
        ctx.stroke();
      }
    }

    if (fdx || fdy) ctx.restore();
  }

  // ── integrated D-pad (DpadPadView.onDraw) ──
  _drawDpadPad(b, r, active) {
    const ctx = this.ctx;
    const app = this.appearance;
    const side = Math.min(r.w, r.h);
    const radius = side / 2;
    const centerHalf = (radius * centerSizeOf(b)) / 100;
    const edge = radius - centerHalf;
    const ox = -side / 2;
    const oy = -side / 2;
    const hat = b._hat || 0;
    const circleCenter = (b.centerShape || "SQUARE") === "CIRCLE";
    // 中心为 0 时仍保持用户选择的形状：圆形→径向扇区，方形→网格。
    const radial = circleCenter;

    // Follow-area mode shifts the whole graphic with the finger, mirroring
    // DpadPadView.onDraw's translate(effectiveCenter). The layout does not clip
    // (setClipChildren(false)), so the moved control may overlap its neighbours.
    const fdx = b._followDx || 0;
    const fdy = b._followDy || 0;
    if (fdx || fdy) {
      ctx.save();
      ctx.translate(fdx, fdy);
    }

    // Circle fill
    ctx.beginPath();
    ctx.arc(0, 0, radius, 0, Math.PI * 2);
    ctx.fillStyle = app.dpadPadColor;
    ctx.fill();

    // Highlight the active region, clipped to the circle.
    if (hat) {
      ctx.save();
      ctx.beginPath();
      ctx.arc(0, 0, radius, 0, Math.PI * 2);
      ctx.clip();
      ctx.fillStyle = highlightColor(app.dpadPadColor, 0.3);
      if (radial) {
        const k = this._dpadSectorIndex(hat);
        if (k >= 0) {
          this._keypadSector(ctx, radius, centerHalf, -22.5 + k * 45, 45);
          ctx.fill();
        }
      } else {
        const c = this._dpadCellRect(hat, ox, oy, centerHalf);
        if (c) ctx.fillRect(c.x, c.y, c.w, c.h);
      }
      ctx.restore();
    }

    // Direction arrows in the direction cells / sector ring.
    const cap = contentCapPx(app);
    const color = app.dpadPadIconColor;
    if (radial) {
      const ringR = centerHalf + edge * 0.55;
      const cell = Math.max(0, edge);
      const size = Math.min(cell * 0.55, cap);
      if (size > 0) {
        const arrow = (name, deg) => {
          const rad = (deg * Math.PI) / 180;
          drawIcon(ctx, name, ringR * Math.cos(rad), ringR * Math.sin(rad), size, cell, cell, color);
        };
        arrow("arrow_up", 270); arrow("arrow_down", 90);
        arrow("arrow_left", 180); arrow("arrow_right", 0);
      }
    } else {
      const centerW = centerHalf * 2;
      const arrow = (name, cx, cy, boxW, boxH) => {
        const size = Math.min(boxW, boxH) * 0.55;
        if (size <= 0) return;
        drawIcon(ctx, name, cx, cy, Math.min(size, cap), boxW, boxH, color);
      };
      arrow("arrow_up", 0, oy + edge / 2, centerW, edge);
      arrow("arrow_down", 0, oy + side - edge / 2, centerW, edge);
      arrow("arrow_left", ox + edge / 2, 0, edge, centerW);
      arrow("arrow_right", ox + side - edge / 2, 0, edge, centerW);
    }

    // Border + separators (same color & width).
    if (app.dpadPadOutlineWidth > 0) {
      ctx.strokeStyle = app.dpadPadOutlineColor;
      ctx.lineWidth = this._outlineW(app.dpadPadOutlineWidth);
      ctx.beginPath();
      ctx.arc(0, 0, radius, 0, Math.PI * 2);
      ctx.stroke();
      ctx.save();
      ctx.beginPath();
      ctx.arc(0, 0, radius, 0, Math.PI * 2);
      ctx.clip();
      ctx.beginPath();
      if (radial) {
        ctx.arc(0, 0, centerHalf, 0, Math.PI * 2);
        for (let i = 0; i < 8; i++) {
          const deg = 22.5 + i * 45;
          const rad = (deg * Math.PI) / 180;
          ctx.moveTo(centerHalf * Math.cos(rad), centerHalf * Math.sin(rad));
          ctx.lineTo(radius * Math.cos(rad), radius * Math.sin(rad));
        }
      } else {
        ctx.moveTo(-centerHalf, oy); ctx.lineTo(-centerHalf, oy + side);
        ctx.moveTo(centerHalf, oy); ctx.lineTo(centerHalf, oy + side);
        ctx.moveTo(ox, -centerHalf); ctx.lineTo(ox + side, -centerHalf);
        ctx.moveTo(ox, centerHalf); ctx.lineTo(ox + side, centerHalf);
      }
      ctx.stroke();
      ctx.restore();
    }

    if (fdx || fdy) ctx.restore();
  }

  /** D-pad bit combination → circular-centre sector index (0=right, clockwise). */
  _dpadSectorIndex(bits) {
    const U = DPAD.UP, D = DPAD.DOWN, L = DPAD.LEFT, R = DPAD.RIGHT;
    switch (bits) {
      case R: return 0;
      case D | R: return 1;
      case D: return 2;
      case D | L: return 3;
      case L: return 4;
      case U | L: return 5;
      case U: return 6;
      case U | R: return 7;
      default: return -1;
    }
  }

  _dpadCellRect(bits, ox, oy, centerHalf) {
    const U = DPAD.UP, D = DPAD.DOWN, L = DPAD.LEFT, R = DPAD.RIGHT;
    let col = -1, row = -1;
    switch (bits) {
      case U: row = 0; col = 1; break;
      case D: row = 2; col = 1; break;
      case L: row = 1; col = 0; break;
      case R: row = 1; col = 2; break;
      case U | L: row = 0; col = 0; break;
      case U | R: row = 0; col = 2; break;
      case D | L: row = 2; col = 0; break;
      case D | R: row = 2; col = 2; break;
      default: return null;
    }
    const xs = [ox, -centerHalf, centerHalf, -ox];
    const ys = [oy, -centerHalf, centerHalf, -oy];
    return { x: xs[col], y: ys[row], w: xs[col + 1] - xs[col], h: ys[row + 1] - ys[row] };
  }

  // ── custom keypad (CustomKeypadView.onDraw) ──
  // Supports 4/8-direction layouts with a square or circular centre:
  //   square + 4 = wedges around a centre square,
  //   square + 8 = 3×3 grid inside the circle,
  //   circle + 4/8 = radial sector rings around a centre circle.
  _drawKeypad(b, r) {
    const ctx = this.ctx;
    const app = this.appearance;
    const side = Math.min(r.w, r.h);
    const radius = side / 2;
    const d = radius * 0.70710678;
    const eightWay = !!b.keypadEightWay;
    const circleCenter = (b.centerShape || "SQUARE") === "CIRCLE";
    const region = b._region;
    const base = app.dpadPadColor;
    const highlight = highlightColor(base, 0.3);
    const centerHighlight = highlightColor(base, 0.45);
    const centerHalf = (radius * centerSizeOf(b)) / 100;
    const edge = radius - centerHalf;
    const centerR = centerHalf;
    // 中心为 0 时仍保持用户选择的形状：圆形→径向扇区，方形→网格。
    const radial = circleCenter;

    // Follow-area mode shifts the whole graphic with the finger, mirroring
    // CustomKeypadView.onDraw's translate(effectiveCenter). The layout does not
    // clip (setClipChildren(false)), so the moved control may overlap its neighbours.
    const fdx = b._followDx || 0;
    const fdy = b._followDy || 0;
    if (fdx || fdy) {
      ctx.save();
      ctx.translate(fdx, fdy);
    }

    // Base fill + region highlights, clipped to the circle.
    ctx.save();
    ctx.beginPath();
    ctx.arc(0, 0, radius, 0, Math.PI * 2);
    ctx.clip();

    ctx.fillStyle = base;
    ctx.beginPath();
    ctx.arc(0, 0, radius, 0, Math.PI * 2);
    ctx.fill();

    if (radial) {
      const sectors = KEYPAD_SECTORS[eightWay ? "8" : "4"];
      if (region != null && sectors[region]) {
        ctx.fillStyle = highlight;
        this._keypadSector(ctx, radius, centerR, sectors[region][0], sectors[region][1]);
        ctx.fill();
      }
      if (b._keypadCenter && centerR > 0) {
        ctx.fillStyle = centerHighlight;
        ctx.beginPath();
        ctx.arc(0, 0, centerR, 0, Math.PI * 2);
        ctx.fill();
      }
    } else if (eightWay) {
      if (region != null && region >= 0 && region <= 8 && region !== 4) {
        ctx.fillStyle = highlight;
        this._keypadCell(ctx, radius, centerHalf, region);
        ctx.fill();
      }
      ctx.fillStyle = b._keypadCenter ? centerHighlight : base;
      this._keypadCell(ctx, radius, centerHalf, 4);
      ctx.fill();
    } else {
      if (region != null && region >= 0 && region <= 3) {
        ctx.fillStyle = highlight;
        this._keypadWedge(ctx, region, centerHalf, d, radius);
        ctx.fill();
      }
      ctx.fillStyle = b._keypadCenter ? centerHighlight : base;
      ctx.beginPath();
      ctx.rect(-centerHalf, -centerHalf, centerHalf * 2, centerHalf * 2);
      ctx.fill();
    }
    ctx.restore(); // circle clip

    // ── border + region separators ──
    if (app.dpadPadOutlineWidth > 0) {
      ctx.strokeStyle = app.dpadPadOutlineColor;
      ctx.lineWidth = this._outlineW(app.dpadPadOutlineWidth);
      ctx.beginPath();
      ctx.arc(0, 0, radius, 0, Math.PI * 2);
      ctx.stroke();

      if (radial) {
        ctx.beginPath();
        ctx.arc(0, 0, centerR, 0, Math.PI * 2);
        ctx.stroke();
        const step = eightWay ? 45 : 90;
        for (let deg = eightWay ? 22.5 : 45; deg < 360; deg += step) {
          const rad = (deg * Math.PI) / 180;
          ctx.beginPath();
          ctx.moveTo(centerR * Math.cos(rad), centerR * Math.sin(rad));
          ctx.lineTo(radius * Math.cos(rad), radius * Math.sin(rad));
          ctx.stroke();
        }
      } else if (eightWay) {
        ctx.save();
        ctx.beginPath();
        ctx.arc(0, 0, radius, 0, Math.PI * 2);
        ctx.clip();
        ctx.beginPath();
        ctx.moveTo(-centerHalf, -radius); ctx.lineTo(-centerHalf, radius);
        ctx.moveTo(centerHalf, -radius); ctx.lineTo(centerHalf, radius);
        ctx.moveTo(-radius, -centerHalf); ctx.lineTo(radius, -centerHalf);
        ctx.moveTo(-radius, centerHalf); ctx.lineTo(radius, centerHalf);
        ctx.stroke();
        ctx.restore();
      } else {
        ctx.beginPath();
        ctx.moveTo(-centerHalf, -centerHalf); ctx.lineTo(-d, -d);
        ctx.moveTo(centerHalf, -centerHalf); ctx.lineTo(d, -d);
        ctx.moveTo(-centerHalf, centerHalf); ctx.lineTo(-d, d);
        ctx.moveTo(centerHalf, centerHalf); ctx.lineTo(d, d);
        ctx.stroke();
        ctx.strokeRect(-centerHalf, -centerHalf, centerHalf * 2, centerHalf * 2);
      }
    }

    // ── region texts (0=up,1=down,2=left,3=right,4=centre,5..8=diagonals) ──
    const texts = (b.keypadTexts || keypadDefaultTexts()).map(localizeKeypadText);
    const textAt = (i) => (texts[i] != null ? texts[i] : "");
    const cap = contentCapPx(app);
    const color = app.dpadPadIconColor;
    if (radial) {
      const midR = centerR + (radius - centerR) * 0.62;
      const box = (radius - centerR) * 0.9;
      const place = (reg, deg) => {
        const rad = (deg * Math.PI) / 180;
        this._keypadText(textAt(reg), midR * Math.cos(rad), midR * Math.sin(rad), box, cap, color);
      };
      place(0, 270); place(1, 90); place(2, 180); place(3, 0);
      if (eightWay) { place(5, 225); place(6, 315); place(7, 135); place(8, 45); }
      if (centerR > 0) this._keypadText(textAt(4), 0, 0, centerR * 2, cap, color);
    } else if (eightWay) {
      const edgeW = Math.max(1, edge);
      const centerW = Math.max(1, centerHalf * 2);
      const cardinalBox = Math.max(1, Math.min(centerW, edgeW));
      this._keypadText(textAt(0), 0, -radius + edge / 2, cardinalBox, cap, color);
      this._keypadText(textAt(1), 0, radius - edge / 2, cardinalBox, cap, color);
      this._keypadText(textAt(2), -radius + edge / 2, 0, cardinalBox, cap, color);
      this._keypadText(textAt(3), radius - edge / 2, 0, cardinalBox, cap, color);
      if (centerHalf > 0) this._keypadText(textAt(4), 0, 0, centerW, cap, color);
      // 四角格被圆裁剪成不规则形状：用可见区域形心定位，并按内切圆缩放文本。
      const corner = this._keypadCornerGeometry(radius, centerHalf);
      if (corner.d > 0) {
        const cd = corner.d * 0.95;
        this._keypadTextInCircle(textAt(5), -corner.c, -corner.c, cd, cap, color);
        this._keypadTextInCircle(textAt(6), corner.c, -corner.c, cd, cap, color);
        this._keypadTextInCircle(textAt(7), -corner.c, corner.c, cd, cap, color);
        this._keypadTextInCircle(textAt(8), corner.c, corner.c, cd, cap, color);
      }
    } else {
      const box = radius * 0.667;
      const off = centerHalf + edge * 0.475;
      this._keypadText(textAt(0), 0, -off, box, cap, color);
      this._keypadText(textAt(1), 0, off, box, cap, color);
      this._keypadText(textAt(2), -off, 0, box, cap, color);
      this._keypadText(textAt(3), off, 0, box, cap, color);
      if (centerHalf > 0) this._keypadText(textAt(4), 0, 0, Math.max(1, centerHalf * 2), cap, color);
    }

    if (fdx || fdy) ctx.restore();
  }

  // 8-way square-centre mode: 3×3 grid cell for a region index.
  _keypadCell(ctx, radius, centerHalf, region) {
    const cell = KEYPAD_REGION_CELL[region];
    const col = cell % 3;
    const row = (cell - col) / 3;
    const xs = [-radius, -centerHalf, centerHalf, radius];
    const ys = [-radius, -centerHalf, centerHalf, radius];
    ctx.beginPath();
    ctx.rect(xs[col], ys[row], xs[col + 1] - xs[col], ys[row + 1] - ys[row]);
  }

  // Circular-centre mode: sector ring between the centre circle and the outer circle.
  _keypadSector(ctx, outerR, innerR, startDeg, sweepDeg) {
    const s = (startDeg * Math.PI) / 180;
    const e = ((startDeg + sweepDeg) * Math.PI) / 180;
    ctx.beginPath();
    ctx.arc(0, 0, outerR, s, e, false);
    ctx.arc(0, 0, innerR, e, s, true);
    ctx.closePath();
  }

  _keypadWedge(ctx, region, c, d, radius) {
    ctx.beginPath();
    if (region === 0) { // up
      ctx.moveTo(c, -c); ctx.lineTo(-c, -c); ctx.lineTo(-d, -d);
      ctx.arc(0, 0, radius, Math.PI * 1.25, Math.PI * 1.75);
      ctx.closePath();
    } else if (region === 1) { // down
      ctx.moveTo(-c, c); ctx.lineTo(c, c); ctx.lineTo(d, d);
      ctx.arc(0, 0, radius, Math.PI * 0.25, Math.PI * 0.75);
      ctx.closePath();
    } else if (region === 2) { // left
      ctx.moveTo(-c, -c); ctx.lineTo(-c, c); ctx.lineTo(-d, d);
      ctx.arc(0, 0, radius, Math.PI * 0.75, Math.PI * 1.25);
      ctx.closePath();
    } else { // right
      ctx.moveTo(c, -c); ctx.lineTo(d, -d);
      ctx.arc(0, 0, radius, Math.PI * 1.75, Math.PI * 0.25);
      ctx.lineTo(c, c);
      ctx.closePath();
    }
  }

  _keypadText(text, cx, cy, box, cap, color) {
    if (!text) return;
    const ctx = this.ctx;
    ctx.fillStyle = color;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    const ref = 100;
    ctx.font = `500 ${ref}px ${UI_FONT}`;
    const m = ctx.measureText(text);
    const tw = Math.max(0.01, m.width);
    const th = Math.max(0.01, (m.actualBoundingBoxAscent || ref * 0.72) + (m.actualBoundingBoxDescent || ref * 0.2));
    let fs = Math.min(ref * (box * 0.8) / tw, ref * (box * 0.9) / th);
    if (cap !== Infinity) fs = Math.min(fs, cap);
    ctx.font = `500 ${Math.max(1, fs)}px ${UI_FONT}`;
    ctx.fillText(text, cx, cy);
  }

  /** 在半径为 radius 的圆内居中绘制文本，字号按文本外接框对角线计算。 */
  _keypadTextInCircle(text, cx, cy, radius, cap, color) {
    if (!text || radius <= 0) return;
    const ctx = this.ctx;
    ctx.fillStyle = color;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    const ref = 100;
    ctx.font = `500 ${ref}px ${UI_FONT}`;
    const m = ctx.measureText(text);
    const tw = Math.max(0.01, m.width);
    const th = Math.max(0.01, (m.actualBoundingBoxAscent || ref * 0.72) + (m.actualBoundingBoxDescent || ref * 0.2));
    let fs = (ref * radius * 2) / Math.max(0.01, Math.hypot(tw, th));
    if (cap !== Infinity) fs = Math.min(fs, cap);
    ctx.font = `500 ${Math.max(1, fs)}px ${UI_FONT}`;
    ctx.fillText(text, cx, cy);
  }

  /**
   * 8-way + square centre: the corner cell is clipped by the outer circle.
   * Returns the centroid (c, c) and inradius d of the visible region in the
   * first quadrant {u >= a, v >= a, u² + v² <= r²}, relative to the centre.
   */
  _keypadCornerGeometry(radius, centerHalf) {
    const r = radius;
    const a = Math.max(0, Math.min(centerHalf, r));
    if (r <= 0) return { c: 0, d: 0 };
    const s = Math.sqrt(Math.max(0, r * r - a * a));
    if (s <= a) return { c: 0, d: 0 };
    const theta = Math.asin(Math.max(-1, Math.min(1, a / r)));
    const area = r * r * 0.5 * (Math.PI / 2 - 2 * theta) - a * (s - a);
    if (area <= 0.0001) return { c: 0, d: 0 };
    const moment = (s * s * s - a * a * a) / 3 - (a * (s * s - a * a)) / 2;
    const c = moment / area;
    const d = Math.max(0, Math.min(c - a, r - c * Math.SQRT2));
    return { c, d };
  }

  // ── touchpad (AppearanceApplier.applyToTouchpad) ──
  _drawPad(b, r, active) {
    const ctx = this.ctx;
    const app = this.appearance;
    const w = r.w;
    const h = r.h;
    if (app.tpFillType === 1 && app.tpImage) {
      ctx.save();
      this._shape(false, w, h, TOUCHPAD_RADIUS_DP);
      ctx.clip();
      const img = app.tpImage;
      const iw = img.naturalWidth || img.width;
      const ih = img.naturalHeight || img.height;
      if (iw && ih) {
        const s = Math.max(w / iw, h / ih);
        ctx.drawImage(img, (-iw * s) / 2, (-ih * s) / 2, iw * s, ih * s);
      }
      if (active) {
        ctx.fillStyle = "rgba(255,255,255,0.235)";
        this._shape(false, w, h, TOUCHPAD_RADIUS_DP);
        ctx.fill();
      }
      ctx.restore();
    } else {
      ctx.fillStyle = active ? highlightColor(b.color || app.tpColor, 0.3) : b.color || app.tpColor;
      this._shape(false, w, h, TOUCHPAD_RADIUS_DP);
      ctx.fill();
    }
    if (app.tpOutlineWidth > 0) {
      ctx.strokeStyle = active ? highlightColor(app.tpOutlineColor, 0.3) : app.tpOutlineColor;
      ctx.lineWidth = this._outlineW(app.tpOutlineWidth);
      this._shape(false, w, h, TOUCHPAD_RADIUS_DP);
      ctx.stroke();
    }

    if (b._touchPoints) {
      for (const p of b._touchPoints) {
        const px = (p.nx - 0.5) * w;
        const py = (p.ny - 0.5) * h;
        ctx.beginPath();
        ctx.arc(px, py, Math.min(w, h) * 0.05, 0, Math.PI * 2);
        ctx.fillStyle = "rgba(204,204,204,0.85)";
        ctx.fill();
      }
    }
  }

  _followAreaRectPx(b) {
    const { cell } = this._layout;
    return {
      x: b.followAreaX * cell,
      y: b.followAreaY * cell,
      w: b.followAreaW * cell,
      h: b.followAreaH * cell,
    };
  }

  // ── selection overlay (GamepadRenderer.drawSelectionOverlay) ──
  _drawSelection(b) {
    if (!b) return;
    const ctx = this.ctx;
    ctx.save();
    ctx.setTransform(this._dpr, 0, 0, this._dpr, 0, 0);

    // Follow-area adjust: draw the extended-range rectangle + its handle instead.
    if (this._adjustingFollowArea) {
      const fr = this._followAreaRectPx(b);
      const op = (b.followAreaOpacity == null ? 100 : b.followAreaOpacity) / 100;
      ctx.save();
      ctx.globalAlpha *= op;
      ctx.strokeStyle = "rgba(0,255,0,0.804)";
      ctx.lineWidth = 3;
      ctx.strokeRect(fr.x, fr.y, fr.w, fr.h);
      ctx.restore();
      const fh = HANDLE_SIZE_DP;
      ctx.fillStyle = "#FFFFFF";
      ctx.fillRect(fr.x + fr.w - fh, fr.y + fr.h - fh, fh, fh);
      ctx.restore();
      return;
    }

    const r = this.gridRect(b);
    ctx.strokeStyle = "rgba(0,255,0,0.804)";
    ctx.lineWidth = 3;
    ctx.strokeRect(r.x, r.y, r.w, r.h);
    const h = HANDLE_SIZE_DP;
    ctx.fillStyle = "#FFFFFF";
    ctx.fillRect(r.x + r.w - h, r.y + r.h - h, h, h);

    if (baseId(b.id) !== "btnSettings") {
      const dist = MARKER_DIST_DP;
      let mx, my, angle;
      const rot = ((b.rotation || 0) % 360 + 360) % 360;
      if (rot === 90) { mx = r.x + r.w + dist; my = r.y + r.h / 2; angle = 90; }
      else if (rot === 180) { mx = r.x + r.w / 2; my = r.y + r.h + dist; angle = 180; }
      else if (rot === 270) { mx = r.x - dist; my = r.y + r.h / 2; angle = 270; }
      else { mx = r.x + r.w / 2; my = r.y - dist; angle = 0; }
      ctx.save();
      ctx.translate(mx, my);
      ctx.rotate((angle * Math.PI) / 180);
      ctx.fillStyle = "#FF0000";
      ctx.textAlign = "center";
      ctx.textBaseline = "middle";
      ctx.font = `500 14px ${UI_FONT}`;
      ctx.fillText(t("keypad.up"), 0, 0);
      ctx.restore();
    }
    ctx.restore();
  }

  // ── hit testing ──
  _pointInControl(b, px, py) {
    const r = this._hitRectFor(b, false);
    return px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h;
  }

  // Extended-range rectangle used by the touchpad when 扩展触摸范围 is on:
  // the touchpad accepts touches across the whole area (not just its own box).
  _hitRectFor(b, includeExtension = true) {
    if (includeExtension && b.isTouchpad && b.followAreaEnabled && b.followAreaW > 0 && b.followAreaH > 0) {
      return this._followAreaRectPx(b);
    }
    const r = this.gridRect(b);
    // Linear triggers slide away from their grid position while held or latched;
    // hit-test at the visible position instead of the original slot.
    if (b.isTrigger && b.linearTriggerEnabled && b._triggerOffset) {
      if (b.slideDirection === "LEFT" || b.slideDirection === "RIGHT") r.x += b._triggerOffset;
      else r.y += b._triggerOffset;
    }
    return r;
  }

  // Follow-area trigger: a joystick / integrated D-pad / custom keypad whose
  // extended rectangle contains the point follows the finger (its centre jumps
  // to the touch). Blocked when another control occupies the point, unless
  // 触发矩形区域重叠触发 is enabled.
  _hitFollowArea(px, py) {
    for (const b of this.buttons) {
      if (!b.visible || !(b.isJoystick || b.isDpadPad || b.isKeypad) || !b.followAreaEnabled) continue;
      if (!(b.followAreaW > 0 && b.followAreaH > 0)) continue;
      const r = this._followAreaRectPx(b);
      if (!(px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h)) continue;
      const other = this.buttons.find(
        (o) => o !== b && o.visible && !o.isSettings && this._pointInControl(o, px, py)
      );
      if (other && !b.followAreaOverlapTrigger) return null;
      return b;
    }
    return null;
  }

  _hitTest(px, py, includeExtension = true) {
    // settings button always wins
    const settings = this.buttons.find((b) => b.isSettings && b.visible);
    if (settings) {
      const r = this.gridRect(settings);
      if (px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h) return settings;
    }
    let found = null;
    for (const b of this.buttons) {
      if (!b.visible) continue;
      const r = this._hitRectFor(b, includeExtension);
      if (px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h) {
        found = b; // last drawn = topmost
      }
    }
    return found;
  }

  _hitHandle(px, py) {
    const b = this.getButton(this.selectedId);
    if (!b) return false;
    const r = this.gridRect(b);
    const hs = 22;
    return Math.abs(px - (r.x + r.w)) < hs && Math.abs(py - (r.y + r.h)) < hs;
  }

  pxToGrid(px, py) {
    const { cell, originX, originY } = this._layout;
    return { gx: (px - originX) / cell, gy: (py - originY) / cell };
  }

  // ── pointer handling ──
  _bind() {
    const c = this.canvas;
    c.addEventListener("pointerdown", (e) => this._onDown(e));
    c.addEventListener("pointermove", (e) => this._onMove(e));
    c.addEventListener("pointerup", (e) => this._onUp(e));
    c.addEventListener("pointercancel", (e) => this._onUp(e));
    c.addEventListener("contextmenu", (e) => e.preventDefault());
  }

  _pos(e) {
    const { x, y } = viewportToLocal(e.clientX, e.clientY);
    return { px: x, py: y };
  }

  _onDown(e) {
    e.preventDefault();
    const { px, py } = this._pos(e);
    if (this.editMode) {
      this._editDown(e, px, py);
      return;
    }

    // Follow-area trigger (joystick / dpadPad 矩形区域内跟随) takes priority.
    const followB = this._hitFollowArea(px, py);
    if (followB) {
      try { this.canvas.setPointerCapture(e.pointerId); } catch {}
      if (followB.isJoystick) {
        this._beginStick(e, followB, px, py, true);
      } else if (followB.isDpadPad) {
        this._pointers.set(e.pointerId, { id: followB.id, kind: "dpad", b: followB, follow: true, cx: px, cy: py });
        this._updateDpad(e.pointerId, px, py);
      } else {
        this._keypadDown(e, followB, px, py, true);
      }
      return;
    }

    const b = this._hitTest(px, py);
    if (!b) return;
    try { this.canvas.setPointerCapture(e.pointerId); } catch {}

    if (b.isSettings) {
      this.cb.onOpenSettings && this.cb.onOpenSettings();
      return;
    }

    const isAutoHold = b.autoHold;
    const alreadyActive = this.controlActive.get(b.id);

    if (b.isJoystick) {
      this._beginStick(e, b, px, py, false);
      return;
    }
    if (b.isDpadPad) {
      this._pointers.set(e.pointerId, { id: b.id, kind: "dpad", b });
      this._updateDpad(e.pointerId, px, py);
      return;
    }
    if (b.isMousepad) {
      this._mpDown(b, e, px, py);
      return;
    }
    if (b.isTouchpad) {
      // 双击按下: a second tap within 300ms / 32px emits TOUCHPAD_CLICK while held.
      const otherTouchpadPointers = [...this._pointers.values()].filter(
        (p) => p.kind === "touchpad" && p.b === b
      ).length;
      this._maybeDoubleTap(b, px, py, otherTouchpadPointers === 0, 32, () =>
        this._emitBit(b, BIT.TOUCHPAD_CLICK, true)
      );
      this._pointers.set(e.pointerId, { id: b.id, kind: "touchpad", b, px, py });
      this._updateTouchpad(b);
      return;
    }
    if (b.isKeypad) {
      this._keypadDown(e, b, px, py, false);
      return;
    }
    if (b.isTrigger && b.linearTriggerEnabled) {
      const which = baseId(b.id) === "btnLT" ? "left" : "right";
      // 自动保持：已锁存时，下一次拖动从当前行程继续；否则从头开始。
      const continuing = alreadyActive && (b._triggerValue || 0) > 0;
      b._which = which;
      const baseOffset = continuing ? b._triggerOffset || 0 : 0;
      if (!continuing) {
        b._triggerOffset = 0;
        b._triggerValue = 0;
        b._triggerDown = false;
        this.cb.onTrigger && this.cb.onTrigger(which, 0);
      }
      this._pointers.set(e.pointerId, {
        id: b.id, kind: "trigger", b, startX: px, startY: py, baseOffset,
        ema: {
          lx: baseOffset,
          lt: Number.isFinite(e.timeStamp) ? e.timeStamp : performance.now(),
          v: 0, interval: 0, seeded: false,
        },
      });
      this.controlActive.set(b.id, true);
      this.render();
      return;
    }

    // plain button / custom
    if (isAutoHold) {
      if (alreadyActive) this._releaseControl(b);
      else this._pressControl(b);
      return;
    }
    this._pointers.set(e.pointerId, { id: b.id, kind: "button", b });
    this._pressControl(b);
  }

  _onMove(e) {
    if (this.editMode) {
      this._editMove(e);
      return;
    }
    const info = this._pointers.get(e.pointerId);
    if (!info) return;
    const { px, py } = this._pos(e);
    if (info.kind === "stick") {
      // Cancel a pending double-tap if the finger moved too far.
      if (info.b._tapTime && Math.hypot(px - info.b._tapX, py - info.b._tapY) > 30) {
        info.b._tapTime = 0;
      }
      this._updateStick(e.pointerId, px, py, e);
    }
    else if (info.kind === "dpad") this._updateDpad(e.pointerId, px, py);
    else if (info.kind === "keypad") this._updateKeypad(e.pointerId, px, py);
    else if (info.kind === "trigger") this._updateTrigger(e.pointerId, px, py, e);
    else if (info.kind === "mousepad") this._mpMove(info.b, info, e);
    else if (info.kind === "touchpad") { info.px = px; info.py = py; this._updateTouchpad(info.b); }
  }

  _onUp(e) {
    if (this.editMode) {
      this._editUp(e);
      return;
    }
    const info = this._pointers.get(e.pointerId);
    if (!info) return;
    this._pointers.delete(e.pointerId);

    if (info.kind === "mousepad") {
      const cancel = e.type === "pointercancel";
      if (cancel) {
        this._mpUp(info.b, info, e, true);
      } else if (this._mpPointerCount(info.b) === 0) {
        this._mpUp(info.b, info, e, false);
      }
      return;
    }

    if (info.kind === "stick") {
      if (info.vel && info.vel.timer) clearTimeout(info.vel.timer);
      this._setStick(info.axis, 0, 0);
      this.controlActive.set(info.id, false);
      info.b._followDx = 0;
      info.b._followDy = 0;
      if (info.b._doubleClick) {
        this._emitBit(info.b, info.b.axis === "left" ? BIT.L3 : BIT.R3, false);
        info.b._doubleClick = false;
      }
    } else if (info.kind === "dpad") {
      info.b._hat = 0;
      info.b._followDx = 0;
      info.b._followDy = 0;
      this.cb.onDpad && this.cb.onDpad(0);
      this.controlActive.set(info.id, false);
    } else if (info.kind === "keypad") {
      const b = info.b;
      if (b._region != null && b._region !== 4) this._emitKeypadRegion(b, b._region, false);
      if (b._keypadCenter) {
        b._keypadCenter = false;
        this._emitKeypadRegion(b, 4, false);
      }
      b._region = null;
      b._followDx = 0;
      b._followDy = 0;
      b._doubleClick = false;
      this.controlActive.set(b.id, false);
    } else if (info.kind === "trigger") {
      const b = info.b;
      // 自动保持：松手后保留当前行程与模拟值，下一次拖动继续调整。
      if (b.autoHold && (b._triggerValue || 0) > 0) {
        this.render();
        return;
      }
      // LinearTriggerView ACTION_UP: reset travel + value, release the digital bit.
      b._triggerOffset = 0;
      b._triggerValue = 0;
      this.cb.onTrigger && this.cb.onTrigger(b._which, 0);
      if (b._triggerDown) {
        this._releaseControl(b);
        b._triggerDown = false;
      } else {
        this.controlActive.set(b.id, false);
      }
      this.render();
    } else if (info.kind === "touchpad") {
      this._updateTouchpad(info.b);
      const remaining = [...this._pointers.values()].filter(
        (p) => p.kind === "touchpad" && p.b === info.b
      ).length;
      if (info.b._doubleClick && remaining === 0) {
        this._emitBit(info.b, BIT.TOUCHPAD_CLICK, false);
        info.b._doubleClick = false;
      }
    } else if (info.kind === "button") {
      this._releaseControl(info.b);
    }
  }

  _pressControl(b) {
    const c = getControl(b.id);
    if (c && c.isKeyboard) {
      this.controlActive.set(b.id, true);
      this.cb.onKey && this.cb.onKey(c.keyCode, true);
    } else if (c && c.bit) {
      this.controlActive.set(b.id, true);
      this.cb.onButtonDown && this.cb.onButtonDown(b.id, c.bit);
    } else if (b.isCustom) {
      this.controlActive.set(b.id, true);
      this.cb.onCustomBits && this.cb.onCustomBits(b.customBits || [], true, {
        source: "custom:" + b.id,
        timing: b.comboTiming,
        intervalMs: b.comboIntervalMs,
      });
    }
    this.render();
  }

  _releaseControl(b) {
    const c = getControl(b.id);
    if (c && c.isKeyboard) {
      this.controlActive.set(b.id, false);
      this.cb.onKey && this.cb.onKey(c.keyCode, false);
    } else if (c && c.bit) {
      this.controlActive.set(b.id, false);
      this.cb.onButtonUp && this.cb.onButtonUp(b.id, c.bit);
    } else if (b.isCustom) {
      this.controlActive.set(b.id, false);
      this.cb.onCustomBits && this.cb.onCustomBits(b.customBits || [], false, {
        source: "custom:" + b.id,
        timing: b.comboTiming,
        intervalMs: b.comboIntervalMs,
      });
    }
    this.render();
  }

  setKeyboardShift(active) {
    if (this.keyboardShift === active) return;
    this.keyboardShift = active;
    this.render();
  }

  _setStick(axis, x, y) {
    this.stickVisual[axis] = { x, y };
    this.cb.onStick && this.cb.onStick(axis, x, y);
  }

  // Emit an arbitrary HID bit for a control that has no static bit mapping
  // (joystick L3/R3 click, touchpad click).
  _emitBit(b, bit, down) {
    if (down) {
      this.controlActive.set(b.id, true);
      this.cb.onButtonDown && this.cb.onButtonDown(b.id, bit);
    } else {
      this.controlActive.set(b.id, false);
      this.cb.onButtonUp && this.cb.onButtonUp(b.id, bit);
    }
    this.render();
  }

  // Double-tap detection (mirrors JoystickView / touchpad doubleClickEnable).
  _maybeDoubleTap(b, px, py, allowed, dist, onDouble) {
    if (!b.doubleClickEnable) return;
    const now = performance.now();
    if (b._tapTime && now - b._tapTime < 300) {
      if (allowed && Math.hypot(px - b._tapX, py - b._tapY) < dist) {
        b._tapTime = 0;
        b._doubleClick = true;
        onDouble();
        return;
      }
      b._tapTime = now;
      b._tapX = px;
      b._tapY = py;
    } else {
      b._tapTime = now;
      b._tapX = px;
      b._tapY = py;
    }
  }

  _beginStick(e, b, px, py, follow) {
    this._maybeDoubleTap(b, px, py, true, Infinity, () => {
      this._emitBit(b, b.axis === "left" ? BIT.L3 : BIT.R3, true);
    });
    const info = {
      id: b.id, kind: "stick", axis: b.axis, b,
      follow: !!follow, cx: px, cy: py, pointerId: e.pointerId,
    };
    this._pointers.set(e.pointerId, info);
    if (b.touchpadMode) {
      // 触摸板模式：DOWN 只记录起点，摇杆保持回中，等 MOVE 产生速度后再偏移。
      info.vel = { lx: px, ly: py, lt: 0, vx: 0, vy: 0, interval: 0, timer: 0, seeded: false, moved: false };
      const r = this.gridRect(b);
      info.b._followDx = follow ? px - (r.x + r.w / 2) : 0;
      info.b._followDy = follow ? py - (r.y + r.h / 2) : 0;
      this._setStick(b.axis, 0, 0);
      this.controlActive.set(b.id, false);
      this.render();
      return;
    }
    this._updateStick(e.pointerId, px, py, e);
  }

  _updateStick(pointerId, px, py, e) {
    const info = this._pointers.get(pointerId);
    if (!info) return;
    if (info.b.touchpadMode) {
      this._updateStickVelocity(info, px, py, e);
      return;
    }
    const r = this.gridRect(info.b);
    // Follow-area mode re-centres the stick at the touch point.
    const cx = info.follow ? info.cx : r.x + r.w / 2;
    const cy = info.follow ? info.cy : r.y + r.h / 2;
    const rad = Math.min(r.w, r.h) / 2;
    let dx = (px - cx) / rad;
    let dy = (py - cy) / rad;
    const len = Math.hypot(dx, dy);
    if (len > 1) {
      dx /= len;
      dy /= len;
    }
    const sens = (info.b.joystickSensitivity || 100) / 100;
    let nx = dx * sens;
    let ny = dy * sens;
    const dz = (info.b.deadZone || 0) / 100;
    if (dz > 0) {
      const l2 = Math.hypot(nx, ny);
      if (l2 < dz) {
        nx = 0;
        ny = 0;
      } else {
        const scale = (l2 - dz) / (1 - dz) / l2;
        nx *= scale;
        ny *= scale;
      }
    }
    const clamp = (v) => Math.max(-1, Math.min(1, v));
    this.controlActive.set(info.id, Math.hypot(nx, ny) > 0.001);
    // Follow-area mode shifts the base + cap to the touch point (JoystickView
    // effectiveCenter), mirroring the dpad / keypad follow behaviour.
    info.b._followDx = info.follow ? info.cx - (r.x + r.w / 2) : 0;
    info.b._followDy = info.follow ? info.cy - (r.y + r.h / 2) : 0;
    // Y is emitted screen-down-positive to match the host / Android convention
    // (HID stick Y: min = up, max = down).
    this._setStick(info.axis, Math.round(clamp(nx) * 32767), Math.round(clamp(ny) * 32767));
    this.render();
  }

  // 触摸板（速度）模式：把批处理历史样本（getCoalescedEvents）算出的瞬时速度做
  // EMA 平滑后映射为摇杆偏移，速度归零（看门狗触发）时摇杆回中。
  // 镜像 EmaVelocityTracker / JoystickView.sampleTouchVelocity。
  _updateStickVelocity(info, px, py, e) {
    const vel = info.vel;
    if (!vel) return;
    vel.moved = false;
    for (const s of this._coalescedSamples(e, px, py)) {
      if (vel.lt <= 0) {
        // 首个样本只做种子：DOWN→首次 MOVE 的间隔不代表触控采样间隔。
        vel.lx = s.x;
        vel.ly = s.y;
        vel.lt = s.t;
        continue;
      }
      const dt = s.t - vel.lt;
      if (dt <= 0) continue;
      const instVx = (s.x - vel.lx) / dt;
      const instVy = (s.y - vel.ly) / dt;
      if (!vel.seeded) {
        // 首个间隔用于播种：不做平滑，避免丢掉起始位移。
        vel.vx = instVx;
        vel.vy = instVy;
        vel.seeded = true;
        if (dt <= EMA_RESEED_GAP_MS) vel.interval = Math.max(MIN_SAMPLE_INTERVAL_MS, Math.min(50, dt));
      } else if (dt > EMA_RESEED_GAP_MS) {
        // 停顿过久：平滑值已无参考意义，直接用当前间隔重新播种。
        vel.vx = instVx;
        vel.vy = instVy;
      } else {
        vel.vx += (instVx - vel.vx) * EMA_ALPHA;
        vel.vy += (instVy - vel.vy) * EMA_ALPHA;
        const clamped = Math.max(MIN_SAMPLE_INTERVAL_MS, Math.min(50, dt));
        vel.interval = vel.interval <= 0 ? clamped : vel.interval * 0.75 + clamped * 0.25;
      }
      vel.lx = s.x;
      vel.ly = s.y;
      vel.lt = s.t;
      vel.moved = true;
    }
    if (!vel.moved) return;
    this._applyStickVelocity(info.b, vel.vx, vel.vy);
    this._scheduleVelocityReset(info);
  }

  // PointerEvent 的批处理历史样本（getCoalescedEvents）+ 当前样本；坐标统一为布局坐标。
  _coalescedSamples(e, px, py) {
    const out = [];
    const coalesced = e && typeof e.getCoalescedEvents === "function" ? e.getCoalescedEvents() : null;
    if (coalesced) {
      for (const ev of coalesced) {
        const p = this._pos(ev);
        out.push({ x: p.px, y: p.py, t: Number.isFinite(ev.timeStamp) ? ev.timeStamp : performance.now() });
      }
    }
    out.push({ x: px, y: py, t: e && Number.isFinite(e.timeStamp) ? e.timeStamp : performance.now() });
    return out;
  }

  // JoystickView.applyVelocity：速度矢量 → 归一化幅度 → 死区/反死区/曲线 → 摇杆输出。
  _applyStickVelocity(b, vx, vy) {
    const r = this.gridRect(b);
    const baseR = Math.min(r.w, r.h) / 2;
    const knobR = baseR * 0.32;
    const maxD = baseR - knobR;
    if (maxD <= 0) {
      this._setStick(b.axis, 0, 0);
      return;
    }
    const speed = Math.hypot(vx, vy);
    const sens = (b.joystickSensitivity || 100) / 100;
    const rawNorm = ((speed * VELOCITY_TIME_CONSTANT_MS) / maxD) * sens;
    const normalized = Math.min(1, rawNorm);
    const afterCurve = this._shapeNormalized(b, normalized);
    const dirScale = speed > 0 ? afterCurve / speed : 0;
    const nx = vx * dirScale;
    const ny = vy * dirScale;
    const clamp = (v) => Math.max(-1, Math.min(1, v));
    this.controlActive.set(b.id, Math.hypot(nx, ny) > 0.001);
    // Y is emitted screen-down-positive to match the host / Android convention
    // (HID stick Y: min = up, max = down).
    this._setStick(b.axis, Math.round(clamp(nx) * 32767), Math.round(clamp(ny) * 32767));
    this.render();
  }

  // 死区 → 反死区 → 灵敏度曲线，输入/输出均为归一化量 (0..1)。
  _shapeNormalized(b, normalized) {
    const dz = Math.max(0, Math.min(0.99, (b.deadZone || 0) / 100));
    const afterDeadZone = normalized <= dz ? 0 : (normalized - dz) / (1 - dz);
    const rdz = Math.max(0, Math.min(0.99, (b.reverseDeadZone || 0) / 100));
    // 死区优先：死区内恒输出 0，反死区只在死区之外抬升下限。
    const afterReverseDeadZone = afterDeadZone === 0 ? 0 : afterDeadZone * (1 - rdz) + rdz;
    return this._evaluateCurve(b.sensitivityCurve, afterReverseDeadZone);
  }

  // SensitivityCurve.evaluate 的网页兜底：曲线编辑器在网页版未提供，曲线恒为 null，
  // 此时为恒等映射；若将来携带曲线则按隐式锚点 (0,0)/(1,1) 做分段线性插值。
  _evaluateCurve(curve, t) {
    const x = Math.max(0, Math.min(1, t));
    if (!curve || curve.length < 2) return x;
    const pts = [[0, 0]];
    for (let i = 0; i + 1 < curve.length; i += 2) {
      const cx = Math.max(0, Math.min(1, curve[i]));
      const cy = Math.max(0, Math.min(1, curve[i + 1]));
      if (cx > 1e-6 && cx < 1 - 1e-6) pts.push([cx, cy]);
    }
    pts.push([1, 1]);
    pts.sort((a, c) => a[0] - c[0]);
    let prev = pts[0];
    for (let i = 1; i < pts.length; i++) {
      const cur = pts[i];
      if (x <= cur[0]) {
        const span = cur[0] - prev[0] || 1;
        return prev[1] + (cur[1] - prev[1]) * ((x - prev[0]) / span);
      }
      prev = cur;
    }
    return 1;
  }

  // 手指停下后不再有 MOVE，按估计的采样间隔触发一次归零，让摇杆回中。
  _scheduleVelocityReset(info) {
    const vel = info.vel;
    if (!vel) return;
    if (vel.timer) clearTimeout(vel.timer);
    const interval = vel.interval > 0 ? vel.interval : FALLBACK_SAMPLE_INTERVAL_MS;
    const delay = Math.max(1, interval * VELOCITY_RESET_INTERVALS);
    vel.timer = setTimeout(() => {
      vel.timer = 0;
      // 指针已抬起或不是同一次触摸时不再归零。
      if (this._pointers.get(info.pointerId) !== info) return;
      vel.vx = 0;
      vel.vy = 0;
      // 下一帧样本重新播种速度，避免从 0 缓慢爬升。
      vel.seeded = false;
      this._applyStickVelocity(info.b, 0, 0);
    }, delay);
  }

  _updateDpad(pointerId, px, py) {
    const info = this._pointers.get(pointerId);
    if (!info) return;
    const r = this.gridRect(info.b);
    const ccx = r.x + r.w / 2;
    const ccy = r.y + r.h / 2;
    const cx = info.follow ? info.cx : ccx;
    const cy = info.follow ? info.cy : ccy;
    const radius = Math.min(r.w, r.h) / 2;
    const nx = (px - cx) / radius;
    const ny = (py - cy) / radius;
    const p = centerSizeOf(info.b) / 100;
    let hat = 0;
    if ((info.b.centerShape || "SQUARE") === "CIRCLE") {
      if (Math.hypot(nx, ny) > p) {
        let deg = (Math.atan2(ny, nx) * 180) / Math.PI;
        if (deg < 0) deg += 360;
        const k = Math.floor((((deg + 22.5) % 360) / 45)) % 8;
        hat = DPAD_SECTOR_BITS[k];
      }
    } else if (Math.abs(nx) > p || Math.abs(ny) > p) {
      if (nx > p) hat |= DPAD.RIGHT;
      if (nx < -p) hat |= DPAD.LEFT;
      if (ny > p) hat |= DPAD.DOWN;
      if (ny < -p) hat |= DPAD.UP;
    }
    info.b._hat = hat;
    info.b._followDx = info.follow ? info.cx - ccx : 0;
    info.b._followDy = info.follow ? info.cy - ccy : 0;
    this.controlActive.set(info.id, hat !== 0);
    this.cb.onDpad && this.cb.onDpad(hat);
    this.render();
  }

  // CustomKeypadView.directionAt — resolves a point (relative to the effective
  // centre) to a region index; 4 = centre.
  _keypadRegionAt(b, dx, dy, r) {
    const side = Math.min(r.w, r.h);
    const radius = side / 2;
    const centerHalf = (radius * centerSizeOf(b)) / 100;
    if ((b.centerShape || "SQUARE") === "CIRCLE") {
      if (dx * dx + dy * dy <= centerHalf * centerHalf) return 4;
      let deg = (Math.atan2(dy, dx) * 180) / Math.PI;
      if (deg < 0) deg += 360;
      if (b.keypadEightWay) {
        const k = Math.floor((((deg + 22.5) % 360) / 45)) % 8;
        return KEYPAD_CIRCLE_SECTOR_8[k];
      }
      const k = Math.floor((((deg + 45) % 360) / 90)) % 4;
      return KEYPAD_CIRCLE_SECTOR_4[k];
    }
    if (b.keypadEightWay) {
      const col = dx < -centerHalf ? 0 : dx > centerHalf ? 2 : 1;
      const row = dy < -centerHalf ? 0 : dy > centerHalf ? 2 : 1;
      const region = KEYPAD_GRID_REGION[row][col];
      return region < 0 ? 4 : region;
    }
    if (Math.abs(dx) <= centerHalf && Math.abs(dy) <= centerHalf) return 4;
    if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? 3 : 2;
    return dy < 0 ? 0 : 1;
  }

  _updateKeypad(pointerId, px, py) {
    const info = this._pointers.get(pointerId);
    if (!info) return;
    const b = info.b;
    const r = this.gridRect(b);
    const ccx = r.x + r.w / 2;
    const ccy = r.y + r.h / 2;
    const cx = info.follow ? info.cx : ccx;
    const cy = info.follow ? info.cy : ccy;
    b._followDx = info.follow ? info.cx - ccx : 0;
    b._followDy = info.follow ? info.cy - ccy : 0;
    const region = this._keypadRegionAt(b, px - cx, py - cy, r);
    if (b._region !== region) {
      if (b._region != null && b._region !== 4) {
        this._emitKeypadRegion(b, b._region, false);
      }
      b._region = region;
      if (region !== 4) this._emitKeypadRegion(b, region, true);
    }
    this.controlActive.set(b.id, region !== 4);
    this.render();
  }

  // Emit the bits bound to a keypad region (region 4 = the double-tap centre).
  _emitKeypadRegion(b, region, down) {
    const bits = (b.keypadBits && b.keypadBits[region]) || [];
    this.cb.onCustomBits && this.cb.onCustomBits(bits, down, {
      source: "keypad:" + b.id + "#" + region,
      timing: (b.keypadComboTimings && b.keypadComboTimings[region]) || "SIMULTANEOUS",
      intervalMs: (b.keypadComboIntervals && b.keypadComboIntervals[region]) || 16,
    });
  }

  // CustomKeypadView.handleDown — the centre fires only on a double-tap; a
  // single tap in the centre is ignored.
  _keypadDown(e, b, px, py, follow) {
    b._keypadCenter = false;
    const r = this.gridRect(b);
    const region = this._keypadRegionAt(b, px - (r.x + r.w / 2), py - (r.y + r.h / 2), r);
    if (region === 4) {
      this._maybeDoubleTap(b, px, py, true, 32, () => {
        b._keypadCenter = true;
        this._emitKeypadRegion(b, 4, true);
      });
    }
    const info = { id: b.id, kind: "keypad", b };
    if (follow) { info.follow = true; info.cx = px; info.cy = py; }
    this._pointers.set(e.pointerId, info);
    this._updateKeypad(e.pointerId, px, py);
  }

  // LinearTriggerView.onTouchEvent — value is the drag distance from the touch
  // point along the slide axis over the travel distance (travelDistance × cell).
  // 滑杆偏移：手指坐标 → 夹取在行程内的偏移（负值表示向上/向左）。
  _triggerOffset(b, info, travelPx, x, y) {
    const dx = x - info.startX;
    const dy = y - info.startY;
    const base = info.baseOffset || 0;
    switch (b.slideDirection) {
      case "UP": return Math.max(-travelPx, Math.min(0, base + dy));
      case "LEFT": return Math.max(-travelPx, Math.min(0, base + dx));
      case "RIGHT": return Math.max(0, Math.min(travelPx, base + dx));
      default: return Math.max(0, Math.min(travelPx, base + dy)); // DOWN
    }
  }

  // 一维 history + EMA 速度（镜像 EmaVelocityTracker.add，单位 px/ms）。
  _emaAdd1D(store, x, t) {
    if (!store.lt) {
      store.lx = x;
      store.lt = t;
      return;
    }
    const dt = t - store.lt;
    if (dt <= 0) return;
    const inst = (x - store.lx) / dt;
    if (!store.seeded) {
      // 首个间隔只用于播种：不做平滑，避免丢掉起始位移。
      store.v = inst;
      store.seeded = true;
      if (dt <= EMA_RESEED_GAP_MS) store.interval = Math.max(MIN_SAMPLE_INTERVAL_MS, Math.min(50, dt));
    } else if (dt > EMA_RESEED_GAP_MS) {
      store.v = inst;
    } else {
      store.v += (inst - store.v) * EMA_ALPHA;
      const clamped = Math.max(MIN_SAMPLE_INTERVAL_MS, Math.min(50, dt));
      store.interval = store.interval <= 0 ? clamped : store.interval * 0.75 + clamped * 0.25;
    }
    store.lx = x;
    store.lt = t;
  }

  _updateTrigger(pointerId, px, py, e) {
    const info = this._pointers.get(pointerId);
    if (!info) return;
    const b = info.b;
    const { cell } = this._layout;
    const travelPx = Math.max(1, (b.travelDistance || 10) * cell);

    const offset = this._triggerOffset(b, info, travelPx, px, py);
    b._triggerOffset = offset;

    // 快速响应模式：history + EMA 速度外推下一帧行程（镜像 EmaVelocityTracker）。
    let effective = Math.abs(offset);
    if (b.prediction) {
      const ema = info.ema;
      for (const s of this._coalescedSamples(e, px, py)) {
        this._emaAdd1D(ema, this._triggerOffset(b, info, travelPx, s.x, s.y), s.t);
      }
      const frame = ema.interval > 0 ? ema.interval : PREDICTION_FRAME_MS;
      effective = Math.abs(offset + ema.v * frame);
    }
    const normalized = Math.min(1, effective / travelPx);
    const value = normalized < 0.004 ? 0 : Math.round(normalized * 255);
    const prev = b._triggerValue || 0;
    b._triggerValue = value;

    // Publish the analog value first so the frame sent on a bit transition
    // already carries the matching analog level.
    this.cb.onTrigger && this.cb.onTrigger(b._which, value);

    // Mirror LinearTriggerView: 0 → >0 presses the digital LT/RT bit, >0 → 0
    // releases it. The bit must track value==0 exactly so the host never sees
    // analog=0 with bit=1 (which it reads as a fully-pressed digital trigger).
    if (prev === 0 && value > 0) {
      this._pressControl(b);
      b._triggerDown = true;
    } else if (prev > 0 && value === 0) {
      this._releaseControl(b);
      b._triggerDown = false;
      this.controlActive.set(b.id, true);
    }

    this.controlActive.set(b.id, true);
    this.render();
  }

  _updateTouchpad(b) {
    // 扩展触摸范围: with followAreaEnabled the coordinates are normalised over
    // the extended rectangle instead of the touchpad control itself.
    const r =
      b.followAreaEnabled && b.followAreaW > 0 && b.followAreaH > 0
        ? this._followAreaRectPx(b)
        : this.gridRect(b);
    const points = [];
    let idx = 0;
    for (const [, info] of this._pointers) {
      if (info.kind !== "touchpad" || info.b !== b) continue;
      // pointer position is tracked via clientX/clientY stored below
      points.push({ id: idx, x: info.px, y: info.py, active: true });
      idx++;
    }
    const clampX = (v) => Math.max(0, Math.min(1919, Math.round(v)));
    const clampY = (v) => Math.max(0, Math.min(942, Math.round(v)));
    const touches = points.map((p) => ({
      id: p.id,
      x: clampX(((p.x - r.x) / r.w) * 1919),
      y: clampY(((p.y - r.y) / r.h) * 942),
      active: true,
    }));
    // Draw markers relative to the control's own box, even when the mapping
    // region is the larger extended rectangle.
    const ctrl = this.gridRect(b);
    b._touchPoints = points.map((p) => ({
      nx: (p.x - ctrl.x) / ctrl.w,
      ny: (p.y - ctrl.y) / ctrl.h,
    }));
    this.cb.onTouchpad && this.cb.onTouchpad(touches);
    this.render();
  }

  // ── Mousepad gestures (mirrors MainActivityControls.attachMousepadGestures) ──
  _mpBitForAction(action) {
    switch (action) {
      case "LEFT_CLICK": return 1;
      case "RIGHT_CLICK": return 2;
      case "MIDDLE_CLICK": return 4;
      default: return 0;
    }
  }

  _mpDragBitForAction(action) {
    switch (action) {
      case "LEFT_DRAG": return 1;
      case "RIGHT_DRAG": return 2;
      case "MIDDLE_DRAG": return 4;
      default: return 0;
    }
  }

  _mpPress(bit) {
    if (bit) this.cb.onMouseButton && this.cb.onMouseButton(bit, true);
  }

  _mpRelease(bit) {
    if (bit) this.cb.onMouseButton && this.cb.onMouseButton(bit, false);
  }

  _mpReleaseAll(b) {
    if (b._mpActiveDragBit) {
      this._mpRelease(b._mpActiveDragBit);
      b._mpActiveDragBit = 0;
    }
  }

  _mpClearPending(b, release = false) {
    const pending = b._mpPending;
    if (!pending) return;
    if (pending.timer) clearTimeout(pending.timer);
    if (release) this._mpRelease(pending.bit);
    b._mpPending = null;
  }

  /** A short simulated click. When track is true the release can be cancelled
   *  by the second tap so a drag starts without an intervening button-up. */
  _mpClick(b, bit, holdMs, track) {
    if (!bit) return;
    this._mpPress(bit);
    const pending = { bit, timer: 0 };
    pending.timer = setTimeout(() => {
      this._mpRelease(bit);
      if (b._mpPending === pending) b._mpPending = null;
    }, holdMs);
    if (track) b._mpPending = pending;
  }

  _mpApplyTap(b, action, holdMs = MOUSEPAD_CLICK_HOLD_MS, track = false) {
    this._mpClick(b, this._mpBitForAction(action), holdMs, track);
  }

  _mpRotateDelta(b, dx, dy) {
    const rot = ((b.rotation || 0) % 360 + 360) % 360;
    if (rot === 90) return { dx: dy, dy: -dx };
    if (rot === 180) return { dx: -dx, dy: -dy };
    if (rot === 270) return { dx: -dy, dy: dx };
    return { dx, dy };
  }

  _mpApplySwipe(b, action, dx, dy, speed) {
    if (action === "NONE" || !action) return;
    const v = this._mpRotateDelta(b, dx, dy);
    if (action === "MOVE_CURSOR") {
      this.cb.onMouseMove && this.cb.onMouseMove(v.dx, v.dy, speed, b);
    } else if (action === "SCROLL") {
      this.cb.onMouseScroll && this.cb.onMouseScroll(v.dx, v.dy, b);
    } else {
      const bit = this._mpDragBitForAction(action);
      if (bit) {
        if (!b._mpActiveDragBit) {
          b._mpActiveDragBit = bit;
          this._mpPress(bit);
        }
        this.cb.onMouseMove && this.cb.onMouseMove(v.dx, v.dy, speed, b);
      }
    }
  }

  _mpSwipeActionFor(b, count) {
    const g = b._mpGesture;
    if (count === 1) return g && g.doubleTapArmed ? b.doubleTapDragAction : b.oneFingerSwipeAction;
    if (count === 2) return b.twoFingerSwipeAction;
    if (count === 3) return b.threeFingerSwipeAction;
    return "NONE";
  }

  _mpTapActionFor(b, count) {
    if (count === 1) return b.singleTapAction;
    if (count === 2) return b.twoFingerTapAction;
    if (count === 3) return b.threeFingerTapAction;
    return "NONE";
  }

  _mpPointerCount(b) {
    let n = 0;
    for (const info of this._pointers.values()) {
      if (info.kind === "mousepad" && info.b === b) n++;
    }
    return n;
  }

  /** Advance one pointer's EMA velocity tracker over coalesced samples. */
  _mpTrackPointer(info, e) {
    const samples = typeof e.getCoalescedEvents === "function" ? e.getCoalescedEvents() : [];
    const list = samples && samples.length ? samples : [e];
    const tr = info.tracker;
    let dx = 0;
    let dy = 0;
    let vx = 0;
    let vy = 0;
    for (const sample of list) {
      const p = this._pos(sample);
      const time = Number.isFinite(sample.timeStamp) ? sample.timeStamp : performance.now();
      if (!tr.hasSample) {
        tr.lastX = p.px;
        tr.lastY = p.py;
        tr.lastTime = time;
        tr.hasSample = true;
        continue;
      }
      const dt = time - tr.lastTime;
      if (dt <= 0) continue;
      const sdx = p.px - tr.lastX;
      const sdy = p.py - tr.lastY;
      const ivx = sdx / dt;
      const ivy = sdy / dt;
      if (!tr.seeded) {
        tr.vx = ivx;
        tr.vy = ivy;
        tr.seeded = true;
      } else if (dt > MOUSEPAD_RESEED_GAP_MS) {
        tr.vx = ivx;
        tr.vy = ivy;
      } else {
        tr.vx += (ivx - tr.vx) * MOUSEPAD_EMA_ALPHA;
        tr.vy += (ivy - tr.vy) * MOUSEPAD_EMA_ALPHA;
      }
      tr.pendingDx += tr.vx * dt;
      tr.pendingDy += tr.vy * dt;
      tr.lastX = p.px;
      tr.lastY = p.py;
      tr.lastTime = time;
      dx += tr.pendingDx;
      dy += tr.pendingDy;
      tr.pendingDx = 0;
      tr.pendingDy = 0;
      vx = tr.vx;
      vy = tr.vy;
    }
    info.px = tr.lastX;
    info.py = tr.lastY;
    return { dx, dy, vx, vy };
  }

  _mpDown(b, e, px, py) {
    const now = Number.isFinite(e.timeStamp) ? e.timeStamp : performance.now();
    const count = this._mpPointerCount(b) + 1;
    if (!b._mpGesture) {
      b._mpGesture = { maxPointers: 1, moved: false, travel: 0, doubleTapArmed: false };
      // The first pointer starts a fresh gesture, matching ACTION_DOWN: drop the
      // fractional cursor/scroll accumulators that the previous gesture left.
      this.cb.onMouseGestureStart && this.cb.onMouseGestureStart(b);
      const lastTap = b._mpLastTapUpTime || 0;
      b._mpGesture.doubleTapArmed =
        b.doubleTapDragAction !== "NONE" && lastTap > 0 && now - lastTap < MOUSEPAD_DOUBLE_TAP_WINDOW_MS;
      b._mpLastTapUpTime = 0;
      if (b._mpGesture.doubleTapArmed) {
        // Cancel the previous tap's delayed release so the drag key stays down.
        const pending = b._mpPending;
        this._mpClearPending(b);
        const dragBit = this._mpDragBitForAction(b.doubleTapDragAction);
        if (pending && pending.bit !== dragBit) this._mpRelease(pending.bit);
        if (dragBit) {
          b._mpActiveDragBit = dragBit;
          this._mpPress(dragBit);
        }
        this.controlActive.set(b.id, true);
        this.cb.onHaptic && this.cb.onHaptic(true);
      }
    } else {
      const g = b._mpGesture;
      if (count > g.maxPointers) g.maxPointers = count;
      // Mirrors ACTION_POINTER_DOWN: drop the fractional scroll leftovers so
      // the two-finger gesture starts from a clean unit boundary.
      this.cb.onMouseExtraPointer && this.cb.onMouseExtraPointer(b);
      // A second finger cancels the double-tap-drag hold.
      if (g.doubleTapArmed) {
        this._mpReleaseAll(b);
        g.doubleTapArmed = false;
        this.controlActive.set(b.id, false);
        this.cb.onHaptic && this.cb.onHaptic(false);
      }
    }

    this._pointers.set(e.pointerId, {
      id: b.id,
      kind: "mousepad",
      b,
      px,
      py,
      downTime: now,
      // Seed at the down point like EmaVelocityTracker.start(), so the first
      // move already yields velocity + displacement instead of being dropped.
      tracker: {
        lastX: px, lastY: py, lastTime: now, hasSample: true,
        vx: 0, vy: 0, pendingDx: 0, pendingDy: 0, seeded: false,
      },
    });
    this.render();
  }

  _mpMove(b, info, e) {
    const g = b._mpGesture;
    if (!g) return;
    const count = this._mpPointerCount(b);
    if (!count) return;
    const d = this._mpTrackPointer(info, e);
    // MotionEvent reports every pointer in one event; a browser pointermove
    // carries one pointer, so average over the simultaneous pointer count.
    const dx = d.dx / count;
    const dy = d.dy / count;
    // Velocity is averaged the same way: Android sums every pointer's EMA
    // velocity in the move event, then divides by the pointer count.
    let sumVx = 0;
    let sumVy = 0;
    for (const p of this._pointers.values()) {
      if (p.kind !== "mousepad" || p.b !== b) continue;
      sumVx += p.tracker.vx;
      sumVy += p.tracker.vy;
    }
    const vx = sumVx / count;
    const vy = sumVy / count;
    g.travel += Math.abs(dx) + Math.abs(dy);
    if (!g.moved && g.travel > b.mouseMoveSlop) g.moved = true;
    if (g.moved) {
      const speed = Math.hypot(vx, vy);
      const action = this._mpSwipeActionFor(b, count);
      this._mpApplySwipe(b, action, dx, dy, speed);
    }
    this.render();
  }

  _mpUp(b, info, e, cancel = false) {
    const g = b._mpGesture;
    const now = Number.isFinite(e.timeStamp) ? e.timeStamp : performance.now();
    if (!cancel) {
      const dur = now - info.downTime;
      const wasTap = g && !g.moved && dur < MOUSEPAD_TAP_THRESHOLD_MS;
      this._mpReleaseAll(b);
      if (g && g.doubleTapArmed) {
        this.controlActive.set(b.id, false);
        this.cb.onHaptic && this.cb.onHaptic(false);
      }
      if (wasTap && g) {
        if (g.maxPointers === 1) {
          if (g.doubleTapArmed) {
            // Second tap: emit an immediate click, no further drag handoff.
            this._mpApplyTap(b, b.singleTapAction);
          } else {
            b._mpLastTapUpTime = now;
            const track = b.doubleTapDragAction !== "NONE";
            this._mpApplyTap(
              b,
              b.singleTapAction,
              track ? MOUSEPAD_HOLD_FOR_DOUBLE_MS : MOUSEPAD_CLICK_HOLD_MS,
              track
            );
          }
        } else {
          this._mpApplyTap(b, this._mpTapActionFor(b, g.maxPointers));
        }
      }
    } else {
      // ACTION_CANCEL: drop any latched drag / pending click without a tap.
      this._mpClearPending(b, true);
      this._mpReleaseAll(b);
      if (g && g.doubleTapArmed) {
        this.controlActive.set(b.id, false);
        this.cb.onHaptic && this.cb.onHaptic(false);
      }
      for (const [pid, p] of [...this._pointers]) {
        if (p.kind === "mousepad" && p.b === b) this._pointers.delete(pid);
      }
    }
    b._mpGesture = null;
    this.render();
  }

  // ── edit mode ──
  _editDown(e, px, py) {
    try { this.canvas.setPointerCapture(e.pointerId); } catch {}
    // Follow-area adjust session: drag/resize the extended-range rectangle.
    if (this._adjustingFollowArea && this.selectedId) {
      const b = this.getButton(this.selectedId);
      if (b && b.followAreaEnabled) {
        const fr = this._followAreaRectPx(b);
        const hs = HANDLE_SIZE_DP * 2;
        if (px >= fr.x + fr.w - hs && px <= fr.x + fr.w && py >= fr.y + fr.h - hs && py <= fr.y + fr.h) {
          this._edit = { pointerId: e.pointerId, mode: "farearesize", b, start: { x: b.followAreaX, y: b.followAreaY, w: b.followAreaW, h: b.followAreaH }, gx: px, gy: py };
          return;
        }
        if (px >= fr.x && px <= fr.x + fr.w && py >= fr.y && py <= fr.y + fr.h) {
          this._edit = { pointerId: e.pointerId, mode: "fareamove", b, start: { x: b.followAreaX, y: b.followAreaY, w: b.followAreaW, h: b.followAreaH }, gx: px, gy: py };
          return;
        }
        return;
      }
    }
    if (this.selectedId && this._hitHandle(px, py)) {
      const b = this.getButton(this.selectedId);
      this._edit = { pointerId: e.pointerId, mode: "resize", b, start: { ...b }, gx: px, gy: py };
      this._animateGridTo(1);
      return;
    }
    const hit = this._hitTest(px, py, false);
    if (hit) {
      this.selectedId = hit.id;
      this._edit = { pointerId: e.pointerId, mode: "move", b: hit, start: { x: hit.x, y: hit.y }, gx: px, gy: py };
      this._animateGridTo(1);
    } else {
      this.selectedId = null;
      this._edit = null;
      this.cb.onSelect && this.cb.onSelect(null);
    }
    this.render();
    if (hit) this.cb.onSelect && this.cb.onSelect(hit.id);
  }

  _editMove(e) {
    if (!this._edit || this._edit.pointerId !== e.pointerId) return;
    const { loc } = { loc: this._layout };
    const cell = loc.cell;
    const { px, py } = this._pos(e);
    const dgx = Math.round((px - this._edit.gx) / cell);
    const dgy = Math.round((py - this._edit.gy) / cell);
    const b = this._edit.b;
    if (this._edit.mode === "move") {
      const nx = this._edit.start.x + dgx;
      const ny = this._edit.start.y + dgy;
      const [vw] = onScreenGridSize(b);
      b.x = Math.max(0, Math.min(GRID_COLS - vw, nx));
      b.y = Math.max(0, ny);
    } else if (this._edit.mode === "fareamove") {
      b.followAreaX = Math.max(0, this._edit.start.x + dgx);
      b.followAreaY = Math.max(0, this._edit.start.y + dgy);
    } else if (this._edit.mode === "farearesize") {
      b.followAreaW = Math.max(3, this._edit.start.w + dgx);
      b.followAreaH = Math.max(3, this._edit.start.h + dgy);
    } else {
      // Resize handles track the visual box, which is swapped for 90/270°.
      const swapped = !b.lockAspect && (b.rotation === 90 || b.rotation === 270);
      if (b.lockAspect) {
        const size = Math.max(3, this._edit.start.w + dgx);
        b.w = size;
        b.h = size;
      } else if (swapped) {
        // Visual W comes from h, visual H from w.
        b.h = Math.max(3, this._edit.start.h + dgx);
        b.w = Math.max(3, this._edit.start.w + dgy);
      } else {
        b.w = Math.max(3, this._edit.start.w + dgx);
        b.h = Math.max(3, this._edit.start.h + dgy);
      }
    }
    this.cb.onEditChanged && this.cb.onEditChanged();
    this.render();
  }

  _editUp(e) {
    if (this._edit && this._edit.pointerId === e.pointerId) {
      this._edit = null;
      this._animateGridTo(0);
      this.cb.onEditEnd && this.cb.onEditEnd();
    }
  }
}
