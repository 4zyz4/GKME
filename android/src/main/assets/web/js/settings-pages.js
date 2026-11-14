// GKME-Web — settings page builders (mirrors the 9 Android settings pages).

import { el, row, section, toggle, slider, select, button, colorInput } from "./dom.js";
import { showConfirm, showSponsorDialog, showLicenseDialog } from "./dialog.js";
import { DISPLAY_MODES } from "./controls.js";
import { DEFAULT_APPEARANCE, normalizeButton } from "./layout.js";
import { DEFAULT_SETTINGS, savePresets, APP_VERSION } from "./state.js";
import { connectedGamepads, deviceMotorCount, gamepadActuator } from "./haptics.js";
import { t, LANGUAGES, getLanguage, setLanguage } from "./i18n.js";

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
      section(t("connection.section"), [
        row(t("connection.status"), el("span", { text: app.conn.connected ? t("status.connected") : t("status.disconnected"), class: "statusval" })),
        row(
          t("connection.pollingRate"),
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
          button(t("connection.enterFullscreen"), () => app._enterFullscreen()),
        ]),
      ])
    );
  },

  presets(app, root) {
    const build = () => {
      root.innerHTML = "";
      root.appendChild(el("div", { class: "presettitle", text: t("presets.title") }));

      const current = app.activePreset;
      const isBuiltIn = app.isBuiltInPreset(current);

      const chips = el("div", { class: "presetchips" });
      const chip = (label, fn) => {
        const b = button(label, fn);
        b.classList.add("presetchip");
        chips.appendChild(b);
        return b;
      };
      chip(t("presets.new"), async () => { if (await app.newPreset()) build(); });
      chip(t("presets.edit"), () => app.enterEditMode());
      if (isBuiltIn) chip(t("presets.restoreDefaults"), async () => { if (await app.restoreCurrentDefault()) build(); });
      chip(t("presets.copy"), async () => { if (await app.copyCurrentPreset()) build(); });
      chip(t("presets.export"), () => app.exportCurrentPreset());
      chips.appendChild(
        el("label", { class: "filebtn presetchip" }, [
          t("presets.import"),
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
        chip(t("presets.rename"), async () => { if (await app.renameCurrentPreset()) build(); });
        chip(t("presets.delete"), async () => { if (await app.deleteCurrentPreset()) build(); });
      }
      root.appendChild(chips);

      root.appendChild(el("div", { class: "presetcurrent", text: t("presets.current", app.presetDisplayName(current)) }));

      const grid = el("div", { class: "presetgrid" });
      for (const name of app.presetNames()) {
        const active = name === app.activePreset;
        const canvas = el("canvas", { class: "presetpreview" });
        const card = el("div", { class: "presetcard" + (active ? " active" : "") }, [
          canvas,
          el("div", { class: "presetcardname", text: app.presetDisplayName(name) }),
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
      section(t("physical.section"), [
        row(t("physical.forward"), toggle(app.settings.physical.forward, (v) => (app.settings.physical.forward = v))),
        row(t("physical.connectedGamepads"), el("span", { class: "statusval", text: String(pads.length) })),
      ])
    );
    const listEl = el("div", { class: "padlist" });
    if (pads.length === 0) {
      listEl.appendChild(el("p", { class: "hint", text: t("physical.noneHint") }));
    } else {
      for (const p of pads) {
        listEl.appendChild(el("div", { class: "padsrow", text: t("physical.padRow", p.index, p.id, p.mapping, p.buttons.length, p.axes.length) }));
      }
    }
    root.appendChild(section(t("physical.deviceList"), [listEl]));
    root.appendChild(
      el("p", { class: "hint", text: t("physical.hint") })
    );
  },

  vibration(app, root) {
    const build = () => {
      root.innerHTML = "";
      root.appendChild(section(t("vibration.buttonSection"), [buttonVibrationControls(app, build)]));

      const hs = app.settings.haptics;
      const pads = connectedGamepads();
      if (!hs.device || typeof hs.device !== "object") hs.device = { type: "phone", controllerIndex: 0 };

      const options = [["phone", t("vibration.phoneMotor")]];
      pads.forEach((p, i) => options.push(["controller:" + i, gamepadLabel(p, i)]));
      options.push(["none", t("common.none")]);

      let value = hs.device.type === "controller" ? "controller:" + (hs.device.controllerIndex | 0) : hs.device.type;
      if (!options.some(([v]) => v === value)) value = "phone";
      const shown = parseVibrationDevice(value, pads);

      const rows = [
        row(t("vibration.receiveGame"), toggle(hs.gameVibrate, (v) => (hs.gameVibrate = v))),
        row(
          t("vibration.device"),
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
            t("vibration.swapMotors"),
            toggle(isController ? hs.swapControllerMotors : hs.swapPhoneMotors, (v) => {
              if (isController) hs.swapControllerMotors = v;
              else hs.swapPhoneMotors = v;
            })
          )
        );
      }

      root.appendChild(section(t("vibration.gameSection"), rows));
      root.appendChild(el("p", { class: "hint", text: hapticsHint(app, shown, pads) }));
    };
    build();
  },

  misc(app, root) {
    root.appendChild(
      section(t("misc.section"), [
        row(
          t("common.language"),
          select(
            LANGUAGES.map((l) => [l.code, l.labelKey ? t(l.labelKey) : l.label]),
            getLanguage(),
            (v) => {
              if (setLanguage(v)) app._onLanguageChanged();
            }
          )
        ),
      ])
    );
    root.appendChild(
      section(t("misc.display"), [
        row(
          t("misc.buttonStyle"),
          select(
            DISPLAY_MODES.map((m) => [m, m]),
            app.settings.displayMode,
            (v) => {
              app.settings.displayMode = v;
              app.layout.setDisplayMode(v);
            }
          )
        ),
        row(t("misc.keepAwake"), toggle(app.settings.screen.keepAwake, (v) => {
          app.settings.screen.keepAwake = v;
          if (v) app._acquireWakeLock();
          else app._releaseWakeLock();
        })),
      ])
    );
    root.appendChild(
      section(t("misc.screenOff"), [
        button(t("misc.enterScreenOff"), () => {
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
      section(t("misc.reset"), [
        el("div", { class: "btnrow" }, [
          button(t("misc.resetAll"), async () => {
            if (!(await showConfirm(t("misc.resetAll"), t("misc.resetAllConfirm"), { positiveText: t("misc.resetAction") }))) return;
            app.settings = JSON.parse(JSON.stringify(DEFAULT_SETTINGS));
            applyAppearance(app);
            app.layout.setDisplayMode(app.settings.displayMode);
            app._restartSendLoop();
            app._selectCategory("misc");
          }, "danger"),
          button(t("misc.resetCurrent"), async () => {
            if (!(await showConfirm(t("misc.resetCurrent"), t("misc.resetCurrentConfirm"), { positiveText: t("misc.resetAction") }))) return;
            app.presets[app.activePreset] = { version: 1, buttons: [] };
            save(app);
            app.layout.setPreset(app.presets[app.activePreset]);
          }, "danger"),
        ]),
      ])
    );
  },

  about(app, root) {
    // Mirrors android content_settings.xml pageAbout + MainActivityAbout.kt.
    const sectionHeader = (labelKey) =>
      el("div", { class: "aboutsectionheader" }, [
        el("div", { class: "aboutsectionbar" }),
        el("div", { class: "aboutsectiontitle", text: t(labelKey) }),
      ]);

    const listCard = (rows) => {
      const card = el("div", { class: "aboutcard" });
      rows.forEach((row, index) => {
        if (index > 0) card.appendChild(el("div", { class: "aboutdivider" }));
        card.appendChild(
          el("div", { class: "aboutrow" }, [
            el("div", { class: "aboutrowtitle", text: row.title }),
            el("div", { class: "aboutrowdesc", text: row.description }),
          ])
        );
      });
      return card;
    };

    const projectCard = (projects) => {
      const card = el("div", { class: "aboutcard aboutprojects" });
      projects.forEach((project, index) => {
        if (index > 0) card.appendChild(el("div", { class: "aboutdivider" }));
        const item = el("div", { class: "aboutproject" }, [
          el("div", { class: "aboutprojecttitle" }, [
            el("span", { class: "aboutprojectname", text: project.name }),
            el("span", { class: "aboutprojectarrow", text: "↗" }),
          ]),
          el("div", { class: "aboutprojectdesc", text: t(project.descriptionKey) }),
        ]);
        item.addEventListener("click", () => openExternal(app, project.url));
        card.appendChild(item);
      });
      return card;
    };

    const page = el("div", { class: "aboutpage" });
    page.appendChild(
      el("div", { class: "abouthero" }, [
        el("img", { class: "abouticon", src: "assets/icon.png", alt: t("common.appIcon") }),
        el("div", { class: "aboutname", text: "GKME" }),
        el("div", { class: "abouttagline1", text: "GAMEPAD KEYBOARD MOUSE EMULATOR" }),
        el("div", { class: "abouttagline2", text: "General Key Mapping Engine" }),
        el("div", { class: "aboutver", text: t("about.version", APP_VERSION) }),
        el("div", { class: "aboutbadges" }, [
          button("GitHub", () => openExternal(app, GKME_SOURCE_URL), "aboutbadge"),
          button("GPLv3", () => showLicenseDialog(t("about.licenseTitle")), "aboutbadge selected"),
        ]),
        el("div", { class: "aboutdesc", text: t("about.description") }),
      ])
    );

    page.appendChild(sectionHeader("about.sectionConnections"));
    page.appendChild(
      listCard([
        { title: t("about.connection.wifi"), description: t("about.connection.wifiDesc") },
        { title: t("about.connection.usb"), description: t("about.connection.usbDesc") },
        { title: t("about.connection.bt"), description: t("about.connection.btDesc") },
        { title: t("about.connection.dsu"), description: t("about.connection.dsuDesc") },
        { title: t("about.connection.local"), description: t("about.connection.localDesc") },
      ])
    );

    page.appendChild(sectionHeader("about.sectionOpenSource"));
    page.appendChild(el("div", { class: "aboutintro", text: t("about.openSourceIntro") }));
    page.appendChild(el("div", { class: "aboutsubtitle", text: t("about.referenced") }));
    page.appendChild(
      projectCard([
        { name: "HIDMaestro", descriptionKey: "about.project.hidmaestro", url: "https://github.com/hifihedgehog/HIDMaestro" },
        { name: "usbip-win2", descriptionKey: "about.project.usbip", url: "https://github.com/vadimgrn/usbip-win2" },
        { name: "Moonlight", descriptionKey: "about.project.moonlight", url: "https://github.com/moonlight-stream/moonlight-android" },
        { name: "Axixi2233/moonlight-android", descriptionKey: "about.project.axixi", url: "https://github.com/Axixi2233/moonlight-android" },
        { name: "qiin2333/moonlight-vplus", descriptionKey: "about.project.vplus", url: "https://github.com/qiin2333/moonlight-vplus" },
      ])
    );
    page.appendChild(el("div", { class: "aboutsubtitle", text: t("about.libraries"), style: { marginTop: "16px" } }));
    page.appendChild(
      projectCard([
        { name: "SDL3 / HIDAPI", descriptionKey: "about.project.sdl3", url: "https://libsdl.org/" },
        { name: "Dagger Hilt", descriptionKey: "about.project.hilt", url: "https://dagger.dev/hilt/" },
        { name: "Shizuku", descriptionKey: "about.project.shizuku", url: "https://shizuku.rikka.app/" },
      ])
    );

    page.appendChild(sectionHeader("about.sectionAuthor"));
    page.appendChild(
      el("div", { class: "aboutauthorcard" }, [
        el("div", { class: "aboutauthor", text: t("about.author").trim() }),
        el("div", { class: "aboutsmall", text: t("about.licenseNotice") }),
        el("div", { class: "aboutsmall", text: t("about.emotionNote") }),
      ])
    );

    page.appendChild(button(t("dialog.sponsorTitle"), () => showSponsorDialog(), "aboutsponsor"));
    root.appendChild(page);
  },
};

const GKME_SOURCE_URL = "https://github.com/4zyz4/gkme";

/** Opens an external link; a blocked popup is reported instead of failing silently. */
function openExternal(app, url) {
  let opened = null;
  try {
    opened = window.open(url, "_blank");
    if (opened) opened.opener = null;
  } catch {}
  if (!opened) app._toast(t("about.unableToOpenLink"));
}

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
    for (const [val, text] of [["none", t("common.none")], ["custom", t("common.custom")]]) {
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
        row(t("vibration.duration"), slider(5, 60, 1, hs[durKey], (v) => (hs[durKey] = v), (v) => v + " ms"))
      );
    }
    return el("div", { class: "vibcol" }, kids);
  };
  return el("div", { class: "vibcols" }, [column(true, t("vibration.press")), column(false, t("vibration.release"))]);
}

// ── Vibration device helpers ──

function gamepadLabel(pad, index) {
  const id = (pad && pad.id ? pad.id : "").trim();
  if (!id) return t("vibration.padName", index + 1);
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
  if (device.type === "none") return t("vibration.hintNone");
  if (device.type === "controller") {
    const actuator = gamepadActuator(pads[device.controllerIndex | 0]);
    return actuator
      ? t("vibration.hintController")
      : t("vibration.hintControllerUnsupported");
  }
  return app.haptics.supported
    ? t("vibration.hintPhone")
    : t("vibration.hintPhoneUnsupported");
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
