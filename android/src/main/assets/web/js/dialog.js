// GKME-Web — custom dialogs mirroring android CustomDialog.kt.
//
// Replaces the browser-native confirm()/prompt() so dialogs match the Android
// look: #1E1E1E surface, centered bold title, dim message, right-aligned
// cancel/confirm buttons (#333 / #666). Web APIs are promise-based instead of
// blocking, so callers use `await`.

import { el } from "./dom.js";
import { t } from "./i18n.js";

function openDialog() {
  const overlay = el("div", { class: "dlgov" });
  const box = el("div", { class: "dlgbox" });
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  return { overlay, box, close: () => overlay.remove() };
}

function btnRow(negativeText, positiveText, onNegative, onPositive) {
  const row = el("div", { class: "dlgbtnrow" });
  if (negativeText) {
    const neg = el("button", { type: "button", class: "dlgbtn", text: negativeText });
    neg.addEventListener("click", onNegative);
    row.appendChild(neg);
  }
  if (positiveText) {
    const pos = el("button", { type: "button", class: "dlgbtn primary", text: positiveText });
    pos.addEventListener("click", onPositive);
    row.appendChild(pos);
  }
  return row;
}

/** Android CustomDialog.showConfirm — resolves true on confirm, false otherwise. */
export function showConfirm(title, message, opts = {}) {
  const { positiveText = t("common.ok"), negativeText = t("common.cancel") } = opts;
  return new Promise((resolve) => {
    const { overlay, box, close } = openDialog();
    box.appendChild(el("div", { class: "dlgtitle", text: title }));
    if (message) box.appendChild(el("div", { class: "dlgmsg", text: message }));
    box.appendChild(btnRow(
      negativeText, positiveText,
      () => { close(); resolve(false); },
      () => { close(); resolve(true); },
    ));
    overlay.addEventListener("click", (e) => { if (e.target === overlay) { close(); resolve(false); } });
  });
}

/** Android CustomDialog.showSponsorDialog — sponsor dialog with the reward QR image. */
export function showSponsorDialog() {
  const { overlay, box, close } = openDialog();
  box.appendChild(el("div", { class: "dlgtitle", text: t("dialog.sponsorTitle") }));
  box.appendChild(el("div", { class: "dlgmsg", text: t("dialog.sponsorThanks") }));
  box.appendChild(el("img", { class: "sponsorimg", src: "assets/reward.png", alt: t("dialog.sponsorQrAlt") }));
  box.appendChild(btnRow(t("common.close"), null, () => close(), null));
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
}

/** iOS Safari has no Fullscreen API for the gamepad, so guide the user to
 *  install the PWA to the home screen for a true fullscreen experience. */
export function showIosFullscreenHint() {
  const { overlay, box, close } = openDialog();
  box.classList.add("ioshintbox");
  box.appendChild(el("div", { class: "dlgtitle", text: t("dialog.iosTitle") }));
  const msg = el("div", { class: "dlgmsg ioshintmsg" }, [
    el("div", { text: t("dialog.iosLine1") }),
    el("div", {}, [
      t("dialog.iosLine2Prefix"),
      shareIcon(),
      el("span", { class: "hl", text: t("dialog.iosShare") }),
      t("dialog.iosLine2Suffix"),
    ]),
    el("div", {}, [
      el("span", { class: "hl", text: t("dialog.iosAddHome") }),
      t("dialog.iosLine3Suffix"),
    ]),
  ]);
  box.appendChild(msg);
  box.appendChild(btnRow(null, t("dialog.gotIt"), null, () => close()));
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });
}

/** iOS share glyph (a box with an upward arrow) shown inline in the hint text. */
function shareIcon() {
  return el("span", {
    class: "ios-share-icon",
    html:
      '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true">' +
      '<path d="M11 4.83 8.41 7.41 7 6l5-5 5 5-1.41 1.41L13 4.83V13h-2zM5 5h3v2H5v12h14V7h-3V5h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"/>' +
      "</svg>",
  });
}

/** Android CustomDialog.showInput — resolves the trimmed string, or null on cancel. */
export function showInput(title, opts = {}) {
  const { hint = "", prefill = "", positiveText = t("common.ok"), negativeText = t("common.cancel") } = opts;
  return new Promise((resolve) => {
    const { overlay, box, close } = openDialog();
    box.appendChild(el("div", { class: "dlgtitle", text: title }));
    const input = el("input", { class: "dlginput", type: "text", placeholder: hint });
    input.value = prefill;
    box.appendChild(input);
    const cancel = () => { close(); resolve(null); };
    const submit = () => { close(); resolve(input.value.trim() || null); };
    box.appendChild(btnRow(negativeText, positiveText, cancel, submit));
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") { e.preventDefault(); submit(); }
      else if (e.key === "Escape") { e.preventDefault(); cancel(); }
    });
    overlay.addEventListener("click", (e) => { if (e.target === overlay) cancel(); });
    setTimeout(() => { input.focus(); input.select(); }, 0);
  });
}
