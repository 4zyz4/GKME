// GKME-Web — control metadata, HID codes and label maps.
// Mirrors com.zyz4.gkme.model.GamepadState / MainActivityControls.

export const BIT = {
  A: 0x00001,
  B: 0x00002,
  X: 0x00004,
  Y: 0x00008,
  LB: 0x00010,
  RB: 0x00020,
  LT: 0x00040,
  RT: 0x00080,
  SELECT: 0x00100,
  START: 0x00200,
  L3: 0x00400,
  R3: 0x00800,
  DPAD_BIT_UP: 0x01000,
  DPAD_BIT_DOWN: 0x02000,
  DPAD_BIT_LEFT: 0x04000,
  DPAD_BIT_RIGHT: 0x08000,
  HOME: 0x10000,
  TOUCHPAD_CLICK: 0x20000,
  MIC_MUTE: 0x40000,
};

export const DPAD = { UP: 1, DOWN: 2, LEFT: 4, RIGHT: 8 };

// HID keyboard usages (see MainActivityControls.Kb).
export const Kb = {
  LCtrl: 0xe0, LShift: 0xe1, LAlt: 0xe2, LGui: 0xe3,
  RCtrl: 0xe4, RShift: 0xe5, RAlt: 0xe6, RGui: 0xe7,
  F1: 0x3a, F2: 0x3b, F3: 0x3c, F4: 0x3d, F5: 0x3e, F6: 0x3f,
  F7: 0x40, F8: 0x41, F9: 0x42, F10: 0x43, F11: 0x44, F12: 0x45,
  KeyA: 0x04, KeyB: 0x05, KeyC: 0x06, KeyD: 0x07, KeyE: 0x08, KeyF: 0x09,
  KeyG: 0x0a, KeyH: 0x0b, KeyI: 0x0c, KeyJ: 0x0d, KeyK: 0x0e, KeyL: 0x0f,
  KeyM: 0x10, KeyN: 0x11, KeyO: 0x12, KeyP: 0x13, KeyQ: 0x14, KeyR: 0x15,
  KeyS: 0x16, KeyT: 0x17, KeyU: 0x18, KeyV: 0x19, KeyW: 0x1a, KeyX: 0x1b,
  KeyY: 0x1c, KeyZ: 0x1d,
  Key1: 0x1e, Key2: 0x1f, Key3: 0x20, Key4: 0x21, Key5: 0x22, Key6: 0x23,
  Key7: 0x24, Key8: 0x25, Key9: 0x26, Key0: 0x27,
  Enter: 0x28, Esc: 0x29, Backspace: 0x2a, Tab: 0x2b, Space: 0x2c,
  Minus: 0x2d, Equal: 0x2e, LBracket: 0x2f, RBracket: 0x30, Backslash: 0x31,
  Semicolon: 0x33, Apostrophe: 0x34, Grave: 0x35, Comma: 0x36, Dot: 0x37,
  Slash: 0x38, CapsLock: 0x39,
  Delete: 0x4c, Insert: 0x49, Home: 0x4a, End: 0x4d, PageUp: 0x4b, PageDown: 0x4e,
  ArrowRight: 0x4f, ArrowLeft: 0x50, ArrowDown: 0x51, ArrowUp: 0x52,
  Menu: 0x65,
};

export const MODIFIER_BITS = {
  LCtrl: 0x01, LShift: 0x02, LAlt: 0x04, LGui: 0x08,
  RCtrl: 0x10, RShift: 0x20, RAlt: 0x40, RGui: 0x80,
};

export const KB_MODIFIER_CODES = new Set([0xe0, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7]);

// Scan code → default key cap text (US layout, lower).
export const KB_CAPTIONS = {
  [Kb.LCtrl]: "Ctrl", [Kb.LShift]: "Shift", [Kb.LAlt]: "Alt", [Kb.LGui]: "Win",
  [Kb.RCtrl]: "Ctrl", [Kb.RShift]: "Shift", [Kb.RAlt]: "AltGr", [Kb.RGui]: "Win",
  [Kb.Esc]: "Esc", [Kb.Tab]: "Tab", [Kb.CapsLock]: "Caps", [Kb.Enter]: "Enter",
  [Kb.Backspace]: "Bksp", [Kb.Space]: "Space", [Kb.Delete]: "Del", [Kb.Menu]: "Menu",
  [Kb.Minus]: "-", [Kb.Equal]: "=", [Kb.LBracket]: "[", [Kb.RBracket]: "]",
  [Kb.Backslash]: "\\", [Kb.Semicolon]: ";", [Kb.Apostrophe]: "'", [Kb.Grave]: "`",
  [Kb.Comma]: ",", [Kb.Dot]: ".", [Kb.Slash]: "/",
  [Kb.ArrowUp]: "↑", [Kb.ArrowDown]: "↓", [Kb.ArrowLeft]: "←", [Kb.ArrowRight]: "→",
};

