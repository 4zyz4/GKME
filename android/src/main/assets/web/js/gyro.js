// GKME-Web — motion (gyro / accelerometer) mapping model.
//
// Mirrors com.zyz4.gkme.input.SensorHandler + AccelSteeringMapper and the gyro
// section of GkViewModel.startSendLoop():
//
//   raw axes → orientation remap → coordinate system → dead zone → mode
//
// Modes are the same six as the Android panel:
//   HANDHELD            forward the gyro / accelerometer to the host
//   MOUSE               gyro → relative mouse movement
//   LEFT_STICK          gyro → left stick (view)
//   RIGHT_STICK         gyro → right stick (view)
//   ACCELEROMETER_LEFT_STICK   accelerometer → left stick (steering wheel)
//   ACCELEROMETER_RIGHT_STICK  accelerometer → right stick (steering wheel)
//
// The shared sensitivity-curve editor is intentionally not ported; a curve
// imported from Android is preserved in the preset but not applied.

import { t } from "./i18n.js";

export const GYRO_ORIENTATION = {
  LANDSCAPE: "LANDSCAPE",
  PORTRAIT: "PORTRAIT",
  PORTRAIT_INVERTED: "PORTRAIT_INVERTED",
};

export const GYRO_ACTIVATE_MODE = {
  ALWAYS: "ALWAYS",
  BUTTON: "BUTTON",
};

export const GYRO_BASE_DIRECTION = {
  VERTICAL: "VERTICAL",
  HORIZONTAL: "HORIZONTAL",
};

export const GYRO_COORDINATE_SYSTEM = {
  YAW: "YAW",
  ROLL: "ROLL",
  YAW_ROLL: "YAW_ROLL",
  WORLD: "WORLD",
};

export const GYRO_MODE = {
  NONE: "NONE",
  HANDHELD: "HANDHELD",
  MOUSE: "MOUSE",
  LEFT_STICK: "LEFT_STICK",
  RIGHT_STICK: "RIGHT_STICK",
  ACCELEROMETER_LEFT_STICK: "ACCELEROMETER_LEFT_STICK",
  ACCELEROMETER_RIGHT_STICK: "ACCELEROMETER_RIGHT_STICK",
};

/** Modes offered by the layout panel, in Android's order. */
export const GYRO_MODES = [
  GYRO_MODE.HANDHELD,
  GYRO_MODE.MOUSE,
  GYRO_MODE.LEFT_STICK,
  GYRO_MODE.RIGHT_STICK,
  GYRO_MODE.ACCELEROMETER_LEFT_STICK,
  GYRO_MODE.ACCELEROMETER_RIGHT_STICK,
];

const GYRO_MODE_LABEL_KEYS = {
  [GYRO_MODE.HANDHELD]: "global.gyroModeHandheld",
  [GYRO_MODE.MOUSE]: "global.gyroModeMouse",
  [GYRO_MODE.LEFT_STICK]: "global.gyroModeLeftView",
  [GYRO_MODE.RIGHT_STICK]: "global.gyroModeRightView",
  [GYRO_MODE.ACCELEROMETER_LEFT_STICK]: "global.gyroModeLeftDirection",
  [GYRO_MODE.ACCELEROMETER_RIGHT_STICK]: "global.gyroModeRightDirection",
};

export function gyroModeLabel(mode) {
  return t(GYRO_MODE_LABEL_KEYS[mode] || "global.gyroModeHandheld");
}

const GYRO_MODE_HINT_KEYS = {
  [GYRO_MODE.HANDHELD]: "global.gyroModeHandheldHint",
  [GYRO_MODE.NONE]: "global.gyroModeHandheldHint",
  [GYRO_MODE.MOUSE]: "global.gyroModeMouseHint",
  [GYRO_MODE.LEFT_STICK]: "global.gyroModeViewHint",
  [GYRO_MODE.RIGHT_STICK]: "global.gyroModeViewHint",
  [GYRO_MODE.ACCELEROMETER_LEFT_STICK]: "global.gyroModeDirectionHint",
  [GYRO_MODE.ACCELEROMETER_RIGHT_STICK]: "global.gyroModeDirectionHint",
};

export function gyroModeHint(mode) {
  return t(GYRO_MODE_HINT_KEYS[mode] || "global.gyroModeHandheldHint");
}

export function isAccelMode(mode) {
  return mode === GYRO_MODE.ACCELEROMETER_LEFT_STICK || mode === GYRO_MODE.ACCELEROMETER_RIGHT_STICK;
}

