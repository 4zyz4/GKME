// GKME-Web — DeviceMotionEvent sensor source.
//
// Mirrors com.zyz4.gkme.input.SensorHandler: reads the device rotation rate and
// accelerometer (including gravity), remaps both into the selected hold
// orientation, and maintains a low-passed gravity estimate for the WORLD
// coordinate system.
//
// Browser notes:
//   * Chrome exposes DeviceMotionEvent only in a secure context (HTTPS or
//     localhost). The host address over plain HTTP will report "unavailable".
//   * iOS 13+ requires DeviceMotionEvent.requestPermission() from a user
//     gesture; App calls requestPermission() from the connect tap.

import { computeWorldDelta, GYRO_ORIENTATION, remapToOrientation } from "./gyro.js";

const GRAVITY_TAU_SEC = 0.24;
const DEG2RAD = Math.PI / 180;

export class MotionSensors {
  constructor() {
    this.supported = typeof window !== "undefined" && typeof window.DeviceMotionEvent !== "undefined";
    this.permission = "unknown";
    this.received = false;
    this.hasGyro = false;
    this.hasAccel = false;

    this.orientation = GYRO_ORIENTATION.LANDSCAPE;
    this.inverted = false;

    this._raw = { gx: 0, gy: 0, gz: 0, ax: 0, ay: 0, az: 0 };
    this._grav = { x: 0, y: 0, z: 0 };
    this._gravInit = false;
    this._lastAccelTime = 0;
    this.world = { x: 0, y: 0 };

    this._started = false;
    this._handler = (event) => this._onMotion(event);
  }

  start() {
    if (this._started || !this.supported) return;
    window.addEventListener("devicemotion", this._handler);
    this._started = true;
    if (typeof window.DeviceMotionEvent?.requestPermission !== "function") {
      // Android / desktop: no explicit permission gate.
      this.permission = "granted";
    }
  }

  stop() {
    if (!this._started) return;
    window.removeEventListener("devicemotion", this._handler);
    this._started = false;
  }

  /** Requests motion permission from a user gesture. Returns true when granted. */
  async requestPermission() {
    if (!this.supported) {
      this.permission = "unavailable";
      return false;
    }
    const DME = window.DeviceMotionEvent;
    if (DME && typeof DME.requestPermission === "function") {
      try {
        const result = await DME.requestPermission();
        this.permission = result === "granted" ? "granted" : "denied";
        return this.permission === "granted";
      } catch {
        this.permission = "denied";
        return false;
      }
    }
    this.permission = "granted";
    return true;
  }

  setOrientation(orientation, inverted) {
    if (orientation === this.orientation && inverted === this.inverted) return;
    this.orientation = orientation;
    this.inverted = !!inverted;
    // Re-learn gravity in the new frame instead of dragging the old estimate.
    this._gravInit = false;
    this.world = { x: 0, y: 0 };
  }

  /** Remapped gyro (rad/s), accel (m/s²) and world-space look rate (rad/s). */
  sample() {
    const raw = this._raw;
    const [gx, gy, gz] = remapToOrientation(raw.gx, raw.gy, raw.gz, this.orientation, this.inverted);
    const [ax, ay, az] = remapToOrientation(raw.ax, raw.ay, raw.az, this.orientation, this.inverted);
    return {
      gyro: { x: gx, y: gy, z: gz },
      accel: { x: ax, y: ay, z: az },
      world: { x: this.world.x, y: this.world.y },
    };
  }

  _onMotion(event) {
    this.received = true;
    // Receiving events proves access even when the API exposes a permission
    // gate but the platform does not require an explicit prompt.
    if (this.permission === "unknown") this.permission = "granted";
    const raw = this._raw;

    const rate = event.rotationRate;
    if (rate && (rate.alpha != null || rate.beta != null || rate.gamma != null)) {
      this.hasGyro = true;
      // W3C axes: beta = x (pitch), alpha = z (yaw), gamma = y (roll).
      raw.gx = (Number(rate.beta) || 0) * DEG2RAD;
      raw.gy = (Number(rate.alpha) || 0) * DEG2RAD;
      raw.gz = (Number(rate.gamma) || 0) * DEG2RAD;
    }

    const accel = event.accelerationIncludingGravity || event.acceleration;
    if (!accel || (accel.x == null && accel.y == null && accel.z == null)) return;
    this.hasAccel = true;
    raw.ax = Number(accel.x) || 0;
    raw.ay = Number(accel.y) || 0;
    raw.az = Number(accel.z) || 0;

    const now = performance.now();
    const dt = this._lastAccelTime ? (now - this._lastAccelTime) / 1000 : 0;
    this._lastAccelTime = now;

    const [ax, ay, az] = remapToOrientation(raw.ax, raw.ay, raw.az, this.orientation, this.inverted);
    const mag = Math.sqrt(ax * ax + ay * ay + az * az);
    if (mag < 0.1) {
      this.world = { x: 0, y: 0 };
      return;
    }

    const alpha = dt > 0 ? Math.max(0, Math.min(1, 1 - Math.exp(-dt / GRAVITY_TAU_SEC))) : 1;
    if (!this._gravInit) {
      this._grav.x = ax;
      this._grav.y = ay;
      this._grav.z = az;
      this._gravInit = true;
    } else {
      this._grav.x += alpha * (ax - this._grav.x);
      this._grav.y += alpha * (ay - this._grav.y);
      this._grav.z += alpha * (az - this._grav.z);
    }

    const [gx, gy, gz] = remapToOrientation(raw.gx, raw.gy, raw.gz, this.orientation, this.inverted);
    const [wdx, wdy] = computeWorldDelta(gx, gy, gz, this._grav.x, this._grav.y, this._grav.z);
    this.world = { x: wdx, y: wdy };
  }
}
