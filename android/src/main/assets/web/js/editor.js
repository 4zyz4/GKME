// GKME-Web — layout editor floating panel.
//
// Re-implements the UI of com.zyz4.gkme.view.FloatingEditorPanel: a draggable,
// collapsible panel with 保存 / 放弃 / 添加 actions and a scrollable area of
// per-control parameters. Android-only features (gyro, global settings page,
// sensitivity-curve editor) are intentionally omitted.

import { el } from "./dom.js";
import { viewportToLocal, elementLocalRect, localViewport } from "./orientation.js";
import { showConfirm } from "./dialog.js";
import {
  baseId, getControl, isKeypadId, bitName, OUTPUT_PICKER_GROUPS,
  controlDisplayName, isButtonId, isSettingsId, DOUBLE_CLICK_IDS, JOYSTICK_IDS,
  MOUSE_TAP_ACTIONS, MOUSE_SWIPE_ACTIONS, mouseActionLabel, CUSTOM_BUTTON_DEFAULT_TEXT,
} from "./controls.js";
import { t, keypadDefaultTexts } from "./i18n.js";
import { centerSizeOf } from "./layout.js";

function padTexts(b) {
  const defaults = keypadDefaultTexts();
  const texts = b.keypadTexts && b.keypadTexts.length >= 5 ? b.keypadTexts.slice() : defaults.slice();
  while (texts.length < 9) texts.push(defaults[texts.length]);
  return texts;
}

function padBits(b) {
  const arr = b.keypadBits && b.keypadBits.length >= 5 ? b.keypadBits.map((x) => (x || []).slice()) : [];
  while (arr.length < 9) arr.push([]);
  return arr;
}

function padTimings(b) {
  const arr = b.keypadComboTimings && b.keypadComboTimings.length >= 5 ? b.keypadComboTimings.slice() : [];
  while (arr.length < 9) arr.push("SIMULTANEOUS");
  return arr;
}

function padIntervals(b) {
  const arr = b.keypadComboIntervals && b.keypadComboIntervals.length >= 5 ? b.keypadComboIntervals.slice() : [];
  while (arr.length < 9) arr.push(16);
  return arr;
}

const ARROW_UP = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="#CCCCCC" d="M12 8l6 6H6z"/></svg>';
const ARROW_DOWN = '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path fill="#CCCCCC" d="M12 16l-6-6h12z"/></svg>';

export class LayoutEditor {
  constructor(app) {
    this.app = app;
    this.panel = document.getElementById("editpanel");
    this.scroll = document.getElementById("editScroll");
    this.actions = document.getElementById("editActions");
    this.params = document.getElementById("editParams");
    this.grip = document.getElementById("editGrip");
    this.collapseBtn = document.getElementById("editCollapse");

    this._collapsed = false;
    this._adjusting = false;
    this._current = null;
    this._sizeSyncers = [];

    this._buildActions();
    this._bindDrag();
    this._bindCollapse();
  }

  get isOpen() {
    return this.panel.classList.contains("show");
  }

  // ── open / close ──
  open() {
    const layout = this.app.layout;
    layout.enterEditMode();
    this._adjusting = false;
    this._updateActions();
    this._setCollapsed(false);
    this.panel.classList.remove("hidden");
    this.panel.classList.add("show");
    this._resetPosition();
    this.clearParams();
  }

  close() {
    this.app.layout.exitEditMode();
    this._adjusting = false;
    this._updateActions();
    this._current = null;
    this.panel.classList.remove("show");
    this.panel.classList.add("hidden");
  }

  _resetPosition() {
    this.panel.style.left = "";
    this.panel.style.right = "12px";
    this.panel.style.top = "10%";
  }