export function isViewMode(mode) {
  return mode === GYRO_MODE.MOUSE || mode === GYRO_MODE.LEFT_STICK || mode === GYRO_MODE.RIGHT_STICK;
}

/** Normalized default values shared by the settings model and the processor. */
export function normalizeGyroSettings(raw) {
  const src = raw || {};
  return {
    enabled: src.enabled !== false,
    orientation: Object.values(GYRO_ORIENTATION).includes(src.orientation)
      ? src.orientation
      : GYRO_ORIENTATION.LANDSCAPE,
    activateMode: src.activateMode === GYRO_ACTIVATE_MODE.BUTTON
      ? GYRO_ACTIVATE_MODE.BUTTON
      : GYRO_ACTIVATE_MODE.ALWAYS,
    mode: GYRO_MODES.includes(src.mode) ? src.mode : GYRO_MODE.HANDHELD,
    sensitivity: clampInt(src.sensitivity, 1, 100, 20),
    deadZone: clampInt(src.deadZone, 0, 100, 0),
    reverseDeadZone: clampInt(src.reverseDeadZone, 0, 100, 0),
    baseDirection: src.baseDirection === GYRO_BASE_DIRECTION.HORIZONTAL
      ? GYRO_BASE_DIRECTION.HORIZONTAL
      : GYRO_BASE_DIRECTION.VERTICAL,
    coordinateSystem: Object.values(GYRO_COORDINATE_SYSTEM).includes(src.coordinateSystem)
      ? src.coordinateSystem
      : GYRO_COORDINATE_SYSTEM.YAW_ROLL,
  };
}

function clampInt(value, min, max, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, Math.round(n)));
}

// ── Sensor frame helpers ─────────────────────────────────

export const GYRO_DEAD_ZONE_MAX_RATE = 5; // rad/s, same normalization as Android

/** SensorHandler.remapToOrientation: raw device axes → screen axes. */
export function remapToOrientation(rawX, rawY, rawZ, orientation, inverted) {
  let x;
  let y;
  let z;
  switch (orientation) {
    case GYRO_ORIENTATION.PORTRAIT:
      x = rawX;
      y = rawZ;
      z = -rawY;
      break;
    case GYRO_ORIENTATION.PORTRAIT_INVERTED:
      x = -rawX;
      y = rawZ;
      z = rawY;
      break;
    case GYRO_ORIENTATION.LANDSCAPE:
    default:
      x = -rawY;
      y = rawZ;
      z = -rawX;
      break;
  }
  if (inverted) return [-x, y, -z];
  return [x, y, z];
}

/**
 * SensorHandler.computeWorldDelta: blend yaw / roll / swapped axes according to
 * the gravity direction, giving a world-space "look" rate that does not depend
 * on how the phone is held.
 */
export function computeWorldDelta(gx, gy, gz, ax, ay, az) {
  const mag = Math.sqrt(ax * ax + ay * ay + az * az);
  if (mag < 0.1) return [0, 0];

  const gravX = ax / mag;
  const gravY = ay / mag;
  const gravZ = az / mag;

  const posY = Math.max(0, gravY);
  const negY = Math.max(0, -gravY);
  const posX = Math.max(0, gravX);
  const negX = Math.max(0, -gravX);
  const negZ = Math.max(0, -gravZ);
  const total = Math.max(0.001, posY + negY + posX + negX + negZ);

  const yawDx = gy;
  const yawDy = gx;
  const rollDx = -gz;
  const rollDy = gx;
  const swapNegYDx = gx;
  const swapNegYDy = -gy;
  const swapNegXDx = -gx;
  const swapNegXDy = gy;

  const worldDx = (posY * yawDx + negZ * rollDx + posX * swapNegYDx + negX * swapNegXDx) / total;
  const worldDy = (posY * yawDy + negZ * rollDy + posX * swapNegYDy + negX * swapNegXDy) / total;
  return [worldDx, worldDy];
}

// ── Accelerometer → stick (AccelSteeringMapper.kt) ───────

const RAD2DEG = 180 / Math.PI;
const DEG2RAD = Math.PI / 180;
const DEFAULT_DT = 0.02;

class AxisWheel {
  constructor() {
    this.reset();
  }

  reset() {
    this.angleDeg = 0;
    this.angleReady = false;
    this.lastSide = 1;
    this.sideReady = false;
    this.turns = 0;
  }
}

