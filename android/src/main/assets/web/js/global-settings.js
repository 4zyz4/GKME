// GKME-Web — per-layout "全局设置" page opened from the layout editor.
//
// Mirrors com.zyz4.gkme.view.LayoutGlobalSettingsPanel: a full-screen,
// 80%-opaque page with a category sidebar. The page acts on the layout preset
// currently being edited. The gyro and volume-key pages of the Android panel
// are not ported (the browser has no gyro stream and volume keys are out of
// scope), and the sensitivity-curve editors are omitted by design.

import { el } from "./dom.js";
import { t } from "./i18n.js";
import { showOutputPicker } from "./picker.js";
import { bitName, MOUSE_TAP_ACTIONS, MOUSE_SWIPE_ACTIONS, mouseActionLabel } from "./controls.js";
import { clampGridDensity, MAX_GRID_DENSITY, MIN_GRID_DENSITY } from "./layout.js";
import {
  PHYSICAL_INPUTS, PHYSICAL_MAPPING_GROUPS, PHYSICAL_KIND, physicalInputLabel,
  mappingOutputs, mappingTiming, isDefaultMapping,
  STICK_MODE, STICK_MODES, stickModeLabel, directionsForMode, stickDirectionLabel,
  directionAt, withDirection, normalizeStickSettings, stripStickSettings,
  normalizeTouchpadSettings, stripTouchpadSettings, TOUCHPAD_MODE,
  MIN_MOUSE_SENSITIVITY, MAX_MOUSE_SENSITIVITY,
} from "./physical.js";

const CHEVRON_RIGHT = "\u25B8";
const CHEVRON_DOWN = "\u25BE";