// Scan code → shifted key cap.
export const KB_SHIFT_CAPTIONS = {
  [Kb.Key1]: "!", [Kb.Key2]: "@", [Kb.Key3]: "#", [Kb.Key4]: "$", [Kb.Key5]: "%",
  [Kb.Key6]: "^", [Kb.Key7]: "&", [Kb.Key8]: "*", [Kb.Key9]: "(", [Kb.Key0]: ")",
  [Kb.Minus]: "_", [Kb.Equal]: "+", [Kb.LBracket]: "{", [Kb.RBracket]: "}",
  [Kb.Backslash]: "|", [Kb.Semicolon]: ":", [Kb.Apostrophe]: '"', [Kb.Comma]: "<",
  [Kb.Dot]: ">", [Kb.Slash]: "?", [Kb.Grave]: "~",
};

export function keyboardCaption(keyCode, shift) {
  if (shift && KB_SHIFT_CAPTIONS[keyCode]) return KB_SHIFT_CAPTIONS[keyCode];
  if (KB_CAPTIONS[keyCode]) return KB_CAPTIONS[keyCode];
  // letters / digits / F-keys
  if (keyCode >= Kb.KeyA && keyCode <= Kb.KeyZ)
    return String.fromCharCode(0x41 + (keyCode - Kb.KeyA));
  if (keyCode >= Kb.Key1 && keyCode <= Kb.Key0)
    return String((keyCode - Kb.Key1 + 1) % 10);
  if (keyCode >= Kb.F1 && keyCode <= Kb.F12) return "F" + (keyCode - Kb.F1 + 1);
  return "?";
}

// Control catalogue. `lockAspect` derives from the app's hard-coded tables.
export const CONTROLS = {
  btnA: { name: "手柄：A", bit: BIT.A, lockAspect: true },
  btnB: { name: "手柄：B", bit: BIT.B, lockAspect: true },
  btnX: { name: "手柄：X", bit: BIT.X, lockAspect: true },
  btnY: { name: "手柄：Y", bit: BIT.Y, lockAspect: true },
  btnLB: { name: "手柄：LB", bit: BIT.LB, lockAspect: false },
  btnRB: { name: "手柄：RB", bit: BIT.RB, lockAspect: false },
  btnLT: { name: "手柄：LT", bit: BIT.LT, isTrigger: true, lockAspect: false },
  btnRT: { name: "手柄：RT", bit: BIT.RT, isTrigger: true, lockAspect: false },
  leftJoystick: { name: "手柄：左摇杆", isJoystick: true, axis: "left", lockAspect: true },
  rightJoystick: { name: "手柄：右摇杆", isJoystick: true, axis: "right", lockAspect: true },
  touchpad: { name: "触摸板（手柄）", isTouchpad: true, lockAspect: false },
  dpadPad: { name: "一体十字键", isDpadPad: true, lockAspect: true },
  btnDpadUp: { name: "上方向", isDpad: true, bit: BIT.DPAD_BIT_UP, lockAspect: true },
  btnDpadDown: { name: "下方向", isDpad: true, bit: BIT.DPAD_BIT_DOWN, lockAspect: true },
  btnDpadLeft: { name: "左方向", isDpad: true, bit: BIT.DPAD_BIT_LEFT, lockAspect: true },
  btnDpadRight: { name: "右方向", isDpad: true, bit: BIT.DPAD_BIT_RIGHT, lockAspect: true },
  btnSelect: { name: "手柄：选择", bit: BIT.SELECT, lockAspect: true },
  btnMenu: { name: "手柄：菜单", bit: BIT.START, lockAspect: true },
  btnHome: { name: "手柄：主页", bit: BIT.HOME, lockAspect: true },
  btnShare: { name: "手柄：分享", bit: BIT.TOUCHPAD_CLICK, lockAspect: true },
  btnLS: { name: "手柄：左摇杆按下", bit: BIT.L3, lockAspect: true },
  btnRS: { name: "手柄：右摇杆按下", bit: BIT.R3, lockAspect: true },
  btnMic: { name: "麦克风静音", bit: BIT.MIC_MUTE, lockAspect: true },
  customKeypad: { name: "自定义按键盘", isKeypad: true, lockAspect: true },
  btnCustomCircle: { name: "自定义(圆)", isCustom: true, lockAspect: true },
  btnCustomRect: { name: "自定义(方)", isCustom: true, lockAspect: false },
  btnSettings: { name: "设置", isSettings: true, lockAspect: true },
};