/**
 * Accelerometer → stick steering mapper. Ported from
 * android/src/main/java/com/zyz4/gkme/input/AccelSteeringMapper.kt, including
 * the gyro-fused gravity estimate (translation rejection) and the multi-turn
 * wheel trackers.
 */
export class AccelSteeringMapper {
  constructor() {
    this.wheelX = new AxisWheel();
    this.wheelY = new AxisWheel();
    this.reset();
  }

  reset() {
    this.dirX = 0;
    this.dirY = 0;
    this.dirZ = 0;
    this.gravityMag = 0;
    this.ready = false;
    this.wheelX.reset();
    this.wheelY.reset();
    this.gyroSeen = false;
  }

  /**
   * @returns {{x:number,y:number}} both components in [-1, 1].
   */
  update(aX, aY, aZ, baseDirection, orientation, inverted, sensitivity, deadZone = 0, reverseDeadZone = 0, gyroX = 0, gyroY = 0, gyroZ = 0, dt = DEFAULT_DT) {
    const aMag = Math.sqrt(aX * aX + aY * aY + aZ * aZ);
    const step = Math.max(0, Math.min(MAX_DT, Number(dt) || 0));

    if (Math.abs(gyroX) + Math.abs(gyroY) + Math.abs(gyroZ) > GYRO_EPS) this.gyroSeen = true;

    if (!this.ready) {
      if (aMag < MIN_GRAVITY) return { x: 0, y: 0 };
      this.dirX = aX / aMag;
      this.dirY = aY / aMag;
      this.dirZ = aZ / aMag;
      this.gravityMag = aMag;
      this.ready = true;
    } else if (step > 0) {
      // Integrate the gyro to predict where gravity should have moved: dg/dt = -omega × g.
      let px = this.dirX + (gyroZ * this.dirY - gyroY * this.dirZ) * step;
      let py = this.dirY + (gyroX * this.dirZ - gyroZ * this.dirX) * step;
      let pz = this.dirZ + (gyroY * this.dirX - gyroX * this.dirY) * step;
      let pm = Math.sqrt(px * px + py * py + pz * pz);
      if (pm > 1e-6) {
        px /= pm;
        py /= pm;
        pz /= pm;
      }

      // Pull towards the measured gravity direction, but only as fast as TAU allows,
      // and even slower when the magnitude looks like linear acceleration.
      if (aMag >= MIN_GRAVITY) {
        const magError = Math.abs(aMag - this.gravityMag) / Math.max(MIN_GRAVITY, this.gravityMag);
        const trust = Math.max(0, Math.min(1, 1 - magError / LINEAR_ACCEL_TOLERANCE));
        const alpha = (step / TAU) * trust;
        const ax = aX / aMag;
        const ay = aY / aMag;
        const az = aZ / aMag;
        px += (ax - px) * alpha;
        py += (ay - py) * alpha;
        pz += (az - pz) * alpha;
        const bm = Math.sqrt(px * px + py * py + pz * pz);
        if (bm > 1e-6) {
          px /= bm;
          py /= bm;
          pz /= bm;
        }
        this.gravityMag += (aMag - this.gravityMag) * Math.max(0, Math.min(1, (step * trust) / GRAVITY_TAU));
      }

      this.dirX = px;
      this.dirY = py;
      this.dirZ = pz;
    }

    const nx = this.dirX;
    const ny = this.dirY;
    const nz = this.dirZ;
    const sens = Math.max(1, Math.min(100, Math.round(Number(sensitivity) || 0))) / 100;

    let rawX;
    let rawY;
    if (baseDirection === GYRO_BASE_DIRECTION.VERTICAL) {
      // 竖放: X rotates about the screen normal (gyroY), Y about the in-plane x axis (gyroX).
      const refZ = orientation === GYRO_ORIENTATION.LANDSCAPE || !inverted ? -1 : 1;
      const [turnsX, sideX] = this._resolveWheel(this.wheelX, nx, nz, refZ, refZ * gyroY * RAD2DEG, step, true);
      const [turnsY, sideY] = this._resolveWheel(this.wheelY, ny, nz, refZ, -refZ * gyroX * RAD2DEG, step, false);
      rawX = -(180 * turnsX + 90 * nx * sideX) / 90;
      rawY = -(180 * turnsY + 90 * ny * sideY) / 90;
    } else {
      // 平放: X rotates about the in-plane z axis (gyroZ), Y about x (gyroX).
      const [turnsX, sideX] = this._resolveWheel(this.wheelX, nx, ny, 1, -gyroZ * RAD2DEG, step, false);
      const [turnsY, sideY] = this._resolveWheel(this.wheelY, nz, ny, 1, gyroX * RAD2DEG, step, false);
      rawX = -(180 * turnsX + 90 * nx * sideX) / 90;
      rawY = -(180 * turnsY + 90 * nz * sideY) / 90;
    }

    // Radial dead zone / reverse dead zone, normalized by one full deflection.
    const dz = Math.max(0, Math.min(0.99, (Number(deadZone) || 0) / 100));
    const rdz = Math.max(0, Math.min(0.99, (Number(reverseDeadZone) || 0) / 100));
    let outX = rawX;
    let outY = rawY;
    if (dz > 0 || rdz > 0) {
      const mag = Math.sqrt(rawX * rawX + rawY * rawY);
      if (mag > 0) {
        const normalized = Math.max(0, Math.min(1, mag));
        const afterDeadZone = normalized <= dz ? 0 : (normalized - dz) / (1 - dz);
        const afterReverseDeadZone = afterDeadZone === 0 ? 0 : afterDeadZone * (1 - rdz) + rdz;
        const scale = afterReverseDeadZone / mag;
        outX = rawX * scale;
        outY = rawY * scale;
      }
    }

    return {
      x: Math.max(-1, Math.min(1, outX * sens)),
      y: Math.max(-1, Math.min(1, outY * sens)),
    };
  }

