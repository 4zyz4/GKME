// GKME-Web 鈥?built-in layout presets (mirrors android/src/main/res/raw).
// Each button only lists the fields it overrides; normalizeButton() fills the
// rest with ButtonPosition defaults.

export const FULL_CON = {
  version: 1,
  buttons: [
    { id: "touchpad", x: 39, y: 7, w: 44, h: 28, lockAspect: false, overlapTrigger: true, doubleClickEnable: true },
    { id: "btnY", x: 100, y: 11, w: 10, h: 10, lockAspect: true },
    { id: "btnA", x: 100, y: 27, w: 10, h: 10, lockAspect: true },
    { id: "btnX", x: 92, y: 19, w: 10, h: 10, lockAspect: true },
    { id: "btnB", x: 108, y: 19, w: 10, h: 10, lockAspect: true },
    { id: "leftJoystick", x: 7, y: 16, w: 18, h: 18, lockAspect: true, doubleClickEnable: true, prediction: true, deadZone: 0 },
    { id: "rightJoystick", x: 82, y: 33, w: 18, h: 18, lockAspect: true, doubleClickEnable: true, prediction: true, deadZone: 0 },
    { id: "btnLT", x: 0, y: 0, w: 16, h: 9, lockAspect: false },
    { id: "btnLB", x: 16, y: 0, w: 16, h: 9, lockAspect: false },
    { id: "btnRT", x: 104, y: 0, w: 16, h: 9, lockAspect: false },
    { id: "btnRB", x: 88, y: 0, w: 16, h: 9, lockAspect: false },
    { id: "btnHome", x: 56, y: 40, w: 9, h: 9, lockAspect: true },
    { id: "btnMenu", x: 66, y: 40, w: 9, h: 9, lockAspect: true },
    { id: "btnSelect", x: 46, y: 40, w: 9, h: 9, lockAspect: true },
    { id: "btnSettings", x: 57, y: 0, w: 6, h: 6, lockAspect: true },
    { id: "dpadPad", x: 21, y: 32, w: 18, h: 18, lockAspect: true },
  ],
};

export const BUILTIN_PRESETS = {
  full_con: FULL_CON,
};

