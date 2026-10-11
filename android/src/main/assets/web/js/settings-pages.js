// GKME-Web — settings page builders (mirrors the 9 Android settings pages).

import { el, row, section, toggle, slider, select, button, colorInput } from "./dom.js";
import { showConfirm, showSponsorDialog } from "./dialog.js";
import { DISPLAY_MODES } from "./controls.js";
import { DEFAULT_APPEARANCE, normalizeButton } from "./layout.js";
import { DEFAULT_SETTINGS, savePresets, APP_VERSION } from "./state.js";
import { connectedGamepads, deviceMotorCount, gamepadActuator } from "./haptics.js";

function ensureAppearance(app) {
  if (!app.settings.appearance) {
    app.settings.appearance = JSON.parse(JSON.stringify(DEFAULT_APPEARANCE));
  }
  return app.settings.appearance;
}

function applyAppearance(app) {
  app.layout.setAppearance(ensureAppearance(app));
}

const POLL_RATES = [30, 45, 60, 90, 100, 120, 200, 250, 300, 500, 750, 1000];

export const PAGES = {
  connection(app, root) {
    const hz = POLL_RATES.includes(app.settings.pollingHz) ? app.settings.pollingHz : 120;
    root.appendChild(
      section("连接", [
        row("状态", el("span", { text: app.conn.connected ? "已连接" : "未连接", class: "statusval" })),
        row(
          "轮询率",
          select(
            POLL_RATES.map((r) => [r, r + " Hz"]),
            String(hz),
            (v) => {
              app.settings.pollingHz = Number(v);
              app._restartSendLoop();
            }
          )
        ),
        el("div", { class: "btnrow" }, [
          button("进入全屏", () => app._enterFullscreen()),
        ]),
      ])
    );
  },

  presets(app, root) {
    const build = () => {
      root.innerHTML = "";
      root.appendChild(el("div", { class: "presettitle", text: "布局预设" }));

      const current = app.activePreset;
      const isBuiltIn = app.isBuiltInPreset(current);

      const chips = el("div", { class: "presetchips" });
      const chip = (label, fn) => {
        const b = button(label, fn);
        b.classList.add("presetchip");
        chips.appendChild(b);
        return b;
      };
      chip("新建", async () => { if (await app.newPreset()) build(); });
      chip("编辑", () => app.enterEditMode());
      if (isBuiltIn) chip("恢复默认", async () => { if (await app.restoreCurrentDefault()) build(); });
      chip("复制", async () => { if (await app.copyCurrentPreset()) build(); });
      chip("导出", () => app.exportCurrentPreset());
      chips.appendChild(
        el("label", { class: "filebtn presetchip" }, [
          "导入",
          el("input", {
            type: "file",
            accept: ".json,application/json",
            onchange: async (e) => {
              const f = e.target.files[0];
              if (!f) return;
              if (await app.importPresetFromFile(f)) build();
              e.target.value = "";
            },
          }),
        ])
      );
      if (!isBuiltIn) {
        chip("重命名", async () => { if (await app.renameCurrentPreset()) build(); });
        chip("删除", async () => { if (await app.deleteCurrentPreset()) build(); });
      }
      root.appendChild(chips);

      root.appendChild(el("div", { class: "presetcurrent", text: "当前预设: " + current }));

      const grid = el("div", { class: "presetgrid" });
      for (const name of app.presetNames()) {
        const active = name === app.activePreset;
        const canvas = el("canvas", { class: "presetpreview" });
        const card = el("div", { class: "presetcard" + (active ? " active" : "") }, [
          canvas,
          el("div", { class: "presetcardname", text: name }),
        ]);
        card.addEventListener("click", () => {
          if (name === app.activePreset) return;
          app._loadPreset(name);
          build();
        });
        grid.appendChild(card);
        requestAnimationFrame(() => drawPresetPreview(canvas, app.presets[name]));
      }
      root.appendChild(grid);
    };
    build();
  },

  physical(app, root) {
    const pads = [];
    if (navigator.getGamepads) {
      for (const p of navigator.getGamepads()) if (p && p.connected) pads.push(p);
    }
    root.appendChild(
      section("实体手柄（Gamepad API）", [
        row("转发实体手柄", toggle(app.settings.physical.forward, (v) => (app.settings.physical.forward = v))),
        row("已连接手柄", el("span", { class: "statusval", text: String(pads.length) })),
      ])
    );
    const listEl = el("div", { class: "padlist" });
    if (pads.length === 0) {
      listEl.appendChild(el("p", { class: "hint", text: "未检测到实体手柄。通过蓝牙/OTG 连接手柄后，在浏览器中按一次手柄按键即可被识别。" }));
    } else {
      for (const p of pads) {
        listEl.appendChild(el("div", { class: "padsrow", text: `${p.index}: ${p.id} (${p.mapping}) — ${p.buttons.length} 键 / ${p.axes.length} 轴` }));
      }
    }
    root.appendChild(section("设备列表", [listEl]));
    root.appendChild(
      el("p", { class: "hint", text: "浏览器只能读取标准映射手柄的按键/摇杆/扳机，无法透传自适应扳机、触摸板、HD 震动等厂商特性。" })
    );
  },

  vibration(app, root) {
    const build = () => {
      root.innerHTML = "";
      root.appendChild(section("按钮震动", [buttonVibrationControls(app, build)]));

      const hs = app.settings.haptics;
      const pads = connectedGamepads();
      if (!hs.device || typeof hs.device !== "object") hs.device = { type: "phone", controllerIndex: 0 };

      const options = [["phone", "手机马达"]];
      pads.forEach((p, i) => options.push(["controller:" + i, gamepadLabel(p, i)]));
      options.push(["none", "无"]);

      let value = hs.device.type === "controller" ? "controller:" + (hs.device.controllerIndex | 0) : hs.device.type;
      if (!options.some(([v]) => v === value)) value = "phone";
      const shown = parseVibrationDevice(value, pads);

      const rows = [
        row("接收主机震动", toggle(hs.gameVibrate, (v) => (hs.gameVibrate = v))),
        row(
          "震动设备",
          select(options, value, (v) => {
            hs.device = parseVibrationDevice(v, pads);
            build();
          })
        ),
      ];
      if (deviceMotorCount(shown, pads) >= 2) {
        const isController = shown.type === "controller";
        rows.push(
          row(
            "交换左右马达",
            toggle(isController ? hs.swapControllerMotors : hs.swapPhoneMotors, (v) => {
              if (isController) hs.swapControllerMotors = v;
              else hs.swapPhoneMotors = v;
            })
          )
        );
      }

      root.appendChild(section("游戏震动", rows));
      root.appendChild(el("p", { class: "hint", text: hapticsHint(app, shown, pads) }));
    };
    build();
  },

  misc(app, root) {
    root.appendChild(
      section("显示", [
        row(
          "按键样式",
          select(
            DISPLAY_MODES.map((m) => [m, m]),
            app.settings.displayMode,
            (v) => {
              app.settings.displayMode = v;
              app.layout.setDisplayMode(v);
            }
          )
        ),
        row("保持屏幕常亮", toggle(app.settings.screen.keepAwake, (v) => {
          app.settings.screen.keepAwake = v;
          if (v) app._acquireWakeLock();
          else app._releaseWakeLock();
        })),
      ])
    );
    root.appendChild(
      section("息屏模式（近似）", [
        button("进入息屏模式", () => {
          app.closeSettings();
          let overlay = document.getElementById("screenOff");
          if (!overlay) {
            overlay = el("div", { id: "screenOff", class: "screenoff" });
            overlay.addEventListener("click", () => overlay.remove());
            document.body.appendChild(overlay);
          }
          app._acquireWakeLock();
        }),
      ])
    );
    root.appendChild(
      section("重置", [
        el("div", { class: "btnrow" }, [
          button("重置全部设置", async () => {
            if (!(await showConfirm("重置全部设置", "确定恢复默认设置？", { positiveText: "重置" }))) return;
            app.settings = JSON.parse(JSON.stringify(DEFAULT_SETTINGS));
            applyAppearance(app);
            app.layout.setDisplayMode(app.settings.displayMode);
            app._restartSendLoop();
            app._selectCategory("misc");
          }, "danger"),
          button("重置当前预设", async () => {
            if (!(await showConfirm("重置当前预设", "确定恢复当前预设为默认？", { positiveText: "重置" }))) return;
            app.presets[app.activePreset] = { version: 1, buttons: [] };
            save(app);
            app.layout.setPreset(app.presets[app.activePreset]);
          }, "danger"),
        ]),
      ])
    );
  },

  about(app, root) {
    root.appendChild(
      el("div", { class: "aboutpage" }, [
        el("img", { class: "abouticon", src: "assets/icon.png", alt: "应用图标" }),
        el("div", { class: "aboutname", text: "GKME" }),
        el("div", { class: "aboutver", text: "版本 " + APP_VERSION }),
        el("div", {
          class: "aboutdesc",
          text:
            "作者：4zyz4  软件Q群：639317971\n\n" +
            "开源地址：https://github.com/4zyz4/gkme\n" +
            "注意：本软件不是Emotion，请进入Q群1045923515以下载正版Emotion",
        }),
        button("赞助", () => showSponsorDialog(), "aboutsponsor"),
      ])
    );
  },
};