  _resolveWheel(wheel, proxy, ref, refSign, rateDeg, step, accelFallback) {
    if (this.gyroSeen) {
      if (!wheel.angleReady) {
        wheel.angleDeg = -Math.asin(Math.max(-1, Math.min(1, proxy))) * RAD2DEG;
        wheel.angleReady = true;
      }
      wheel.angleDeg += rateDeg * step;

      const measured = -Math.asin(Math.max(-1, Math.min(1, proxy))) * RAD2DEG;
      let rep = measured;
      while (rep - wheel.angleDeg > 180) rep -= 360;
      while (wheel.angleDeg - rep > 180) rep += 360;
      let alt = 180 - measured;
      while (alt - wheel.angleDeg > 180) alt -= 360;
      while (wheel.angleDeg - alt > 180) alt += 360;
      if (Math.abs(alt - wheel.angleDeg) < Math.abs(rep - wheel.angleDeg)) rep = alt;
      wheel.angleDeg += (rep - wheel.angleDeg) * Math.max(0, Math.min(MAX_WHEEL_CORRECTION, step / WHEEL_TAU));

      const side = Math.cos(wheel.angleDeg * DEG2RAD) >= 0 ? 1 : -1;
      const turns = -Math.floor((wheel.angleDeg + 90) / 180);
      return [turns, side];
    }

    if (!accelFallback) return [0, 1];

    // Legacy fallback (no gyro data): infer the half-turn from the out-of-plane gravity sign.
    const side = ref * refSign >= 0 ? 1 : -1;
    if (!wheel.sideReady) {
      wheel.lastSide = side;
      wheel.sideReady = true;
    } else if (side !== wheel.lastSide) {
      const yDeg = 90 * proxy;
      if (wheel.lastSide > 0 && side < 0) wheel.turns += yDeg < 0 ? -1 : 1;
      else if (wheel.lastSide < 0 && side > 0) wheel.turns += yDeg < 0 ? 1 : -1;
      wheel.lastSide = side;
    }
    return [wheel.turns, side];
  }
}

const MAX_DT = 0.05;
const TAU = 1.0;
const GRAVITY_TAU = 2.0;
const LINEAR_ACCEL_TOLERANCE = 0.4;
const MIN_GRAVITY = 0.1;
const GYRO_EPS = 1e-3;
const WHEEL_TAU = 2.0;
const MAX_WHEEL_CORRECTION = 0.05;

// ── Per-frame gyro processor ────────────────────────────

/**
 * Turns one remapped sensor sample into the per-frame contributions used by the
 * input snapshot. Mouse counts are integrated by the caller at the send rate
 * (see App._gyroMouseStep), matching GkViewModel.
 */
export class GyroProcessor {
  constructor() {
    this.mapper = new AccelSteeringMapper();
    this._accelKey = "";
  }

  reset() {
    this.mapper.reset();
    this._accelKey = "";
  }

