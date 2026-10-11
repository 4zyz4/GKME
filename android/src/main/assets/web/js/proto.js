// GKME-Web — minimal hand-written protobuf codec.
//
// Implements exactly the subset of proto/gamepad_state.proto that the browser
// controller needs, so the wire format is byte-for-byte compatible with the
// Android client and the C# host (Google.Protobuf). No build step, no deps.
//
// Wire framing (see protocol.md):
//   type 0x00 ClientToServer (browser → host)   [1 byte type][protobuf]
//   type 0x02 GamepadInput   (browser → host)   [1 byte type][protobuf]
//   type 0x01 ServerToClient (host → browser)   [1 byte type][protobuf]

export const TYPE_CLIENT_TO_SERVER = 0x00;
export const TYPE_SERVER_TO_CLIENT = 0x01;
export const TYPE_GAMEPAD_INPUT = 0x02;

const WIRE_VARINT = 0;
const WIRE_I64 = 1;
const WIRE_LEN = 2;
const WIRE_I32 = 5;

class Writer {
  constructor() {
    this.buf = new Uint8Array(1024);
    this.pos = 0;
  }

  _ensure(n) {
    if (this.pos + n <= this.buf.length) return;
    let cap = this.buf.length * 2;
    while (cap < this.pos + n) cap *= 2;
    const nb = new Uint8Array(cap);
    nb.set(this.buf.subarray(0, this.pos));
    this.buf = nb;
  }

  u8(b) {
    this._ensure(1);
    this.buf[this.pos++] = b & 0xff;
  }

  varint(v) {
    v = v >>> 0;
    while (v > 0x7f) {
      this.u8((v & 0x7f) | 0x80);
      v >>>= 7;
    }
    this.u8(v);
  }

  tag(field, wire) {
    this.varint(((field << 3) | wire) >>> 0);
  }

  uint(field, v) {
    if (v === undefined || v === null || v === 0) return;
    this.tag(field, WIRE_VARINT);
    this.varint(v);
  }

  bool(field, v) {
    if (!v) return;
    this.tag(field, WIRE_VARINT);
    this.u8(1);
  }

  sint(field, v) {
    if (v === undefined || v === null || v === 0) return;
    this.tag(field, WIRE_VARINT);
    // zigzag
    const zz = ((v << 1) ^ (v >> 31)) >>> 0;
    this.varint(zz);
  }

  float(field, v) {
    if (v === undefined || v === null || v === 0) return;
    this.tag(field, WIRE_I32);
    this._ensure(4);
    const dv = new DataView(this.buf.buffer, this.buf.byteOffset + this.pos, 4);
    dv.setFloat32(0, v, true);
    this.pos += 4;
  }

  string(field, v) {
    if (!v) return;
    const bytes = new TextEncoder().encode(v);
    this.bytes(field, bytes);
  }

  bytes(field, arr) {
    if (!arr || arr.length === 0) return;
    this.tag(field, WIRE_LEN);
    this.varint(arr.length);
    this._ensure(arr.length);
    this.buf.set(arr, this.pos);
    this.pos += arr.length;
  }

  message(field, sub) {
    if (!sub) return;
    this.bytes(field, sub);
  }

  // Packed repeated uint32 (proto3 default for repeated scalar).
  packedUints(field, arr) {
    if (!arr || arr.length === 0) return;
    const w = new Writer();
    for (const v of arr) w.varint(v);
    this.bytes(field, w.toBytes());
  }

  toBytes() {
    return this.buf.subarray(0, this.pos).slice();
  }
}

/** Encode a single TouchPoint (id=1, x=2, y=3, active=4). */
function encodeTouch(w, field, pt) {
  const t = new Writer();
  t.uint(1, pt.id);
  t.uint(2, pt.x);
  t.uint(3, pt.y);
  t.bool(4, pt.active);
  w.message(field, t.toBytes());
}

/**
 * Encode GamepadInput. `inp` fields (all optional):
 *   buttons, leftStickX/Y, rightStickX/Y, leftTrigger, rightTrigger, dpad,
 *   batteryLevel, isCharging, touches (array), scanCodes (array), modifiers
 */
