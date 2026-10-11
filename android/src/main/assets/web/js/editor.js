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
  chineseNameFor, isButtonId, isSettingsId, DOUBLE_CLICK_IDS, JOYSTICK_IDS,
} from "./controls.js";

const KEYPAD_DEFAULT_TEXTS = ["上", "下", "左", "右", "中", "左上", "右上", "左下", "右下"];

function padTexts(b) {
  const t = b.keypadTexts && b.keypadTexts.length >= 5 ? b.keypadTexts.slice() : KEYPAD_DEFAULT_TEXTS.slice();
  while (t.length < 9) t.push(KEYPAD_DEFAULT_TEXTS[t.length]);
  return t;
}

function padBits(b) {
  const arr = b.keypadBits && b.keypadBits.length >= 5 ? b.keypadBits.map((x) => (x || []).slice()) : [];
  while (arr.length < 9) arr.push([]);
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
    const mk = (label, fn) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "editbtn";
      b.textContent = label;
      b.addEventListener("click", fn);
      this.actions.appendChild(b);
      return b;
    };
    this.btnSave = mk("保存", () => this._onSave());
    this.btnDiscard = mk("放弃", () => this._onDiscard());
    this.btnAdd = mk("+ 添加", () => this.app._showAddControl());
    this.btnBack = mk("返回", () => this.setAdjusting(false));
    this._updateActions();
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
    this.app._toast("已保存布局");
  }

  async _onDiscard() {
    const layout = this.app.layout;
    if (layout.hasUnsavedChanges() && !(await showConfirm("放弃修改", "确定放弃当前布局修改？"))) return;
    if (layout.hasUnsavedChanges()) layout.discardToSnapshot();
    this.close();
    this.app._toast("已放弃修改");
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
      ["添加", () => this._showOutputPicker(bits, (newBits) => onChanged(newBits))],
      ["清空", () => onChanged([])],
    ]);
    const wrap = el("div", {});
    wrap.append(box, row);
    return wrap;
  }

  _showOutputPicker(currentBits, onResult) {
    const overlay = el("div", { class: "modal" });
    const box = el("div", { class: "modalbox" });
    box.append(el("div", { class: "modaltitle", text: "映射键值" }));
    const grid = el("div", { class: "pickergrid" });
    const sel = new Set(currentBits || []);
    for (const group of OUTPUT_PICKER_GROUPS) {
      grid.append(el("div", { class: "pickergrouptitle", text: group.title }));
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
    const ok = el("button", { class: "modalclose", type: "button", text: "确定" });
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

    this.params.append(el("div", { class: "editctrlname", text: chineseNameFor(id) }));

    if (this._adjusting) {
      this._buildFollowAreaAdjust(button);
      return;
    }

    // ── Size ──
    if (button.lockAspect) {
      const sizeMax = 40;
      const s = this._seek({
        label: "大小",
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
        label: "宽度",
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
        label: "高度",
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
        const lab = el("div", { class: "editrotlabel", text: delta < 0 ? "逆时针" : "顺时针" });
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
          label: "空闲时不透明度(%)",
          value: button.idleOpacity,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { idleOpacity: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: "操作时不透明度(%)",
          value: button.activeOpacity,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { activeOpacity: v }),
        }).el
      );
    }

    // ── Overlap trigger ──
    if (!isSettings) {
      this.params.append(this._check("重叠区域触发", button.overlapTrigger, (v) => this._update(id, { overlapTrigger: v })));
    }

    // ── Auto hold ──
    if ((isButtonId(id) || getControl(id)?.isKeyboard) && !isSettings) {
      this.params.append(this._check("自动保持", button.autoHold, (v) => this._update(id, { autoHold: v })));
    }

    // ── Double click ──
    if (DOUBLE_CLICK_IDS.has(base)) {
      this.params.append(this._check("双击按下", button.doubleClickEnable, (v) => this._update(id, { doubleClickEnable: v })));
    }

    // ── Follow area ──
    if (base === "touchpad") {
      this.params.append(
        this._check("扩展触摸范围", button.followAreaEnabled, (checked) => {
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
      if (button.followAreaEnabled) this.params.append(this._btnRow([["进入调节", () => this.setAdjusting(true)]]));
    } else if (JOYSTICK_IDS.has(base) || base === "dpadPad" || isKeypadId(id)) {
      this.params.append(
        this._check("矩形区域内跟随", button.followAreaEnabled, (checked) => {
          const cur = this._current;
          const props =
            checked && (cur.followAreaW === 0 || cur.followAreaH === 0)
              ? { followAreaEnabled: true, followAreaX: cur.x, followAreaY: cur.y, followAreaW: cur.w, followAreaH: cur.h }
              : { followAreaEnabled: checked };
          this._update(id, props);
          this.showParams(this._current);
        })
      );
      if (button.followAreaEnabled) this.params.append(this._btnRow([["进入调节", () => this.setAdjusting(true)]]));
    }

    // ── Joystick tuning ──
    if (JOYSTICK_IDS.has(base)) {
      this.params.append(
        this._seek({
          label: "灵敏度(%)",
          value: button.joystickSensitivity,
          min: 10,
          max: 300,
          onChange: (v) => this._update(id, { joystickSensitivity: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: "死区(%)",
          value: button.deadZone,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { deadZone: v }),
        }).el
      );
      this.params.append(
        this._seek({
          label: "反死区(%)",
          value: button.reverseDeadZone,
          min: 0,
          max: 100,
          onChange: (v) => this._update(id, { reverseDeadZone: v }),
        }).el
      );
      this.params.append(this._check("触摸板模式", button.touchpadMode, (v) => this._update(id, { touchpadMode: v })));
      this.params.append(this._check("快速响应模式", button.prediction, (v) => this._update(id, { prediction: v })));
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
      this.params.append(this._btnRow([["删除", () => this._delete()]]));
    }
  }

  async _delete() {
    const id = this._current && this._current.id;
    if (!id) return;
    if (!(await showConfirm("删除控件", "确定删除该控件？", { positiveText: "删除" }))) return;
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
        label: "区域宽度",
        value: button.followAreaW,
        min: 1,
        max: maxW,
        onChange: (v) => this._update(id, { followAreaW: v }),
      }).el
    );
    this.params.append(
      this._seek({
        label: "区域高度",
        value: button.followAreaH,
        min: 1,
        max: maxH,
        onChange: (v) => this._update(id, { followAreaH: v }),
      }).el
    );
    this.params.append(
      this._seek({
        label: "矩形区域不透明度(%)",
        value: button.followAreaOpacity,
        min: 0,
        max: 100,
        onChange: (v) => this._update(id, { followAreaOpacity: v }),
      }).el
    );
    if (baseId(id) !== "touchpad") {
      this.params.append(
        this._check("触发矩形区域重叠触发", button.followAreaOverlapTrigger, (v) => this._update(id, { followAreaOverlapTrigger: v }))
      );
    }
  }

  _buildKeypadParams(button) {
    const id = button.id;
    const texts = padTexts(button);
    const bits = padBits(button);

    this.params.append(
      this._spinner("方向模式", [["4", "4方向"], ["8", "8方向"]], button.keypadEightWay ? "8" : "4", (v) => {
        const eight = v === "8";
        this._update(id, { keypadEightWay: eight });
        this.showParams(this._current);
      })
    );
    this.params.append(
      this._spinner("中心形状", [["SQUARE", "方形"], ["CIRCLE", "圆形"]], button.centerShape || "SQUARE", (v) => {
        this._update(id, { centerShape: v });
        this.showParams(this._current);
      })
    );

    const dirs = [
      ["上方向", 0], ["下方向", 1], ["左方向", 2], ["右方向", 3],
    ];
    if (button.keypadEightWay) dirs.push(["左上方向", 5], ["右上方向", 6], ["左下方向", 7], ["右下方向", 8]);
    dirs.push(["双击中心", 4]);

    for (const [name, idx] of dirs) {
      this.params.append(this._section(name));
      this.params.append(
        this._text(null, texts[idx], (v) => {
          const t = padTexts(this._current);
          t[idx] = v;
          this._update(id, { keypadTexts: t });
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
  }

  _buildTriggerParams(button) {
    const id = button.id;
    const base = baseId(id);
    const isTrigger = base === "btnLT" || base === "btnRT";

    this.params.append(
      this._check("滑动触发", button.swipeTrigger && !button.linearTriggerEnabled, (v) => {
        this._update(id, { swipeTrigger: v, ...(isTrigger ? { linearTriggerEnabled: false } : {}) });
        this.showParams(this._current);
      })
    );

    if (isTrigger) {
      this.params.append(
        this._check("模拟线性扳机", button.linearTriggerEnabled, (v) => {
          this._update(id, { linearTriggerEnabled: v, swipeTrigger: v ? false : button.swipeTrigger });
          this.showParams(this._current);
        })
      );
      if (button.linearTriggerEnabled) {
        const dirOptions = [
          ["DOWN", "向下"], ["UP", "向上"], ["LEFT", "向左"], ["RIGHT", "向右"],
        ];
        this.params.append(
          this._spinner("滑动方向", dirOptions, button.slideDirection || "DOWN", (v) => this._update(id, { slideDirection: v }))
        );
        this.params.append(this._section("扳机行程"));
        this.params.append(
          this._seek({
            label: "行程",
            value: button.travelDistance,
            min: 1,
            max: 40,
            onChange: (v) => this._update(id, { travelDistance: v }),
          }).el
        );
        this.params.append(this._check("快速响应模式", button.prediction, (v) => this._update(id, { prediction: v })));
      }
    }
  }

  _buildCustomParams(button) {
    const id = button.id;
    this.params.append(
      this._text("显示文本", button.customText || "自定义", (v) => this._update(id, { customText: v || "自定义" }))
    );
    this.params.append(el("div", { class: "editseclabel", text: "映射键值" }));
    this.params.append(
      this._bitsEditor((button.customBits || []).slice(), (newBits) => {
        this._update(id, { customBits: newBits });
        this.showParams(this._current);
      })
    );
  }
}