  /**
   * @param {object} sample { gyro:{x,y,z}, accel:{x,y,z}, world:{x,y} }
   * @param {object} cfg effective gyro settings (see App.gyroConfig)
   * @param {boolean} active whether the gyro is currently enabled/activated
   * @param {number} dt frame interval in seconds
   */
  update(sample, cfg, active, dt) {
    const zero = {
      active: false,
      mode: cfg.mode,
      sens: 1,
      forwardGyro: { x: 0, y: 0, z: 0 },
      forwardAccel: { x: 0, y: 0, z: 0 },
      mappedX: 0,
      mappedY: 0,
      stick: { lx: 0, ly: 0, rx: 0, ry: 0 },
    };
    if (!active) {
      this.reset();
      return zero;
    }

    const gyro = sample.gyro;
    const accel = sample.accel;
    const gx = gyro.x;
    const gy = gyro.y;
    const gz = gyro.z;

    let mappedX = 0;
    let mappedY = 0;
    switch (cfg.coordinateSystem) {
      case GYRO_COORDINATE_SYSTEM.YAW:
        mappedX = gy;
        mappedY = gx;
        break;
      case GYRO_COORDINATE_SYSTEM.ROLL:
        mappedX = -gz;
        mappedY = gx;
        break;
      case GYRO_COORDINATE_SYSTEM.WORLD:
        mappedX = sample.world.x;
        mappedY = sample.world.y;
        break;
      case GYRO_COORDINATE_SYSTEM.YAW_ROLL:
      default:
        mappedX = gy - gz;
        mappedY = gx;
        break;
    }

    // Dead zone / reverse dead zone, radial and normalized by 5 rad/s.
    const dz = Math.max(0, Math.min(0.99, (Number(cfg.deadZone) || 0) / 100));
    const rdz = Math.max(0, Math.min(0.99, (Number(cfg.reverseDeadZone) || 0) / 100));
    if (dz > 0 || rdz > 0) {
      const mag = Math.sqrt(mappedX * mappedX + mappedY * mappedY);
      if (mag > 0) {
        const normalized = Math.max(0, Math.min(1, mag / GYRO_DEAD_ZONE_MAX_RATE));
        const afterDeadZone = normalized <= dz ? 0 : (normalized - dz) / (1 - dz);
        const afterReverseDeadZone = afterDeadZone === 0 ? 0 : afterDeadZone * (1 - rdz) + rdz;
        const scale = (afterReverseDeadZone * GYRO_DEAD_ZONE_MAX_RATE) / mag;
        mappedX *= scale;
        mappedY *= scale;
      }
    }

    const sens = Math.max(1, Math.min(100, Math.round(Number(cfg.sensitivity) || 0))) / 100;
    const stick = { lx: 0, ly: 0, rx: 0, ry: 0 };

    if (cfg.mode === GYRO_MODE.LEFT_STICK) {
      stick.lx = toI16(-mappedX * sens * 32767);
      stick.ly = toI16(-mappedY * sens * 32767);
    } else if (cfg.mode === GYRO_MODE.RIGHT_STICK) {
      stick.rx = toI16(-mappedX * sens * 32767);
      stick.ry = toI16(-mappedY * sens * 32767);
    }

    const accelMode = isAccelMode(cfg.mode);
    const accelKey = `${cfg.baseDirection}|${cfg.orientation}|${cfg.inverted ? 1 : 0}|${accelMode ? 1 : 0}`;
    if (accelKey !== this._accelKey) {
      this.mapper.reset();
      this._accelKey = accelKey;
    }
    if (accelMode) {
      const out = this.mapper.update(
        accel.x,
        accel.y,
        accel.z,
        cfg.baseDirection,
        cfg.orientation,
        !!cfg.inverted,
        cfg.sensitivity,
        cfg.deadZone,
        cfg.reverseDeadZone,
        gx,
        gy,
        gz,
        dt
      );
      const ix = toI16(out.x * 32767);
      const iy = toI16(out.y * 32767);
      if (cfg.mode === GYRO_MODE.ACCELEROMETER_LEFT_STICK) {
        stick.lx = ix;
        stick.ly = iy;
      } else {
        stick.rx = ix;
        stick.ry = iy;
      }
    }

    return {
      active: true,
      mode: cfg.mode,
      sens,
      forwardGyro: { x: gx, y: gy, z: gz },
      forwardAccel: { x: accel.x, y: accel.y, z: accel.z },
      mappedX,
      mappedY,
      stick,
    };
  }
}

function toI16(value) {
  return Math.max(-32768, Math.min(32767, Math.trunc(value)));
}