export class GlobalSettingsPanel {
  constructor(app) {
    this.app = app;
    this.root = document.getElementById("globalsettings");
    this.navList = document.getElementById("gsNavList");
    this.body = document.getElementById("gsBody");
    this.closeBtn = document.getElementById("gsClose");
    this.tabs = [
      { key: "grid", labelKey: "global.grid" },
      { key: "physical", labelKey: "global.physical" },
    ];
    this.currentTab = 0;
    // Expansion state survives the list rebuilds triggered by every edit
    // (mirrors LayoutGlobalSettingsPanel.physicalMappingExpanded*).
    this.expandedGroups = new Set();
    this.expandedInputs = new Set();
    this._closeTimer = null;
    if (this.closeBtn) this.closeBtn.addEventListener("click", () => this.app._closeLayoutGlobalSettings());
    // Android back closes the page; Escape does the same on desktop.
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && this.isOpen) this.app._closeLayoutGlobalSettings();
    });
    this._renderNav();
  }

  get isOpen() {
    return this.root && !this.root.classList.contains("hidden");
  }

  show() {
    if (!this.root) return;
    clearTimeout(this._closeTimer);
    this.root.classList.remove("hidden");
    this.root.style.transition = "none";
    this.root.style.opacity = "0";
    this._renderNav();
    this._selectTab(0);
    requestAnimationFrame(() => {
      this.root.style.transition = "opacity 200ms cubic-bezier(0.22, 1, 0.36, 1)";
      this.root.style.opacity = "1";
    });
  }

  hide() {
    if (!this.root) return;
    this.root.style.transition = "opacity 150ms cubic-bezier(0.22, 1, 0.36, 1)";
    this.root.style.opacity = "0";
    clearTimeout(this._closeTimer);
    this._closeTimer = setTimeout(() => {
      this.root.classList.add("hidden");
      this.root.style.opacity = "";
    }, 150);
  }

  /** Rebuilds every label after a language switch. */
  refreshLanguage() {
    this._renderNav();
    this._selectTab(this.currentTab);
  }

  /** Refreshes the visible category (e.g. after a gamepad is connected). */
  refreshCurrentTab() {
    if (!this.isOpen) return;
    const scroller = this.body.querySelector(".settingspage");
    const scrollTop = scroller ? scroller.scrollTop : 0;
    this._selectTab(this.currentTab);
    const next = this.body.querySelector(".settingspage");
    if (next) next.scrollTop = scrollTop;
  }

  // ── sidebar ──
  _renderNav() {
    if (!this.navList) return;
    this.navList.innerHTML = "";
    this.tabs.forEach((tab, index) => {
      const button = el("button", { class: "navitem" + (index === this.currentTab ? " active" : ""), text: t(tab.labelKey) });
      button.addEventListener("click", () => this._selectTab(index));
      this.navList.appendChild(button);
    });
  }

  _selectTab(index) {
    this.currentTab = index;
    this._renderNav();
    const scroller = this.body.querySelector(".settingspage");
    const scrollTop = scroller ? scroller.scrollTop : 0;
    this.body.innerHTML = "";
    const page = el("div", { class: "settingspage" });
    this.body.appendChild(page);
    if (this.tabs[index].key === "grid") this._buildGridPage(page);
    else this._buildPhysicalPage(page);
    page.scrollTop = scrollTop;
  }

  // ── widgets ──
  _section(text) {
    return el("div", { class: "gssectiontitle", text });
  }

  _check(label, checked, onChange) {
    const input = el("input", { type: "checkbox" });
    input.checked = !!checked;
    input.addEventListener("change", () => onChange(input.checked));
    return el("label", { class: "gscheck" }, [
      el("span", { class: "gschecklabel", text: label }),
      el("span", { class: "switch" }, [input, el("span", { class: "slider" })]),
    ]);
  }

  _select(options, value, onChange) {
    const select = el("select", { class: "gsselect" });
    for (const [val, text] of options) {
      const option = el("option", { value: val, text });
      if (val === value) option.selected = true;
      select.appendChild(option);
    }
    select.value = value;
    select.addEventListener("change", () => onChange(select.value));
    return select;
  }

  /**
   * Labelled slider with an editable value and −/+ steps, mirroring the
   * Android addSeekbar() widget. `onStart` / `onStop` drive the grid-density
   * rescale baseline.
   */
  _seek({ label, value, min, max, step = 1, onChange, onStart, onStop }) {
    const state = { value: Math.min(max, Math.max(min, Number(value) || 0)) };
    const wrap = el("div", { class: "gsseek" });
    const row = el("div", { class: "editseekrow" });
    const lab = el("span", { class: "editseeklabel", text: label });
    const input = el("input", { class: "editnum", type: "text", inputmode: "numeric" });
    const minus = el("button", { class: "editstep", type: "button", text: "-" });
    const plus = el("button", { class: "editstep", type: "button", text: "+" });
    const slider = el("input", {
      class: "editslider",
      type: "range",
      min: String(min),
      max: String(max),
      step: String(step),
    });

    const commit = (v, fire = true) => {
      const n = Math.max(min, Math.min(max, Math.round(Number(v) || 0)));
      state.value = n;
      input.value = String(n);
      slider.value = String(n);
      if (fire) onChange(n);
    };
    input.addEventListener("change", () => commit(input.value));
    input.addEventListener("blur", () => commit(input.value));
    minus.addEventListener("click", () => commit(state.value - step));
    plus.addEventListener("click", () => commit(state.value + step));
    slider.addEventListener("input", () => commit(slider.value));
    if (onStart) slider.addEventListener("pointerdown", () => onStart());
    if (onStop) {
      slider.addEventListener("pointerup", () => onStop());
      slider.addEventListener("pointercancel", () => onStop());
    }
    commit(value, false);

    row.append(lab, input, minus, plus);
    wrap.append(row, slider);
    return { el: wrap, set: (v) => commit(v, false) };
  }

  _chips(outputs, onRemove) {
    const box = el("div", { class: "gschips" });
    if (!outputs.length) {
      box.appendChild(el("span", { class: "gshint", text: t("global.unmapped") }));
      return box;
    }
    for (const bit of outputs) {
      const chip = el("span", { class: "chip", text: bitName(bit) });
      chip.addEventListener("click", (e) => {
        e.stopPropagation();
        onRemove(bit);
      });
      box.appendChild(chip);
    }
    return box;
  }

  _comboTiming(timing, interval, onChange) {
    const showSeek = timing === "CUSTOM_INTERVAL";
    const seek = this._seek({
      label: t("editor.comboInterval"),
      value: interval,
      min: 1,
      max: 100,
      onChange: (v) => onChange({ comboIntervalMs: v }),
    });
    seek.el.style.display = showSeek ? "" : "none";
    const select = this._select(
      [
        ["SIMULTANEOUS", t("editor.comboSimultaneous")],
        ["FRAME_BY_FRAME", t("editor.comboFramed")],
        ["CUSTOM_INTERVAL", t("editor.comboCustom")],
      ],
      timing,
      (value) => {
        seek.el.style.display = value === "CUSTOM_INTERVAL" ? "" : "none";
        onChange({ comboTiming: value });
      }
    );
    return el("div", { class: "gscombo" }, [select, seek.el]);
  }

  // ── 网格 page ──
  _buildGridPage(page) {
    const layout = this.app.layout;
    page.appendChild(this._section(t("global.grid")));
    page.appendChild(
      this._check(t("global.useGrid"), layout.useGrid, (checked) => {
        layout.setGridSettings(checked, layout.gridDensity);
        densityBox.style.display = checked ? "" : "none";
        drawGridPreview(preview, checked, layout.gridDensity);
      })
    );

    const preview = el("canvas", { class: "gridpreview" });
    page.appendChild(preview);

    const densityBox = el("div", {});
    densityBox.appendChild(
      this._seek({
        label: t("global.gridDensity"),
        value: layout.gridDensity,
        min: MIN_GRID_DENSITY,
        max: MAX_GRID_DENSITY,
        onStart: () => layout.beginGridSettingsDrag(),
        onStop: () => layout.endGridSettingsDrag(),
        onChange: (value) => {
          layout.setGridSettings(true, clampGridDensity(value));
          drawGridPreview(preview, layout.useGrid, layout.gridDensity);
        },
      }).el
    );
    densityBox.style.display = layout.useGrid ? "" : "none";
    page.appendChild(densityBox);
    requestAnimationFrame(() => drawGridPreview(preview, layout.useGrid, layout.gridDensity));
  }

  // ── 实体手柄 page ──
  _buildPhysicalPage(page) {
    const layout = this.app.layout;
    const scrollTop = page.scrollTop || 0;
    const rebuild = () => {
      const scroller = this.body.querySelector(".settingspage");
      const top = scroller ? scroller.scrollTop : scrollTop;
      this.body.innerHTML = "";
      const next = el("div", { class: "settingspage" });
      this.body.appendChild(next);
      this._buildPhysicalPage(next);
      next.scrollTop = top;
    };

    const mappings = layout.physicalInputMappings || {};
    page.appendChild(this._section(t("global.physical")));

    // Group the catalogue exactly like MainActivityPhysicalMapping does.
    const grouped = new Map(PHYSICAL_MAPPING_GROUPS.map((group) => [group.key, { group, inputs: [] }]));
    for (const input of PHYSICAL_INPUTS) {
      const owner = PHYSICAL_MAPPING_GROUPS.find((g) => g.inputKeys.includes(input.key));
      const entry = grouped.get(owner ? owner.key : "other");
      if (entry) entry.inputs.push(input);
    }

    for (const { group, inputs } of grouped.values()) {
      if (!inputs.length) continue;
      const expanded = this.expandedGroups.has(group.key);
      const header = el("div", { class: "gsgroupheader" }, [
        el("span", { class: "gschev", text: expanded ? CHEVRON_DOWN : CHEVRON_RIGHT }),
        el("span", { class: "gsgrouptitle", text: t(group.labelKey) }),
        el("span", { class: "gsgroupcount", text: String(inputs.length) }),
      ]);
      header.setAttribute("aria-label", t(expanded ? "global.collapse" : "global.expand"));
      const body = el("div", { class: "gsgroupbody" });
      body.style.display = expanded ? "" : "none";
      header.addEventListener("click", () => {
        if (this.expandedGroups.has(group.key)) this.expandedGroups.delete(group.key);
        else this.expandedGroups.add(group.key);
        rebuild();
      });
      page.appendChild(header);
      page.appendChild(body);
      for (const input of inputs) body.appendChild(this._buildMappingCard(input, mappings, rebuild));
    }
  }

  _buildMappingCard(input, mappings, rebuild) {
    const layout = this.app.layout;
    const isButton = input.kind === PHYSICAL_KIND.BUTTON;
    const outputs = mappingOutputs(mappings, input.key);
    const expanded = this.expandedInputs.has(input.key);
    const card = el("div", { class: "gscard" });
    const header = el("div", { class: "gsrow" });
    header.appendChild(el("span", { class: "gsrowlabel", text: physicalInputLabel(input) }));

    if (isButton) {
      header.appendChild(
        this._chips(outputs, (removed) => {
          this._writeMapping(
            input.key,
            outputs.filter((v) => v !== removed),
            mappings,
            mappingTiming(mappings, input.key).gyroActivate
          );
          rebuild();
        })
      );
      const add = el("button", { class: "chip gsaddchip", type: "button", text: "+" });
      add.setAttribute("aria-label", t("editor.addChip"));
      add.addEventListener("click", (e) => {
        e.stopPropagation();
        showOutputPicker(outputs, (picked) => {
          this._writeMapping(input.key, picked, mappings, mappingTiming(mappings, input.key).gyroActivate);
          rebuild();
        });
      });
      header.appendChild(add);
    } else {
      header.appendChild(el("span", { class: "gssummary", text: this._inputSummary(input) }));
    }

    const chevron = el("span", { class: "gschev gsrowchev", text: expanded ? CHEVRON_DOWN : CHEVRON_RIGHT });
    header.appendChild(chevron);
    card.appendChild(header);

    const details = el("div", { class: "gsdetails" });
    details.style.display = expanded ? "" : "none";
    let built = false;
    const buildDetails = () => {
      if (built) return;
      built = true;
      if (isButton) this._buildButtonDetails(details, input, mappings, rebuild);
      else if (input.kind === PHYSICAL_KIND.JOYSTICK) this._buildStickDetails(details, input, rebuild);
      else this._buildTouchpadDetails(details, rebuild);
    };
    card.appendChild(details);

    header.addEventListener("click", () => {
      const open = details.style.display === "none";
      if (open) {
        buildDetails();
        this.expandedInputs.add(input.key);
      } else {
        this.expandedInputs.delete(input.key);
      }
      details.style.display = open ? "" : "none";
      chevron.textContent = open ? CHEVRON_DOWN : CHEVRON_RIGHT;
    });
    if (expanded) buildDetails();
    return card;
  }

  _inputSummary(input) {
    if (input.key === "LEFT_JOYSTICK") return stickModeLabel(this.app.layout.leftStickSettings && this.app.layout.leftStickSettings.mode);
    if (input.key === "RIGHT_JOYSTICK") return stickModeLabel(this.app.layout.rightStickSettings && this.app.layout.rightStickSettings.mode);
    if (input.key === "TOUCHPAD") {
      const settings = normalizeTouchpadSettings(this.app.layout.touchpadSettings);
      return settings.mode === TOUCHPAD_MODE.MOUSE ? t("global.mouse") : t("global.nativeMapping");
    }
    return "";
  }

  _buildButtonDetails(container, input, mappings, rebuild) {
    const layout = this.app.layout;
    const current = mappingTiming(mappings, input.key);
    const defaultOutputs = input.defaultOutputs || [];

    const actions = el("div", { class: "gsactions" });
    const clear = el("button", { class: "btn", type: "button", text: t("editor.clearChip") });
    clear.addEventListener("click", () => {
      this._writeMapping(input.key, [], mappings);
      rebuild();
    });
    const reset = el("button", { class: "btn", type: "button", text: t("global.reset") });
    reset.addEventListener("click", () => {
      this._writeMapping(input.key, defaultOutputs, mappings, current.gyroActivate);
      rebuild();
    });
    actions.append(clear, reset);
    container.appendChild(actions);

    if (current.outputs.length) {
      container.appendChild(
        this._comboTiming(current.comboTiming, current.comboIntervalMs, (patch) => {
          const next = { ...current, ...patch };
          this._writeMapping(input.key, next.outputs, mappings, next.gyroActivate, next.comboTiming, next.comboIntervalMs);
        })
      );
    }
  }

  _buildStickDetails(container, input, rebuild) {
    const isLeft = input.key === "LEFT_JOYSTICK";
    const layout = this.app.layout;
    const read = () => normalizeStickSettings(isLeft ? layout.leftStickSettings : layout.rightStickSettings);
    const write = (next) => {
      const stripped = stripStickSettings(next);
      if (isLeft) layout.setPhysicalStickSettings(stripped, layout.rightStickSettings);
      else layout.setPhysicalStickSettings(layout.leftStickSettings, stripped);
    };
    const current = read();

    container.appendChild(this._section(t("global.stickMapping")));
    container.appendChild(
      this._select(
        [
          ["", t("global.nativeMapping")],
          ...STICK_MODES.map((mode) => [mode, stickModeLabel(mode)]),
        ],
        current.mode || "",
        (value) => {
          write({ ...read(), mode: value || null });
          rebuild();
        }
      )
    );

    const directions = directionsForMode(current.mode);
    for (const direction of directions) {
      const settings = directionAt(current, direction);
      const editor = el("div", { class: "gsdirection" });
      editor.appendChild(el("div", { class: "gsdirectionlabel", text: stickDirectionLabel(direction) }));
      const row = el("div", { class: "gsdirectionrow" });
      row.appendChild(
        this._chips(settings.outputs, (removed) => {
          write(withDirection(read(), direction, { ...directionAt(read(), direction), outputs: settings.outputs.filter((v) => v !== removed) }));
          rebuild();
        })
      );
      const add = el("button", { class: "chip gsaddchip", type: "button", text: t("editor.addChip") });
      add.addEventListener("click", () => {
        showOutputPicker(settings.outputs, (picked) => {
          write(withDirection(read(), direction, { ...directionAt(read(), direction), outputs: picked }));
          rebuild();
        });
      });
      const clear = el("button", { class: "chip", type: "button", text: t("editor.clearChip") });
      clear.addEventListener("click", () => {
        write(withDirection(read(), direction, { ...directionAt(read(), direction), outputs: [] }));
        rebuild();
      });
      row.append(add, clear);
      editor.appendChild(row);
      if (settings.outputs.length) {
        editor.appendChild(
          this._comboTiming(settings.comboTiming, settings.comboIntervalMs, (patch) => {
            const latest = directionAt(read(), direction);
            write(withDirection(read(), direction, { ...latest, ...patch }));
          })
        );
      }
      container.appendChild(editor);
    }

    if (current.mode === STICK_MODE.MOUSE) {
      container.appendChild(
        this._seek({
          label: t("editor.mouseSensitivity"),
          value: current.mouseSensitivity,
          min: MIN_MOUSE_SENSITIVITY,
          max: MAX_MOUSE_SENSITIVITY,
          onChange: (v) => write({ ...read(), mouseSensitivity: v }),
        }).el
      );
    }

    container.appendChild(
      this._seek({
        label: t("editor.deadZone"),
        value: current.deadZone,
        min: 0,
        max: 100,
        onChange: (v) => write({ ...read(), deadZone: v }),
      }).el
    );
    container.appendChild(
      this._seek({
        label: t("editor.antiDeadZone"),
        value: current.reverseDeadZone,
        min: 0,
        max: 100,
        onChange: (v) => write({ ...read(), reverseDeadZone: v }),
      }).el
    );
  }

  _buildTouchpadDetails(container, rebuild) {
    const layout = this.app.layout;
    const read = () => normalizeTouchpadSettings(layout.touchpadSettings);
    const write = (next) => layout.setPhysicalTouchpadSettings(stripTouchpadSettings(next));
    const current = read();

    container.appendChild(this._section(t("global.touchpadMapping")));
    container.appendChild(
      this._select(
        [
          [TOUCHPAD_MODE.NATIVE, t("global.nativeMapping")],
          [TOUCHPAD_MODE.MOUSE, t("global.mouse")],
        ],
        current.mode,
        (value) => {
          write({ ...read(), mode: value });
          rebuild();
        }
      )
    );

    if (current.mode !== TOUCHPAD_MODE.MOUSE) return;

    const gesture = (labelKey, actions, key) => {
      container.appendChild(
        el("div", { class: "gslabel", text: t(labelKey) })
      );
      container.appendChild(
        this._select(
          actions.map((action) => [action, mouseActionLabel(action)]),
          current[key],
          (value) => write({ ...read(), [key]: value })
        )
      );
    };
    gesture("editor.singleTap", MOUSE_TAP_ACTIONS, "singleTapAction");
    gesture("editor.twoFingerTap", MOUSE_TAP_ACTIONS, "twoFingerTapAction");
    gesture("editor.doubleTapDrag", MOUSE_SWIPE_ACTIONS, "doubleTapDragAction");
    gesture("editor.oneFingerSwipe", MOUSE_SWIPE_ACTIONS, "oneFingerSwipeAction");
    gesture("editor.twoFingerSwipe", MOUSE_SWIPE_ACTIONS, "twoFingerSwipeAction");

    container.appendChild(
      this._seek({
        label: t("editor.mouseSensitivity"),
        value: current.mouseSensitivity,
        min: MIN_MOUSE_SENSITIVITY,
        max: MAX_MOUSE_SENSITIVITY,
        onChange: (v) => write({ ...read(), mouseSensitivity: v }),
      }).el
    );
    container.appendChild(
      this._seek({
        label: t("editor.scrollSensitivity"),
        value: current.scrollSensitivity,
        min: MIN_MOUSE_SENSITIVITY,
        max: MAX_MOUSE_SENSITIVITY,
        onChange: (v) => write({ ...read(), scrollSensitivity: v }),
      }).el
    );
    container.appendChild(
      this._check(t("editor.invertScrollV"), current.invertScrollV, (v) => write({ ...read(), invertScrollV: v }))
    );
    container.appendChild(
      this._check(t("editor.invertScrollH"), current.invertScrollH, (v) => write({ ...read(), invertScrollH: v }))
    );
  }

  /** Writes one physical-input mapping, dropping it when it equals the default. */
  _writeMapping(key, outputs, mappings, gyroActivate = false, comboTiming, comboIntervalMs) {
    const layout = this.app.layout;
    const previous = mappingTiming(mappings, key);
    const next = {
      outputs: outputs || [],
      gyroActivate: !!gyroActivate,
      comboTiming: comboTiming || previous.comboTiming,
      comboIntervalMs: comboIntervalMs != null ? comboIntervalMs : previous.comboIntervalMs,
    };
    const updated = { ...(layout.physicalInputMappings || {}) };
    if (isDefaultMapping(key, next)) delete updated[key];
    else updated[key] = next;
    layout.setPhysicalInputMappings(updated);
  }
}