  // ── actions ──
  _buildActions() {
    this.actions.innerHTML = "";
    const mk = (label, fn) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "editbtn";
      b.textContent = label;
      b.addEventListener("click", fn);
      this.actions.appendChild(b);
      return b;
    };
    this.btnSave = mk(t("editor.save"), () => this._onSave());
    this.btnDiscard = mk(t("editor.discard"), () => this._onDiscard());
    this.btnAdd = mk(t("editor.add"), () => this.app._showAddControl());
    this.btnBack = mk(t("editor.back"), () => this.setAdjusting(false));
    this._updateActions();
  }

  /** Rebuild visible labels after the user switches language. */
  refreshLanguage() {
    this._buildActions();
    this._setCollapsed(this._collapsed);
    if (this._current) this.showParams(this._current);
  }

  _updateActions() {
    const adj = this._adjusting;
    this.btnSave.style.display = adj ? "none" : "";
    this.btnDiscard.style.display = adj ? "none" : "";
    this.btnAdd.style.display = adj ? "none" : "";
    this.btnBack.style.display = adj ? "" : "none";
  }

  _onSave() {
    this.app._saveCurrentPreset();
    this.close();
    this.app._toast(t("editor.saved"));
  }

  async _onDiscard() {
    const layout = this.app.layout;
    if (layout.hasUnsavedChanges() && !(await showConfirm(t("editor.discardTitle"), t("editor.discardConfirm")))) return;
    if (layout.hasUnsavedChanges()) layout.discardToSnapshot();
    this.close();
    this.app._toast(t("editor.discarded"));
  }

  // ── collapse / drag ──
  _bindCollapse() {
    this.collapseBtn.innerHTML = ARROW_UP;
    this.collapseBtn.addEventListener("click", () => this._setCollapsed(!this._collapsed));
  }

  _setCollapsed(collapsed) {
    this._collapsed = collapsed;
    this.panel.classList.toggle("collapsed", collapsed);
    this.collapseBtn.innerHTML = collapsed ? ARROW_DOWN : ARROW_UP;
  }

  _bindDrag() {
    let dragging = false;
    let sx = 0;
    let sy = 0;
    let ox = 0;
    let oy = 0;
    this.grip.addEventListener("pointerdown", (e) => {
      dragging = true;
      const r = elementLocalRect(this.panel);
      ox = r.left;
      oy = r.top;
      const p = viewportToLocal(e.clientX, e.clientY);
      sx = p.x;
      sy = p.y;
      this.panel.style.right = "auto";
      this.panel.style.left = ox + "px";
      this.panel.style.top = oy + "px";
      try { this.grip.setPointerCapture(e.pointerId); } catch {}
    });
    this.grip.addEventListener("pointermove", (e) => {
      if (!dragging) return;
      const { w, h } = localViewport();
      const pw = this.panel.offsetWidth;
      const ph = this.panel.offsetHeight;
      const p = viewportToLocal(e.clientX, e.clientY);
      const nx = Math.min(Math.max(0, ox + (p.x - sx)), Math.max(0, w - pw));
      const ny = Math.min(Math.max(0, oy + (p.y - sy)), Math.max(0, h - ph));
      this.panel.style.left = nx + "px";
      this.panel.style.top = ny + "px";
    });
    const stop = () => { dragging = false; };
    this.grip.addEventListener("pointerup", stop);
    this.grip.addEventListener("pointercancel", stop);
  }

  // ── selection ──
  onSelect(id) {
    if (!id) {
      this.clearParams();
      return;
    }
    this.showParams(this.app.layout.getButton(id));
  }

  onEditChanged() {
    for (const s of this._sizeSyncers) s.set(s.get());
  }

  onEditEnd() {
    if (this._current) this.showParams(this._current);
  }

  clearParams() {
    this._sizeSyncers = [];
    this._current = null;
    this.params.innerHTML = "";
  }

  // ── geometry helpers ──
  _screenSize(b) {
    const swapped = !b.lockAspect && (b.rotation === 90 || b.rotation === 270);
    return swapped ? [b.h, b.w] : [b.w, b.h];
  }

  _growArea(b, width, height) {
    const [sw, sh] = this._screenSize({ ...b, w: width, h: height });
    return {
      followAreaW: Math.max(b.followAreaW, b.x + sw - b.followAreaX),
      followAreaH: Math.max(b.followAreaH, b.y + sh - b.followAreaY),
    };
  }

  _update(id, props) {
    this.app.layout.updateButton(id, props);
  }

  setAdjusting(on) {
    const layout = this.app.layout;
    this._adjusting = on;
    if (on) layout.enterFollowAreaAdjust(this._current ? this._current.id : layout.selectedId);
    else layout.exitFollowAreaAdjust();
    this._updateActions();
    if (this._current) this.showParams(this._current);
  }

  // ── parameter widgets ──
  _section(text) {
    return el("div", { class: "editseclabel", text });
  }

  _seek({ label, value, min, max, step = 1, onChange }) {
    const state = { value: Math.min(max, Math.max(min, value)) };
    const wrap = el("div", { class: "editseek" });
    const row = el("div", { class: "editseekrow" });
    const lab = el("span", { class: "editseeklabel", text: label });
    const input = el("input", { class: "editnum", type: "text", inputmode: step < 1 ? "decimal" : "numeric" });
    const minus = el("button", { class: "editstep", type: "button", text: "-" });
    const plus = el("button", { class: "editstep", type: "button", text: "+" });
    const slider = el("input", { class: "editslider", type: "range", min: String(min), max: String(max), step: String(step) });

    const commit = (v, fire = true) => {
      let n = Math.min(max, Math.max(min, v));
      n = step < 1 ? Math.round(n * 100) / 100 : Math.round(n);
      state.value = n;
      input.value = String(n);
      slider.value = String(n);
      if (fire) onChange(n);
    };
    const parse = (s) => {
      const n = parseFloat(s);
      return Number.isNaN(n) ? state.value : n;
    };
    input.addEventListener("change", () => commit(parse(input.value)));
    input.addEventListener("blur", () => commit(parse(input.value)));
    minus.addEventListener("click", () => commit(state.value - step));
    plus.addEventListener("click", () => commit(state.value + step));
    slider.addEventListener("input", () => commit(parseFloat(slider.value)));
    commit(value, false);

    row.append(lab, input, minus, plus);
    wrap.append(row, slider);
    return { el: wrap, set: (v) => commit(v, false) };
  }

  _check(label, checked, onChange) {
    const row = el("div", { class: "editcheck" });
    const txt = el("span", { class: "editchecklabel", text: label });
    const sw = el("label", { class: "switch" });
    const input = el("input", { type: "checkbox" });
    input.checked = !!checked;
    const sl = el("span", { class: "slider" });
    input.addEventListener("change", () => onChange(input.checked));
    sw.append(input, sl);
    row.append(txt, sw);
    return row;
  }

  _spinner(label, options, value, onChange) {
    const wrap = el("div", { class: "editspinner" });
    if (label) wrap.append(this._section(label));
    const sel = el("select", { class: "editsel" });
    for (const [val, text] of options) {
      const o = el("option", { value: val, text });
      sel.appendChild(o);
    }
    sel.value = value;
    sel.addEventListener("change", () => onChange(sel.value));
    wrap.append(sel);
    return wrap;
  }

  _text(label, value, onChange) {
    const wrap = el("div", { class: "edittext" });
    if (label) wrap.append(this._section(label));
    const input = el("input", { class: "edittextinput", type: "text", value: value || "" });
    const commit = () => onChange(input.value);
    input.addEventListener("change", commit);
    input.addEventListener("blur", commit);
    wrap.append(input);
    return wrap;
  }

  _btnRow(pairs) {
    const row = el("div", { class: "editbtnrow" });
    for (const [label, fn] of pairs) {
      const b = el("button", { class: "editbtn", type: "button", text: label });
      b.addEventListener("click", fn);
      row.append(b);
    }
    return row;
  }

  _bitsEditor(bits, onChanged) {
    const box = el("div", { class: "editchips" });
    if (bits.length) {
      for (const bit of bits) {
        const c = el("span", { class: "chip", text: bitName(bit) });
        c.addEventListener("click", () => onChanged(bits.filter((x) => x !== bit)));
        box.append(c);
      }
    }
    const row = this._btnRow([
      [t("editor.addChip"), () => this._showOutputPicker(bits, (newBits) => onChanged(newBits))],
      [t("editor.clearChip"), () => onChanged([])],
    ]);
    const wrap = el("div", {});
    wrap.append(box, row);
    return wrap;
  }

  /** Chord timing selector; the interval slider is only shown for CUSTOM_INTERVAL. */
  _comboTimingEditor(timing, intervalMs, onChange) {
    const currentTiming = timing || "SIMULTANEOUS";
    const currentInterval = Math.max(1, Math.min(100, Number(intervalMs) || 16));
    const seekWrap = el("div", {
      style: { display: currentTiming === "CUSTOM_INTERVAL" ? "block" : "none" },
    });
    const spinner = this._spinner(
      t("editor.comboTiming"),
      [
        ["SIMULTANEOUS", t("editor.comboSimultaneous")],
        ["FRAME_BY_FRAME", t("editor.comboFramed")],
        ["CUSTOM_INTERVAL", t("editor.comboCustom")],
      ],
      currentTiming,
      (value) => {
        seekWrap.style.display = value === "CUSTOM_INTERVAL" ? "block" : "none";
        onChange({ comboTiming: value });
      }
    );
    seekWrap.append(
      this._seek({
        label: t("editor.comboInterval"),
        value: currentInterval,
        min: 1,
        max: 100,
        onChange: (value) => onChange({ comboIntervalMs: value }),
      }).el
    );
    const box = el("div", {});
    box.append(spinner, seekWrap);
    return box;
  }

  _showOutputPicker(currentBits, onResult) {
    const overlay = el("div", { class: "modal" });
    const box = el("div", { class: "modalbox" });
    box.append(el("div", { class: "modaltitle", text: t("editor.mappedKeys") }));
    const grid = el("div", { class: "pickergrid" });
    const sel = new Set(currentBits || []);
    for (const group of OUTPUT_PICKER_GROUPS) {
      grid.append(el("div", { class: "pickergrouptitle", text: t(group.titleKey) }));
      for (const bit of group.bits) {
        const c = el("span", { class: "pickerchip" + (sel.has(bit) ? " on" : ""), text: bitName(bit) });
        c.addEventListener("click", () => {
          if (sel.has(bit)) { sel.delete(bit); c.classList.remove("on"); }
          else { sel.add(bit); c.classList.add("on"); }
        });
        grid.append(c);
      }
    }
    box.append(grid);
    const ok = el("button", { class: "modalclose", type: "button", text: t("common.ok") });
    ok.addEventListener("click", () => { onResult(Array.from(sel)); overlay.remove(); });
    box.append(ok);
    overlay.append(box);
    overlay.addEventListener("click", (e) => { if (e.target === overlay) overlay.remove(); });
    document.body.appendChild(overlay);
  }

  // ── parameter list ──
  showParams(button) {
    this._sizeSyncers = [];
    this.params.innerHTML = "";
    this._current = button;
    if (!button) return;

    const id = button.id;
    const base = baseId(id);
    const isSettings = isSettingsId(id);

    this.params.append(el("div", { class: "editctrlname", text: controlDisplayName(id) }));

    if (this._adjusting) {
      this._buildFollowAreaAdjust(button);
      return;
    }

    // ── Size ──
    if (button.lockAspect) {
      const sizeMax = 40;
      const s = this._seek({
        label: t("editor.size"),
        value: button.w,
        min: 1,
        max: sizeMax,
        onChange: (v) => this._update(id, { w: v, h: v }),
      });
      this._sizeSyncers.push({ get: () => this._current.w, set: s.set });
      this.params.append(s.el);
    } else {
      const isSwapped = button.rotation === 90 || button.rotation === 270;
      const sizeMax = 120;
      const wSeek = this._seek({
        label: t("editor.width"),
        value: isSwapped ? button.h : button.w,
        min: 1,
        max: sizeMax,
        onChange: (v) => {
          const props = isSwapped ? { h: v } : { w: v };
          if (base === "touchpad" && button.followAreaEnabled) {
            Object.assign(props, this._growArea(button, props.w || button.w, props.h || button.h));
          }
          this._update(id, props);
        },
      });
      const hSeek = this._seek({
        label: t("editor.height"),
        value: isSwapped ? button.w : button.h,
        min: 1,
        max: sizeMax,
        onChange: (v) => {
          const props = isSwapped ? { w: v } : { h: v };
          if (base === "touchpad" && button.followAreaEnabled) {
            Object.assign(props, this._growArea(button, props.w || button.w, props.h || button.h));
          }
          this._update(id, props);
        },
      });
      this._sizeSyncers.push({ get: () => (isSwapped ? this._current.h : this._current.w), set: wSeek.set });
      this._sizeSyncers.push({ get: () => (isSwapped ? this._current.w : this._current.h), set: hSeek.set });
      this.params.append(wSeek.el, hSeek.el);
    }

    // ── Rotation (not for the settings button) ──
    if (!isSettings) {
      const row = el("div", { class: "editrot" });
      const mkRot = (text, delta) => {
        const col = el("div", { class: "editrotcol" });
        const b = el("button", { class: "editrotbtn", type: "button", text });
        b.addEventListener("click", () => {
          const cur = this.app.layout.getButton(id);
          const rotation = ((cur.rotation + delta) % 360 + 360) % 360;
          this._update(id, { rotation });
          this.showParams(this._current);
        });
        const lab = el("div", { class: "editrotlabel", text: delta < 0 ? t("editor.counterclockwise") : t("editor.clockwise") });
        col.append(b, lab);
        return col;
      };
      row.append(mkRot("↺", -90), mkRot("↻", 90));
      this.params.append(row);
    }

    // ── Opacity ──
    if (!isSettings) {
      this.params.append(
        this._seek({
          label: t("editor.idleOpacity"),
          value: button.idleOpacity,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { idleOpacity: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: t("editor.activeOpacity"),
          value: button.activeOpacity,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { activeOpacity: v }),
        }).el
      );
    }

    // ── Overlap trigger ──
    if (!isSettings) {
      this.params.append(this._check(t("editor.overlapTrigger"), button.overlapTrigger, (v) => this._update(id, { overlapTrigger: v })));
    }

    // ── Auto hold ──
    if ((isButtonId(id) || getControl(id)?.isKeyboard) && !isSettings) {
      this.params.append(this._check(t("editor.autoHold"), button.autoHold, (v) => this._update(id, { autoHold: v })));
    }

    // ── Double click ──
    if (DOUBLE_CLICK_IDS.has(base)) {
      this.params.append(this._check(t("editor.doubleTap"), button.doubleClickEnable, (v) => this._update(id, { doubleClickEnable: v })));
    }

    // ── Follow area ──
    if (base === "touchpad") {
      this.params.append(
        this._check(t("editor.extendedTouch"), button.followAreaEnabled, (checked) => {
          const cur = this._current;
          let props;
          if (checked) {
            const [sw, sh] = this._screenSize(cur);
            const contained =
              cur.followAreaW > 0 && cur.followAreaH > 0 &&
              cur.x >= cur.followAreaX && cur.y >= cur.followAreaY &&
              cur.x + sw <= cur.followAreaX + cur.followAreaW &&
              cur.y + sh <= cur.followAreaY + cur.followAreaH;
            props = contained
              ? { followAreaEnabled: true }
              : { followAreaEnabled: true, followAreaX: cur.x, followAreaY: cur.y, followAreaW: sw, followAreaH: sh };
          } else {
            props = { followAreaEnabled: false };
          }
          this._update(id, props);
          this.showParams(this._current);
        })
      );
      if (button.followAreaEnabled) this.params.append(this._btnRow([[t("editor.enterAdjust"), () => this.setAdjusting(true)]]));
    } else if (JOYSTICK_IDS.has(base) || base === "dpadPad" || isKeypadId(id)) {
      this.params.append(
        this._check(t("editor.followInside"), button.followAreaEnabled, (checked) => {
          const cur = this._current;
          const props =
            checked && (cur.followAreaW === 0 || cur.followAreaH === 0)
              ? { followAreaEnabled: true, followAreaX: cur.x, followAreaY: cur.y, followAreaW: cur.w, followAreaH: cur.h }
              : { followAreaEnabled: checked };
          this._update(id, props);
          this.showParams(this._current);
        })
      );
      if (button.followAreaEnabled) this.params.append(this._btnRow([[t("editor.enterAdjust"), () => this.setAdjusting(true)]]));
    }

    // ── Mousepad gestures & tuning ──
    if (base === "mousepad") {
      const picker = (key, labelKey, actions) => {
        this.params.append(
          this._spinner(
            t(labelKey),
            actions.map((a) => [a, mouseActionLabel(a)]),
            button[key] || "NONE",
            (v) => this._update(id, { [key]: v })
          )
        );
      };
      picker("singleTapAction", "editor.singleTap", MOUSE_TAP_ACTIONS);
      picker("twoFingerTapAction", "editor.twoFingerTap", MOUSE_TAP_ACTIONS);
      picker("threeFingerTapAction", "editor.threeFingerTap", MOUSE_TAP_ACTIONS);
      picker("doubleTapDragAction", "editor.doubleTapDrag", MOUSE_SWIPE_ACTIONS);
      picker("oneFingerSwipeAction", "editor.oneFingerSwipe", MOUSE_SWIPE_ACTIONS);
      picker("twoFingerSwipeAction", "editor.twoFingerSwipe", MOUSE_SWIPE_ACTIONS);
      picker("threeFingerSwipeAction", "editor.threeFingerSwipe", MOUSE_SWIPE_ACTIONS);
      this.params.append(
        this._seek({
          label: t("editor.mouseSensitivity"),
          value: button.mouseSensitivity ?? 1,
          min: 0.1,
          max: 3,
          step: 0.05,
          onChange: (v) => this._update(id, { mouseSensitivity: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: t("editor.swipeThreshold"),
          value: button.mouseMoveSlop ?? 4,
          min: 0,
          max: 20,
          onChange: (v) => this._update(id, { mouseMoveSlop: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: t("editor.scrollSensitivity"),
          value: button.scrollSensitivity ?? 0.1,
          min: 0.01,
          max: 1,
          step: 0.01,
          onChange: (v) => this._update(id, { scrollSensitivity: v }),
        }).el
      );
      const accel = Array.isArray(button.mouseAcceleration) && button.mouseAcceleration.length > 1
        ? Number(button.mouseAcceleration[1]) || 0
        : 0;
      this.params.append(
        this._seek({
          label: t("editor.pointerAcceleration"),
          value: accel,
          min: 0,
          max: 0.5,
          step: 0.01,
          onChange: (v) => this._update(id, { mouseAcceleration: [1, v] }),
        }).el
      );
      this.params.append(
        this._check(t("editor.invertScrollV"), button.invertScrollV, (v) => this._update(id, { invertScrollV: v }))
      );
      this.params.append(
        this._check(t("editor.invertScrollH"), button.invertScrollH, (v) => this._update(id, { invertScrollH: v }))
      );
    }

    // ── Integrated D-pad centre size ──
    if (base === "dpadPad") {
      this.params.append(
        this._seek({
          label: t("editor.centerSize"),
          value: centerSizeOf(button),
          min: 0,
          max: 70,
          onChange: (v) => this._update(id, { centerSize: v }),
        }).el
      );
    }

    // ── Joystick tuning ──
    if (JOYSTICK_IDS.has(base)) {
      this.params.append(
        this._seek({
          label: t("editor.sensitivity"),
          value: button.joystickSensitivity,
          min: 10,
          max: 300,
          onChange: (v) => this._update(id, { joystickSensitivity: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: t("editor.deadZone"),
          value: button.deadZone,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { deadZone: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: t("editor.antiDeadZone"),
          value: button.reverseDeadZone,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { reverseDeadZone: v }),
        }).el
      );
      this.params.append(this._check(t("editor.touchpadMode"), button.touchpadMode, (v) => this._update(id, { touchpadMode: v })));
      this.params.append(this._check(t("editor.fastResponse"), button.prediction, (v) => this._update(id, { prediction: v })));
    }

    // ── Keypad ──
    if (isKeypadId(id)) {
      this._buildKeypadParams(button);
    }

    // ── Triggers ──
    if (isButtonId(id) || getControl(id)?.isKeyboard) {
      this._buildTriggerParams(button);
    }

    // ── Custom button ──
    if (button.isCustom) {
      this._buildCustomParams(button);
    }

    // ── Delete ──
    if (!isSettings) {
      this.params.append(this._btnRow([[t("editor.deleteControl"), () => this._delete()]]));
    }
  }

  async _delete() {
    const id = this._current && this._current.id;
    if (!id) return;
    if (!(await showConfirm(t("editor.deleteControlTitle"), t("editor.deleteControlConfirm"), { positiveText: t("editor.deleteControl") }))) return;
    const layout = this.app.layout;
    layout.buttons = layout.buttons.filter((b) => b.id !== id);
    layout.selectedId = null;
    layout.render();
    this.app._positionStatus();
    this.clearParams();
  }

  _buildFollowAreaAdjust(button) {
    const id = button.id;
    const maxW = Math.max(40, button.followAreaW);
    const maxH = Math.max(40, button.followAreaH);
    this.params.append(
      this._seek({
        label: t("editor.areaWidth"),
        value: button.followAreaW,
        min: 1,
        max: maxW,
        onChange: (v) => this._update(id, { followAreaW: v }),
      }).el
    );
    this.params.append(
      this._seek({
        label: t("editor.areaHeight"),
        value: button.followAreaH,
        min: 1,
        max: maxH,
        onChange: (v) => this._update(id, { followAreaH: v }),
      }).el
    );
    this.params.append(
      this._seek({
        label: t("editor.followAreaOpacity"),
        value: button.followAreaOpacity,
        min: 0,
        max: 100,
        onChange: (v) => this._update(id, { followAreaOpacity: v }),
      }).el
    );
    if (baseId(id) !== "touchpad") {
      this.params.append(
        this._check(t("editor.triggerOverlap"), button.followAreaOverlapTrigger, (v) => this._update(id, { followAreaOverlapTrigger: v }))
      );
    }
  }

  _buildKeypadParams(button) {
    const id = button.id;
    const texts = padTexts(button);
    const bits = padBits(button);

    this.params.append(
      this._spinner(t("editor.directionMode"), [["4", t("editor.dir4")], ["8", t("editor.dir8")]], button.keypadEightWay ? "8" : "4", (v) => {
        const eight = v === "8";
        this._update(id, { keypadEightWay: eight });
        this.showParams(this._current);
      })
    );
    this.params.append(
      this._spinner(t("editor.centerShape"), [["SQUARE", t("editor.square")], ["CIRCLE", t("editor.circle")]], button.centerShape || "SQUARE", (v) => {
        this._update(id, { centerShape: v });
        this.showParams(this._current);
      })
    );
    this.params.append(
      this._seek({
        label: t("editor.centerSize"),
        value: centerSizeOf(button),
        min: 0,
        max: 70,
        onChange: (v) => this._update(id, { centerSize: v }),
      }).el
    );

    const dirs = [
      [t("editor.up"), 0], [t("editor.down"), 1], [t("editor.left"), 2], [t("editor.right"), 3],
    ];
    if (button.keypadEightWay) dirs.push([t("editor.upLeft"), 5], [t("editor.upRight"), 6], [t("editor.downLeft"), 7], [t("editor.downRight"), 8]);
    dirs.push([t("editor.doubleTapCenter"), 4]);

    for (const [name, idx] of dirs) {
      this.params.append(this._section(name));
      this.params.append(
        this._text(null, texts[idx], (v) => {
          const next = padTexts(this._current);
          next[idx] = v;
          this._update(id, { keypadTexts: next });
        })
      );
      if (idx !== 4) {
        const regionBits = bits[idx];
        this.params.append(
          this._bitsEditor(regionBits, (newBits) => {
            const b = padBits(this._current);
            b[idx] = newBits;
            this._update(id, { keypadBits: b });
            this.showParams(this._current);
          })
        );
        this.params.append(this._keypadTimingEditor(button, idx));
      }
    }
    this.params.append(
      this._bitsEditor(bits[4], (newBits) => {
        const b = padBits(this._current);
        b[4] = newBits;
        this._update(id, { keypadBits: b });
        this.showParams(this._current);
      })
    );
    this.params.append(this._keypadTimingEditor(button, 4));
  }

  /** Combo timing for one keypad region; timing arrays are stored per region. */
  _keypadTimingEditor(button, region) {
    const id = button.id;
    const timings = padTimings(button);
    const intervals = padIntervals(button);
    return this._comboTimingEditor(timings[region], intervals[region], (props) => {
      const nextTimings = padTimings(this._current);
      const nextIntervals = padIntervals(this._current);
      if (props.comboTiming !== undefined) nextTimings[region] = props.comboTiming;
      if (props.comboIntervalMs !== undefined) nextIntervals[region] = props.comboIntervalMs;
      this._update(id, {
        keypadComboTimings: nextTimings,
        keypadComboIntervals: nextIntervals,
      });
    });
  }

  _buildTriggerParams(button) {
    const id = button.id;
    const base = baseId(id);
    const isTrigger = base === "btnLT" || base === "btnRT";

    this.params.append(
      this._check(t("editor.swipeTrigger"), button.swipeTrigger && !button.linearTriggerEnabled, (v) => {
        this._update(id, { swipeTrigger: v, ...(isTrigger ? { linearTriggerEnabled: false } : {}) });
        this.showParams(this._current);
      })
    );

    if (isTrigger) {
      this.params.append(
        this._check(t("editor.emulateLinear"), button.linearTriggerEnabled, (v) => {
          this._update(id, { linearTriggerEnabled: v, swipeTrigger: v ? false : button.swipeTrigger });
          this.showParams(this._current);
        })
      );
      if (button.linearTriggerEnabled) {
        const dirOptions = [
          ["DOWN", t("editor.slideDown")], ["UP", t("editor.slideUp")], ["LEFT", t("editor.slideLeft")], ["RIGHT", t("editor.slideRight")],
        ];
        this.params.append(
          this._spinner(t("editor.swipeDirection"), dirOptions, button.slideDirection || "DOWN", (v) => this._update(id, { slideDirection: v }))
        );
        this.params.append(this._section(t("editor.triggerTravel")));
        this.params.append(
          this._seek({
            label: t("editor.travel"),
            value: button.travelDistance,
            min: 1,
            max: 40,
            onChange: (v) => this._update(id, { travelDistance: v }),
          }).el
        );
        this.params.append(this._check(t("editor.fastResponse"), button.prediction, (v) => this._update(id, { prediction: v })));
      }
    }
  }

  _buildCustomParams(button) {
    const id = button.id;
    this.params.append(
      this._text(t("editor.displayText"), button.customText || CUSTOM_BUTTON_DEFAULT_TEXT, (v) => this._update(id, { customText: v || CUSTOM_BUTTON_DEFAULT_TEXT }))
    );
    this.params.append(el("div", { class: "editseclabel", text: t("editor.mappedKeys") }));
    this.params.append(
      this._bitsEditor((button.customBits || []).slice(), (newBits) => {
        this._update(id, { customBits: newBits });
        this.showParams(this._current);
      })
    );
    this.params.append(
      this._comboTimingEditor(button.comboTiming, button.comboIntervalMs, (props) => this._update(id, props))
    );
  }
}
