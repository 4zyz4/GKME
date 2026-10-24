// GKME-Web — persisted settings, presets and the live input state model.

import { BUILTIN_PRESETS, FULL_CON } from "./presets.js";
import { normalizeButton } from "./layout.js";
const SETTINGS_KEY = "gkme.settings.v1";
const PRESETS_KEY = "gkme.presets.v1";
const ACTIVE_PRESET_KEY = "gkme.activePreset.v1";

// Kept in sync with the Android versionName (android/build.gradle.kts).
export const APP_VERSION = "4.7.0";

export const DEFAULT_SETTINGS = {
  displayMode: "XBOX",
  pollingHz: 120,
  haptics: {
    // Button vibration, split into press / release. type: "none" | "custom".
    buttonPressType: "custom",
    buttonPressDuration: 18,
    buttonReleaseType: "none",
    buttonReleaseDuration: 12,
    gameVibrate: true,
    // Which actuator receives the game rumble. type: "phone" | "controller" | "none";
    // controllerIndex is the position in the connected-gamepad list (see connectedGamepads()).
    device: { type: "phone", controllerIndex: 0 },
    swapPhoneMotors: false,
    swapControllerMotors: false,
  },
  screen: {
    keepAwake: true,
    screenOff: false,
  },
  physical: {
    forward: true,
  },
  meta: {
    deviceName: "",
  },
};

function deepMerge(base, override) {
  const out = Array.isArray(base) ? base.slice() : { ...base };
  for (const k of Object.keys(override || {})) {
    const v = override[k];
    if (v && typeof v === "object" && !Array.isArray(v) && typeof base[k] === "object" && base[k] !== null) {
      out[k] = deepMerge(base[k], v);
    } else {
      out[k] = v;
    }
  }
  return out;
}

export function loadSettings() {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (!raw) return JSON.parse(JSON.stringify(DEFAULT_SETTINGS));
    const parsed = JSON.parse(raw);
    const merged = deepMerge(DEFAULT_SETTINGS, parsed);
    // Migrate the legacy single swapMotors flag to the phone-motor setting.
    const legacySwap = parsed && parsed.haptics && parsed.haptics.swapMotors;
    if (typeof legacySwap === "boolean" && !(parsed.haptics && "swapPhoneMotors" in parsed.haptics)) {
      merged.haptics.swapPhoneMotors = legacySwap;
    }
    delete merged.haptics.swapMotors;
    // Migrate the legacy single button-vibration toggle/duration to the press side.
    const h = parsed && parsed.haptics;
    if (h) {
      if (typeof h.buttonVibrate === "boolean" && !("buttonPressType" in h)) {
        merged.haptics.buttonPressType = h.buttonVibrate ? "custom" : "none";
      }
      if (typeof h.buttonVibrateMs === "number" && !("buttonPressDuration" in h)) {
        merged.haptics.buttonPressDuration = h.buttonVibrateMs;
      }
    }
    delete merged.haptics.buttonVibrate;
    delete merged.haptics.buttonVibrateMs;
    return merged;
  } catch {
    return JSON.parse(JSON.stringify(DEFAULT_SETTINGS));
  }
}

export function saveSettings(settings) {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  } catch {}
}

export function loadPresets() {
  try {
    const raw = localStorage.getItem(PRESETS_KEY);
    if (raw) return JSON.parse(raw);
  } catch {}
  return null;
}

export function savePresets(presets) {
  try {
    localStorage.setItem(PRESETS_KEY, JSON.stringify(presets));
  } catch {}
}

export function ensurePresets() {
  // The web build ships only the touch gamepad preset; keyboard/mouse layouts
  // are intentionally not part of GKME-Web.
  let presets = loadPresets() || {};
  delete presets.keyboard;
  delete presets.mouse;
  if (!presets.full_con) presets.full_con = JSON.parse(JSON.stringify(FULL_CON));
  savePresets(presets);
  return presets;
}

export function getActivePresetName() {
  const name = localStorage.getItem(ACTIVE_PRESET_KEY) || "full_con";
  return name === "keyboard" || name === "mouse" ? "full_con" : name;
}

export function setActivePresetName(name) {
  localStorage.setItem(ACTIVE_PRESET_KEY, name);
}

// ── Live input state ──
// Aggregates touch controls, physical controller forwarding and keyboard input
// into one GamepadInput-shaped snapshot.
export class InputState {
  constructor() {
    this.buttons = 0; // GKME bitmask (uint32)
    this.dpad = 0;
    this.leftStickX = 0;
    this.leftStickY = 0;
    this.rightStickX = 0;
    this.rightStickY = 0;
    this.leftTrigger = 0;
    this.rightTrigger = 0;
    this.touches = [];
    // keyboard
    this.kbModifiers = 0;
    this.kbKeys = new Set(); // usages
    // sources keep button/dpad contributions independent
    this._touchButtons = 0;
    this._physicalButtons = 0;
    this._customBits = 0;
    this._touchDpad = 0;
    this._physicalDpad = 0;
  }

  recomputeButtons() {
    this.buttons = (this._touchButtons | this._physicalButtons | this._customBits) >>> 0;
    this.dpad = this._touchDpad | this._physicalDpad;
  }

  toInput() {
    this.recomputeButtons();
    const scanCodes = Array.from(this.kbKeys);
    return {
      buttons: this.buttons >>> 0,
      dpad: this.dpad >>> 0,
      leftStickX: this.leftStickX,
      leftStickY: this.leftStickY,
      rightStickX: this.rightStickX,
      rightStickY: this.rightStickY,
      leftTrigger: this.leftTrigger,
      rightTrigger: this.rightTrigger,
      touches: this.touches,
      scanCodes,
      modifiers: this.kbModifiers >>> 0,
    };
  }
}