function save(app) {
  savePresets(app.presets);
}

// ── Button vibration (按下 / 松开), mirrors the Android two-column layout ──

function buttonVibrationControls(app, rebuild) {
  const hs = app.settings.haptics;
  const column = (isPress, title) => {
    const typeKey = isPress ? "buttonPressType" : "buttonReleaseType";
    const durKey = isPress ? "buttonPressDuration" : "buttonReleaseDuration";
    const chips = el("div", { class: "vibchips" });
    for (const [val, text] of [["none", "无"], ["custom", "自定义"]]) {
      const chip = el("button", { class: "btn vibchip" + (hs[typeKey] === val ? " on" : ""), text });
      chip.addEventListener("click", () => {
        hs[typeKey] = val;
        rebuild();
      });
      chips.appendChild(chip);
    }
    const kids = [el("div", { class: "vibcoltitle", text: title }), chips];
    if (hs[typeKey] === "custom") {
      kids.push(
        row("时长", slider(5, 60, 1, hs[durKey], (v) => (hs[durKey] = v), (v) => v + " ms"))
      );
    }
    return el("div", { class: "vibcol" }, kids);
  };
  return el("div", { class: "vibcols" }, [column(true, "按下"), column(false, "松开")]);
}

// ── Vibration device helpers ──

