// GKME-Web — output value picker shared by the layout editor and the layout
// global settings page. Mirrors MainActivity.showOutputValuePicker: gamepad
// bits, mouse buttons and keyboard scan codes (stored as negative values).

import { el } from "./dom.js";
import { OUTPUT_PICKER_GROUPS, bitName, Kb } from "./controls.js";
import { t } from "./i18n.js";

/** HID scan codes offered by the keyboard group, in keyboard order. */
export const KEYBOARD_PICKER_BITS = Object.values(Kb).map((code) => -code);

/**
 * Shows the multi-select picker. `onResult` receives the selected value list
 * (gamepad/mouse bits and negative keyboard codes) in click order.
 */
export function showOutputPicker(currentBits, onResult) {
  const overlay = el("div", { class: "modal" });
  const box = el("div", { class: "modalbox" });
  box.append(el("div", { class: "modaltitle", text: t("editor.mappedKeys") }));
  const grid = el("div", { class: "pickergrid" });
  const sel = new Set(currentBits || []);

  const addGroup = (titleKey, values) => {
    grid.append(el("div", { class: "pickergrouptitle", text: t(titleKey) }));
    for (const bit of values) {
      const chip = el("span", { class: "pickerchip" + (sel.has(bit) ? " on" : ""), text: bitName(bit) });
      chip.addEventListener("click", () => {
        if (sel.has(bit)) {
          sel.delete(bit);
          chip.classList.remove("on");
        } else {
          sel.add(bit);
          chip.classList.add("on");
        }
      });
      grid.append(chip);
    }
  };

  for (const group of OUTPUT_PICKER_GROUPS) addGroup(group.titleKey, group.bits);
  addGroup("control.keyboard", KEYBOARD_PICKER_BITS);

  box.append(grid);
  const ok = el("button", { class: "modalclose", type: "button", text: t("common.ok") });
  ok.addEventListener("click", () => {
    onResult(Array.from(sel));
    overlay.remove();
  });
  box.append(ok);
  overlay.append(box);
  overlay.addEventListener("click", (e) => {
    if (e.target === overlay) overlay.remove();
  });
  document.body.appendChild(overlay);
}