const KB_KEY_CODES = {
  kbLCtrl: Kb.LCtrl, kbLShift: Kb.LShift, kbLAlt: Kb.LAlt, kbLWin: Kb.LGui,
  kbRCtrl: Kb.RCtrl, kbRShift: Kb.RShift, kbRAlt: Kb.RAlt, kbRGui: Kb.RGui,
  kbQ: Kb.KeyQ, kbW: Kb.KeyW, kbE: Kb.KeyE, kbR: Kb.KeyR, kbT: Kb.KeyT,
  kbY: Kb.KeyY, kbU: Kb.KeyU, kbI: Kb.KeyI, kbO: Kb.KeyO, kbP: Kb.KeyP,
  kbA: Kb.KeyA, kbS: Kb.KeyS, kbD: Kb.KeyD, kbF: Kb.KeyF, kbG: Kb.KeyG,
  kbH: Kb.KeyH, kbJ: Kb.KeyJ, kbK: Kb.KeyK, kbL: Kb.KeyL,
  kbZ: Kb.KeyZ, kbX: Kb.KeyX, kbC: Kb.KeyC, kbV: Kb.KeyV, kbB: Kb.KeyB,
  kbN: Kb.KeyN, kbM: Kb.KeyM,
  kb1: Kb.Key1, kb2: Kb.Key2, kb3: Kb.Key3, kb4: Kb.Key4, kb5: Kb.Key5,
  kb6: Kb.Key6, kb7: Kb.Key7, kb8: Kb.Key8, kb9: Kb.Key9, kb0: Kb.Key0,
  kbSpace: Kb.Space, kbEnter: Kb.Enter, kbBackspace: Kb.Backspace, kbTab: Kb.Tab,
  kbCaps: Kb.CapsLock, kbEsc: Kb.Esc, kbDelete: Kb.Delete, kbMenu: Kb.Menu,
  kbMinus: Kb.Minus, kbEqual: Kb.Equal, kbLBracket: Kb.LBracket, kbRBracket: Kb.RBracket,
  kbBackslash: Kb.Backslash, kbSemicolon: Kb.Semicolon, kbApostrophe: Kb.Apostrophe,
  kbComma: Kb.Comma, kbDot: Kb.Dot, kbSlash: Kb.Slash, kbGrave: Kb.Grave,
  kbF1: Kb.F1, kbF2: Kb.F2, kbF3: Kb.F3, kbF4: Kb.F4, kbF5: Kb.F5, kbF6: Kb.F6,
  kbF7: Kb.F7, kbF8: Kb.F8, kbF9: Kb.F9, kbF10: Kb.F10, kbF11: Kb.F11, kbF12: Kb.F12,
  kbArrowUp: Kb.ArrowUp, kbArrowDown: Kb.ArrowDown, kbArrowLeft: Kb.ArrowLeft,
  kbArrowRight: Kb.ArrowRight,
};

for (const [id, code] of Object.entries(KB_KEY_CODES)) {
  CONTROLS[id] = { name: CONTROLS[id]?.name || id, isKeyboard: true, keyCode: code, lockAspect: false };
}

export const DOUBLE_CLICK_IDS = new Set(["leftJoystick", "rightJoystick", "touchpad"]);
export const JOYSTICK_IDS = new Set(["leftJoystick", "rightJoystick"]);
export const AREA_IDS = new Set(["leftJoystick", "rightJoystick", "touchpad", "dpadPad", "customKeypad"]);

export function baseId(id) {
  return id.split("_")[0];
}

// Default grid sizes for newly added controls (w, h).
export const DEFAULT_SIZE = {
  touchpad: [34, 22],
  leftJoystick: [17, 17],
  rightJoystick: [17, 17],
  dpadPad: [17, 17],
  customKeypad: [17, 17],
  btnLB: [14, 8],
  btnRB: [14, 8],
  btnLT: [14, 8],
  btnRT: [14, 8],
  btnCustomRect: [14, 8],
  btnSpace: [14, 8],
  kbEnter: [9, 8],
  kbBackspace: [9, 8],
  kbTab: [8, 8],
  kbCaps: [9, 8],
};

export function defaultSizeFor(id) {
  return DEFAULT_SIZE[baseId(id)] || [10, 10];
}

export function isKeypadId(id) {
  return baseId(id) === "customKeypad";
}

export function getControl(id) {
  const b = baseId(id);
  if (CONTROLS[b]) return CONTROLS[b];
  if (isKeypadId(id)) return CONTROLS.customKeypad;
  return null;
}

export function lockAspectFor(id) {
  const c = getControl(id);
  if (!c) return true;
  return c.lockAspect !== false;
}

// ── Display-mode labels (mirrors MainActivityControls.updateButtonLabels) ──
export const DISPLAY_MODES = ["XBOX", "PS", "SWITCH"];