/** Grid density preview (mirrors GridDensityPreviewView): lines fade to the edges. */
function drawGridPreview(canvas, useGrid, density) {
  const cssW = Math.max(1, canvas.offsetWidth);
  const cssH = Math.max(1, canvas.offsetHeight);
  const dpr = Math.max(1, window.devicePixelRatio || 1);
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, cssW, cssH);
  if (!useGrid) return;

  const value = clampGridDensity(density);
  const cell = cssW / value;
  if (!(cell > 0)) return;
  ctx.strokeStyle = "rgba(204,204,204,0.85)";
  ctx.lineWidth = Math.max(0.25, Math.min(1, cell * 0.25));
  for (let x = 0; x <= cssW + 0.5; x += cell) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, cssH);
    ctx.stroke();
  }
  for (let y = 0; y <= cssH + 0.5; y += cell) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(cssW, y);
    ctx.stroke();
  }

  // Rectangular alpha mask: transparent at every edge, opaque in the middle.
  ctx.globalCompositeOperation = "destination-in";
  const horizontal = ctx.createLinearGradient(0, 0, cssW, 0);
  const vertical = ctx.createLinearGradient(0, 0, 0, cssH);
  for (const gradient of [horizontal, vertical]) {
    gradient.addColorStop(0, "rgba(255,255,255,0)");
    gradient.addColorStop(0.18, "rgba(255,255,255,1)");
    gradient.addColorStop(0.82, "rgba(255,255,255,1)");
    gradient.addColorStop(1, "rgba(255,255,255,0)");
  }
  ctx.fillStyle = horizontal;
  ctx.fillRect(0, 0, cssW, cssH);
  ctx.fillStyle = vertical;
  ctx.fillRect(0, 0, cssW, cssH);
  ctx.globalCompositeOperation = "source-over";
}
