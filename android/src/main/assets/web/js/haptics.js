// GKME-Web — haptics. Phone output uses the Vibration API (approximation of the
// phone's motor / RichTap output); controller output uses the Gamepad API's
// dual-rumble actuator (strong/weak) on the selected physical gamepad.
// Gracefully degrades where unsupported.

/** Connected gamepads in stable list order. The vibration-device setting and the
 *  settings UI both address controllers by position in this list. */
export function connectedGamepads() {
  const out = [];
  if (typeof navigator !== "undefined" && navigator.getGamepads) {
    for (const p of navigator.getGamepads()) if (p && p.connected) out.push(p);
  }
  return out;
}

/** The dual-rumble actuator of a gamepad, or null if it can't rumble. */
export function gamepadActuator(pad) {
  if (!pad) return null;
  if (pad.vibrationActuator && typeof pad.vibrationActuator.playEffect === "function") {
    return pad.vibrationActuator;
  }
  if (pad.hapticActuators && pad.hapticActuators[0]) return pad.hapticActuators[0];
  return null;
}

/** Number of independent motors the device exposes (0/2), used to decide whether
 *  the "swap left/right motors" row should be shown. */
export function deviceMotorCount(device, pads = connectedGamepads()) {
  if (!device) return 0;
  if (device.type === "phone") return 2;
  if (device.type === "controller") return gamepadActuator(pads[device.controllerIndex | 0]) ? 2 : 0;
  return 0;
}

export class Haptics {
  constructor(settings) {
    this.settings = settings;
    this.supported = typeof navigator !== "undefined" && "vibrate" in navigator;
    this._lastVib = 0;
    this._lastPressTime = 0;
    this._lastReleaseTime = 0;
    this._lastRumbleTime = 0;
    this._phoneActive = false;
    this._controllerActive = false;
  }

  setSettings(s) {
    this.settings = s;
  }

  // The Vibration API is supposed to be async, but several mobile WebViews
  // block the main thread for the whole vibration. Calling it from inside a
  // pointer/key handler would then stall input dispatch and the send loop,
  // which shows up as a >100ms delay on buttons only (the stick never
  // vibrates). Defer to a later task so the handler and the current input
  // frame are already processed before any block happens.
  _vibrate(ms) {
    setTimeout(() => {
      try {
        navigator.vibrate(ms);
      } catch {}
    }, 0);
  }

  /** Pulse on a button press/release. Always uses the phone motor. */
  buttonPulse(isPress = true) {
    if (!this.supported) return;
    const hs = this.settings.haptics;
    const type = isPress ? hs.buttonPressType : hs.buttonReleaseType;
    if (type !== "custom") return;
    // Throttle per side: a stream of presses must not vibrate continuously.
    const now = performance.now();
    const key = isPress ? "_lastPressTime" : "_lastReleaseTime";
    if (now - this[key] < 10) return;
    this[key] = now;
    const ms = Math.max(10, (isPress ? hs.buttonPressDuration : hs.buttonReleaseDuration) || 18);
    this._vibrate(ms);
  }

  /** Server-driven rumble (Vibration: large_motor / small_motor, 0..255).
   *  Routed to whichever actuator the user selected in the 震动 settings. */
  rumble(large, small) {
    if (!this.settings.haptics.gameVibrate) return;
    const device = this.settings.haptics.device || { type: "phone" };
    if (device.type === "none") return;
    if (device.type === "controller") {
      this._rumbleController(device.controllerIndex | 0, large, small);
    } else {
      this._rumblePhone(large, small);
    }
  }

  /** Phone motor: the magnitude is approximated with a fixed Vibration API pulse. */
  _rumblePhone(large, small) {
    if (!this.supported) return;
    let l = large | 0;
    let s = small | 0;
    if (this.settings.haptics.swapPhoneMotors) {
      const t = l;
      l = s;
      s = t;
    }
    const mag = Math.max(l, s);
    if (mag < 8) {
      if (this._phoneActive) {
        this._phoneActive = false;
        this._vibrate(0);
      }
      return;
    }
    const now = performance.now();
    // The protocol keeps re-sending the same rumble state; only re-trigger
    // when the magnitude meaningfully changes or enough time passed.
    if (Math.abs(mag - this._lastVib) < 24 && now - this._lastRumbleTime < 120) return;
    this._lastVib = mag;
    this._lastRumbleTime = now;
    this._phoneActive = true;
    this._vibrate(90);
  }

  /** Gamepad motor: dual-rumble with strong (large) / weak (small) magnitudes. */
  _rumbleController(index, large, small) {
    const pad = connectedGamepads()[index];
    const actuator = gamepadActuator(pad);
    if (!actuator) return;
    let strong = large | 0;
    let weak = small | 0;
    if (this.settings.haptics.swapControllerMotors) {
      const t = strong;
      strong = weak;
      weak = t;
    }
    const mag = Math.max(strong, weak);
    const now = performance.now();
    if (mag < 8) {
      if (this._controllerActive) {
        this._controllerActive = false;
        this._stopActuator(actuator);
      }
      return;
    }
    if (Math.abs(mag - this._lastVib) < 24 && now - this._lastRumbleTime < 120) return;
    this._lastVib = mag;
    this._lastRumbleTime = now;
    this._controllerActive = true;
    const strongMagnitude = Math.max(0, Math.min(1, strong / 255));
    const weakMagnitude = Math.max(0, Math.min(1, weak / 255));
    try {
      if (typeof actuator.playEffect === "function") {
        actuator.playEffect("dual-rumble", {
          startDelay: 0,
          duration: 200,
          strongMagnitude,
          weakMagnitude,
        });
      } else if (typeof actuator.pulse === "function") {
        // Legacy GamepadHapticActuator: single value, no independent motors.
        actuator.pulse(mag / 255, 200);
      }
    } catch {}
  }

  _stopActuator(actuator) {
    try {
      if (typeof actuator.playEffect === "function") {
        actuator.playEffect("dual-rumble", {
          startDelay: 0,
          duration: 1,
          strongMagnitude: 0,
          weakMagnitude: 0,
        });
      } else if (typeof actuator.reset === "function") {
        actuator.reset();
      } else if (typeof actuator.pulse === "function") {
        actuator.pulse(0, 1);
      }
    } catch {}
  }

  stop() {
    this._lastVib = 0;
    if (this.supported && this._phoneActive) {
      this._phoneActive = false;
      this._vibrate(0);
    }
    if (this._controllerActive) {
      this._controllerActive = false;
      const actuator = gamepadActuator(connectedGamepads()[(this.settings.haptics.device || {}).controllerIndex | 0]);
      if (actuator) this._stopActuator(actuator);
    }
  }
}