export function encodeGamepadInput(inp) {
  const w = new Writer();
  w.uint(1, inp.buttons >>> 0);
  w.sint(2, inp.leftStickX | 0);
  w.sint(3, inp.leftStickY | 0);
  w.sint(4, inp.rightStickX | 0);
  w.sint(5, inp.rightStickY | 0);
  w.uint(6, inp.leftTrigger >>> 0);
  w.uint(7, inp.rightTrigger >>> 0);
  w.uint(8, inp.dpad >>> 0);
  w.uint(16, inp.batteryLevel >>> 0);
  w.bool(17, inp.isCharging);
  if (inp.touches && inp.touches.length) {
    for (const pt of inp.touches) {
      if (!pt) continue;
      encodeTouch(w, 22, pt);
    }
  }
  w.packedUints(28, inp.scanCodes);
  w.uint(30, inp.modifiers >>> 0);
  return w.toBytes();
}

/** Encode ClientToServer { Hello hello = 1 }. */
export function encodeHello(hello) {
  const h = new Writer();
  h.uint(1, hello.protocolVersion || 1);
  h.string(2, hello.deviceName);
  h.uint(4, hello.controllerIndex >>> 0);
  h.string(5, hello.macAddress);
  const w = new Writer();
  w.message(1, h.toBytes());
  return w.toBytes();
}

class Reader {
  constructor(bytes) {
    this.buf = bytes;
    this.pos = 0;
    this.view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  }

  get eof() {
    return this.pos >= this.buf.length;
  }

  varint() {
    let result = 0;
    let shift = 0;
    while (true) {
      const b = this.buf[this.pos++];
      result |= (b & 0x7f) << shift;
      if ((b & 0x80) === 0) break;
      shift += 7;
      if (shift > 35) break;
    }
    return result >>> 0;
  }

  sint() {
    const v = this.varint();
    return (v >>> 1) ^ -(v & 1);
  }

  float() {
    const f = this.view.getFloat32(this.pos, true);
    this.pos += 4;
    return f;
  }

  bytes() {
    const len = this.varint();
    const out = this.buf.subarray(this.pos, this.pos + len);
    this.pos += len;
    return out;
  }

  /** Read a length-delimited sub-message and return a reader over it. */
  sub() {
    return new Reader(this.bytes());
  }

  string() {
    return new TextDecoder().decode(this.bytes());
  }

  skip(wire) {
    switch (wire) {
      case WIRE_VARINT:
        this.varint();
        break;
      case WIRE_I64:
        this.pos += 8;
        break;
      case WIRE_LEN: {
        const len = this.varint();
        this.pos += len;
        break;
      }
      case WIRE_I32:
        this.pos += 4;
        break;
      default:
        this.pos = this.buf.length;
    }
  }
}

/** Iterate fields of a message buffer: cb(field, wire, reader). */
function eachField(reader, cb) {
  while (!reader.eof) {
    const key = reader.varint();
    const field = key >>> 3;
    const wire = key & 7;
    cb(field, wire, reader);
  }
}

function decodeTouch(r) {
  const pt = { id: 0, x: 0, y: 0, active: false };
  eachField(r, (f, wire, rr) => {
    if (f === 1) pt.id = rr.varint();
    else if (f === 2) pt.x = rr.varint();
    else if (f === 3) pt.y = rr.varint();
    else if (f === 4) pt.active = rr.varint() !== 0;
    else rr.skip(wire);
  });
  return pt;
}

function decodeVibration(r) {
  const v = { largeMotor: 0, smallMotor: 0 };
  eachField(r, (f, wire, rr) => {
    if (f === 1) v.largeMotor = rr.varint();
    else if (f === 2) v.smallMotor = rr.varint();
    else rr.skip(wire);
  });
  return v;
}

function decodeLed(r) {
  const l = { color: 0, playerLed: 0 };
  eachField(r, (f, wire, rr) => {
    if (f === 1) l.color = rr.varint();
    else if (f === 2) l.playerLed = rr.varint();
    else rr.skip(wire);
  });
  return l;
}

function decodeTestTone(r) {
  let enabled = false;
  eachField(r, (f, wire, rr) => {
    if (f === 1) enabled = rr.varint() !== 0;
    else rr.skip(wire);
  });
  return { enabled };
}

