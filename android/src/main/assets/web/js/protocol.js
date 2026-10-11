// GKME-Web — WebSocket transport.
//
// The browser is a GKME "controller" device. It connects to the host's embedded
// WebSocket bridge (served from the same origin) and speaks the same protobuf
// protocol as the Android app, only with WebSocket framing instead of UDP.

import {
  TYPE_CLIENT_TO_SERVER,
  TYPE_SERVER_TO_CLIENT,
  TYPE_GAMEPAD_INPUT,
  encodeHello,
  encodeGamepadInput,
  decodeServerToClient,
} from "./proto.js";

const DEVICE_ID_KEY = "gkme.deviceId";
const DEVICE_NAME_KEY = "gkme.deviceName";

function loadDeviceId() {
  let id = localStorage.getItem(DEVICE_ID_KEY);
  if (!id) {
    id =
      "web-" +
      Math.random().toString(36).slice(2, 10) +
      Math.random().toString(36).slice(2, 10);
    localStorage.setItem(DEVICE_ID_KEY, id);
  }
  return id;
}

export function getDeviceName() {
  return localStorage.getItem(DEVICE_NAME_KEY) || defaultName();
}

export function setDeviceName(name) {
  localStorage.setItem(DEVICE_NAME_KEY, name || "");
}

function defaultName() {
  const ua = navigator.userAgent || "";
  if (/iPhone|iPad|iPod/i.test(ua)) return "iPhone 网页手柄";
  if (/Android/i.test(ua)) return "Android 网页手柄";
  return "网页手柄";
}

export class Connection extends EventTarget {
  constructor() {
    super();
    this.deviceId = loadDeviceId();
    this.ws = null;
    this.connected = false;
    this.reconnectDelay = 1000;
    this.reconnectTimer = null;
    this.manualClose = false;
    this.hostName = "";
    this.protocolVersion = 0;
    this.bytesOut = 0;
    this.bytesIn = 0;
    this._lastStatsTime = performance.now();
    this._statsTimer = null;
    this.batteryLevel = 0;
    this.isCharging = false;
    this._initBattery();
  }

  async _initBattery() {
    if (!navigator.getBattery) return;
    try {
      const b = await navigator.getBattery();
      const update = () => {
        this.batteryLevel = Math.round(b.level * 100);
        this.isCharging = b.charging;
      };
      update();
      b.addEventListener("levelchange", update);
      b.addEventListener("chargingchange", update);
    } catch {}
  }

  get url() {
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const name = encodeURIComponent(getDeviceName());
    return `${proto}//${location.host}/ws?id=${encodeURIComponent(this.deviceId)}&name=${name}`;
  }

  connect() {
    this.manualClose = false;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING))
      return;

    this._emit("status", { state: "connecting" });
    let ws;
    try {
      ws = new WebSocket(this.url);
    } catch (e) {
      this._scheduleReconnect();
      return;
    }
    ws.binaryType = "arraybuffer";
    this.ws = ws;

    ws.onopen = () => {
      this.connected = true;
      this.reconnectDelay = 1000;
      // Handshake: send Hello (ClientToServer, type 0x00).
      const hello = encodeHello({
        protocolVersion: 1,
        deviceName: getDeviceName(),
        controllerIndex: 0,
        macAddress: this.deviceId,
      });
      this._sendFrame(TYPE_CLIENT_TO_SERVER, hello);
      this._emit("open", {});
      this._emit("status", { state: "connected" });
      this._startStats();
    };

    ws.onmessage = (ev) => {
      const buf = new Uint8Array(ev.data);
      if (buf.length < 1) return;
      this.bytesIn += buf.length;
      const type = buf[0];
      const payload = buf.subarray(1);
      if (type === TYPE_SERVER_TO_CLIENT) {
        let msg;
        try {
          msg = decodeServerToClient(payload);
        } catch {
          return;
        }
        this._handleServerMessage(msg);
      }
    };

    ws.onclose = () => {
      const wasConnected = this.connected;
      this.connected = false;
      this._stopStats();
      this._emit("status", { state: "disconnected" });
      if (wasConnected) this._emit("close", {});
      if (!this.manualClose) this._scheduleReconnect();
    };

    ws.onerror = () => {
      // onclose fires afterwards and drives the reconnect.
    };
  }

  disconnect() {
    this.manualClose = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      try {
        this.ws.close();
      } catch {}
      this.ws = null;
    }
    this.connected = false;
    this._emit("status", { state: "disconnected" });
  }

  _scheduleReconnect() {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 1.7, 5000);
  }

  _sendFrame(type, payload) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    const frame = new Uint8Array(payload.length + 1);
    frame[0] = type;
    frame.set(payload, 1);
    try {
      this.ws.send(frame);
      this.bytesOut += frame.length;
    } catch {}
  }

  sendGamepadInput(inp) {
    inp.batteryLevel = this.batteryLevel;
    inp.isCharging = this.isCharging;
    this._sendFrame(TYPE_GAMEPAD_INPUT, encodeGamepadInput(inp));
  }

  _handleServerMessage(msg) {
    switch (msg.kind) {
      case "serverHello":
        this.hostName = msg.serverHello.hostName;
        this.protocolVersion = msg.serverHello.protocolVersion;
        this._emit("serverHello", msg.serverHello);
        break;
      case "compactFrame":
        if (msg.compactFrame.vibration) this._emit("vibration", msg.compactFrame.vibration);
        if (msg.compactFrame.ledState) this._emit("led", msg.compactFrame.ledState);
        if (msg.compactFrame.testTone) this._emit("testTone", msg.compactFrame.testTone);
        if (msg.compactFrame.triggerEffects) this._emit("triggerEffects", msg.compactFrame.triggerEffects);
        break;
      case "ledState":
        this._emit("led", msg.ledState);
        break;
      case "testTone":
        this._emit("testTone", msg.testTone);
        break;
      case "triggerEffects":
        this._emit("triggerEffects", msg.triggerEffects);
        break;
      case "hdRumble":
        this._emit("hdRumble", msg.hdRumble);
        break;
      case "audioFrame":
        this._emit("audioFrame", msg.audioFrame);
        break;
      case "disconnect":
        this._emit("serverDisconnect", msg.reason || "");
        this.manualClose = true;
        try {
          this.ws?.close();
        } catch {}
        break;
    }
  }

  _startStats() {
    this._stopStats();
    this._statsTimer = setInterval(() => {
      this._emit("stats", {
        bytesOut: this.bytesOut,
        bytesIn: this.bytesIn,
      });
    }, 1000);
  }

  _stopStats() {
    if (this._statsTimer) {
      clearInterval(this._statsTimer);
      this._statsTimer = null;
    }
  }

  _emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }
}