// PS face buttons carry icons, not text (see layout.js _iconFor), so their label is empty.
const FACE_LABELS = {
  btnA: { XBOX: "A", PS: "", SWITCH: "B" },
  btnB: { XBOX: "B", PS: "", SWITCH: "A" },
  btnX: { XBOX: "X", PS: "", SWITCH: "Y" },
  btnY: { XBOX: "Y", PS: "", SWITCH: "X" },
};

// Bit value → display name (mirrors MainActivityControls.BitNameMapper).
export const BIT_NAMES = {
  [BIT.A]: "手柄：A",
  [BIT.B]: "手柄：B",
  [BIT.X]: "手柄：X",
  [BIT.Y]: "手柄：Y",
  [BIT.LB]: "手柄：LB",
  [BIT.RB]: "手柄：RB",
  [BIT.LT]: "手柄：LT",
  [BIT.RT]: "手柄：RT",
  [BIT.SELECT]: "手柄：选择",
  [BIT.START]: "手柄：菜单",
  [BIT.L3]: "手柄：左摇杆按下",
  [BIT.R3]: "手柄：右摇杆按下",
  [BIT.HOME]: "手柄：主页",
  [BIT.TOUCHPAD_CLICK]: "手柄：分享",
  [BIT.MIC_MUTE]: "麦克风静音",
  [BIT.DPAD_BIT_UP]: "上方向",
  [BIT.DPAD_BIT_DOWN]: "下方向",
  [BIT.DPAD_BIT_LEFT]: "左方向",
  [BIT.DPAD_BIT_RIGHT]: "右方向",
};

export function bitName(bit) {
  return BIT_NAMES[bit] || "位" + bit;
}

// Output picker groups (mirrors MainActivity.showOutputValuePicker).
export const OUTPUT_PICKER_GROUPS = [
  {
    title: "手柄",
    bits: [
      BIT.A, BIT.B, BIT.X, BIT.Y, BIT.LB, BIT.RB, BIT.LT, BIT.RT,
      BIT.SELECT, BIT.START, BIT.L3, BIT.R3, BIT.HOME, BIT.TOUCHPAD_CLICK,
      BIT.MIC_MUTE,
      BIT.DPAD_BIT_UP, BIT.DPAD_BIT_DOWN, BIT.DPAD_BIT_LEFT, BIT.DPAD_BIT_RIGHT,
    ],
  },
];

// Control ids treated as "buttons" by the editor panel (see FloatingEditorPanel.BUTTON_IDS).
const BUTTON_IDS = new Set([
  "btnDpadUp", "btnDpadDown", "btnDpadLeft", "btnDpadRight",
  "btnY", "btnA", "btnX", "btnB", "btnLT", "btnLB", "btnRT", "btnRB",
  "btnSelect", "btnHome", "btnMenu", "btnShare", "btnLS", "btnRS", "btnMic",
]);

export function isButtonId(id) {
  const b = baseId(id);
  return BUTTON_IDS.has(b) || b.startsWith("btnCustom");
}

export function isSettingsId(id) {
  return baseId(id) === "btnSettings";
}

// Chinese name for the editor panel title (mirrors FloatingEditorPanel.getChineseName).
export function chineseNameFor(id) {
  const b = baseId(id);
  const c = CONTROLS[b];
  if (c) return c.name;
  if (isKeypadId(id)) return CONTROLS.customKeypad.name;
  return b;
}

export function controlLabel(id, displayMode) {
  const b = baseId(id);
  if (FACE_LABELS[b]) return FACE_LABELS[b][displayMode] ?? FACE_LABELS[b].XBOX;

  // Bumpers / triggers.
  const bumpers = {
    btnLB: { XBOX: "LB", PS: "L1", SWITCH: "L" },
    btnRB: { XBOX: "RB", PS: "R1", SWITCH: "R" },
    btnLT: { XBOX: "LT", PS: "L2", SWITCH: "ZL" },
    btnRT: { XBOX: "RT", PS: "R2", SWITCH: "ZR" },
  };
  if (bumpers[b]) return bumpers[b][displayMode] || bumpers[b].XBOX;

  // Select / menu: XBOX & SWITCH draw an icon (empty text); PS shows text.
  if (b === "btnSelect") return displayMode === "PS" ? "SHARE" : "";
  if (b === "btnMenu") return displayMode === "PS" ? "OPTION" : "";

  // Icon-only gamepad buttons (drawn via _iconFor / letter icon).
  if (b === "btnHome" || b === "btnShare" || b === "btnLS" || b === "btnRS" || b === "btnMic") return "";
  // Direction buttons draw arrow icons.
  if (b.startsWith("btnDpad")) return "";

  return "";
}