function decodeTriggerEffects(r) {
  const t = {
    leftTriggerEffect: null,
    rightTriggerEffect: null,
    leftTriggerRumble: null,
    rightTriggerRumble: null,
  };
  eachField(r, (f, wire, rr) => {
    if (f === 1) t.leftTriggerEffect = rr.bytes().slice();
    else if (f === 2) t.rightTriggerEffect = rr.bytes().slice();
    else if (f === 3) t.leftTriggerRumble = rr.varint();
    else if (f === 4) t.rightTriggerRumble = rr.varint();
    else rr.skip(wire);
  });
  return t;
}

function decodeCompactFrame(r) {
  const c = { vibration: null, triggerEffects: null, ledState: null, testTone: null };
  eachField(r, (f, wire, rr) => {
    if (f === 1) c.vibration = decodeVibration(rr.sub());
    else if (f === 2) c.triggerEffects = decodeTriggerEffects(rr.sub());
    else if (f === 3) c.ledState = decodeLed(rr.sub());
    else if (f === 5) c.testTone = decodeTestTone(rr.sub());
    else rr.skip(wire);
  });
  return c;
}

function decodeServerHello(r) {
  const h = {
    protocolVersion: 0,
    hostName: "",
    maxDownlinkRateHz: 0,
    recommendedUplinkIntervalUs: 0,
  };
  eachField(r, (f, wire, rr) => {
    if (f === 1) h.protocolVersion = rr.varint();
    else if (f === 2) h.hostName = rr.string();
    else if (f === 3) h.maxDownlinkRateHz = rr.varint();
    else if (f === 4) h.recommendedUplinkIntervalUs = rr.varint();
    else rr.skip(wire);
  });
  return h;
}

function decodeAudioFrame(r) {
  const a = {
    sampleRateHz: 0,
    channels: 0,
    bitsPerSample: 0,
    frameIndex: 0,
    sampleCount: 0,
    pcm: null,
  };
  eachField(r, (f, wire, rr) => {
    if (f === 1) a.sampleRateHz = rr.varint();
    else if (f === 2) a.channels = rr.varint();
    else if (f === 3) a.bitsPerSample = rr.varint();
    else if (f === 4) a.frameIndex = rr.varint();
    else if (f === 5) a.sampleCount = rr.varint();
    else if (f === 6) a.pcm = rr.bytes().slice();
    else rr.skip(wire);
  });
  return a;
}

function decodeHdRumble(r) {
  const out = new Array(8).fill(0);
  eachField(r, (f, wire, rr) => {
    if (f >= 1 && f <= 8) out[f - 1] = rr.float();
    else rr.skip(wire);
  });
  return out;
}

function decodeDisconnect(r) {
  let reason = "";
  eachField(r, (f, wire, rr) => {
    if (f === 1) reason = rr.string();
    else rr.skip(wire);
  });
  return { reason };
}

/**
 * Decode a ServerToClient payload (without the leading type byte). Returns a
 * flat object tagged with `kind`.
 */
export function decodeServerToClient(payload) {
  const r = new Reader(payload);
  const out = { kind: "unknown" };
  eachField(r, (f, wire, rr) => {
    switch (f) {
      case 2:
        out.kind = "triggerEffects";
        out.triggerEffects = decodeTriggerEffects(rr.sub());
        break;
      case 3:
        out.kind = "disconnect";
        out.reason = decodeDisconnect(rr.sub()).reason;
        break;
      case 7:
        out.kind = "serverHello";
        out.serverHello = decodeServerHello(rr.sub());
        break;
      case 8:
        out.kind = "audioFrame";
        out.audioFrame = decodeAudioFrame(rr.sub());
        break;
      case 9:
        out.kind = "ledState";
        out.ledState = decodeLed(rr.sub());
        break;
      case 10:
        out.kind = "testTone";
        out.testTone = decodeTestTone(rr.sub());
        break;
      case 11:
        out.kind = "compactFrame";
        out.compactFrame = decodeCompactFrame(rr.sub());
        break;
      case 12:
        out.kind = "hdRumble";
        out.hdRumble = decodeHdRumble(rr.sub());
        break;
      default:
        rr.skip(wire);
    }
  });
  return out;
}