function gamepadLabel(pad, index) {
  const id = (pad && pad.id ? pad.id : "").trim();
  if (!id) return "手柄" + (index + 1);
  return id.length > 30 ? id.slice(0, 30) + "…" : id;
}

/** Parse a select value ("phone" | "controller:<i>" | "none") into a device object. */
function parseVibrationDevice(value, pads) {
  if (typeof value === "string" && value.startsWith("controller:")) {
    const index = Number(value.slice("controller:".length));
    if (pads[index]) return { type: "controller", controllerIndex: index };
  } else if (value === "none") {
    return { type: "none", controllerIndex: 0 };
  }
  return { type: "phone", controllerIndex: 0 };
}

function hapticsHint(app, device, pads) {
  if (device.type === "none") return "已关闭游戏震动输出。";
  if (device.type === "controller") {
    const actuator = gamepadActuator(pads[device.controllerIndex | 0]);
    return actuator
      ? "游戏震动通过 Gamepad API 的 dual-rumble 发送到所选手柄（强/弱双马达）。"
      : "所选手柄不支持震动：浏览器未提供 vibrationActuator（部分手柄依赖厂商驱动）。";
  }
  return app.haptics.supported
    ? "手机马达使用浏览器 Vibration API（仅移动端浏览器支持）。"
    : "当前浏览器不支持 Vibration API。";
}

// Miniature layout preview for a preset card (mirrors PresetPreviewView).
function drawPresetPreview(canvas, preset) {
  const raw = (preset && preset.buttons) || [];
  const cssW = Math.max(1, canvas.offsetWidth);
  const cssH = Math.max(1, canvas.offsetHeight);
  const dpr = Math.max(1, window.devicePixelRatio || 1);
  canvas.width = Math.round(cssW * dpr);
  canvas.height = Math.round(cssH * dpr);
  const ctx = canvas.getContext("2d");
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, cssW, cssH);
  if (!raw.length) return;

  const gridW = 120;
  const gridH = 53;
  const scale = Math.min(cssW / gridW, cssH / gridH);
  const offX = (cssW - gridW * scale) / 2;
  const offY = (cssH - gridH * scale) / 2;

  for (const r of raw) {
    const b = normalizeButton(r);
    if (b.visible === false) continue;
    const base = b.id.split("_")[0];
    const bx = offX + b.x * scale;
    const by = offY + b.y * scale;
    const swapped = !b.lockAspect && (b.rotation === 90 || b.rotation === 270);
    const bw = (swapped ? b.h : b.w) * scale;
    const bh = (swapped ? b.w : b.h) * scale;
    if (base === "dpadPad" || base === "customKeypad") {
      const s = Math.min(bw, bh);
      ctx.beginPath();
      ctx.fillStyle = "#BBBBBB";
      ctx.arc(bx + bw / 2, by + bh / 2, s / 2, 0, Math.PI * 2);
      ctx.fill();
    } else if (b.lockAspect) {
      const r2 = Math.max(2, Math.min(b.w, b.h) * scale * 0.35);
      ctx.beginPath();
      ctx.fillStyle = "#AAAAAA";
      ctx.arc(bx + bw / 2, by + bh / 2, r2, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.fillStyle = "#BBBBBB";
      ctx.fillRect(bx, by, bw, bh);
    }
  }
}
