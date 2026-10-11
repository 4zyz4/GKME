// GKME-Web — tiny DOM helpers.

export function el(tag, props, children) {
  const e = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === undefined || v === null) continue;
      if (k === "class") e.className = v;
      else if (k === "text") e.textContent = v;
      else if (k === "html") e.innerHTML = v;
      else if (k.startsWith("on") && typeof v === "function") e.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === "style" && typeof v === "object") Object.assign(e.style, v);
      else e.setAttribute(k, v);
    }
  }
  for (const c of children || []) {
    if (c == null) continue;
    e.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return e;
}

/** A labelled row: label on the left, control on the right. */
export function row(label, control) {
  return el("div", { class: "settingrow" }, [
    el("label", { class: "settinglabel", text: label }),
    el("div", { class: "settingcontrol" }, [control]),
  ]);
}

export function section(title, children) {
  return el("div", { class: "settingsection" }, [
    el("div", { class: "sectiontitle", text: title }),
    ...children,
  ]);
}

export function toggle(checked, onChange) {
  const input = el("input", { type: "checkbox" });
  input.checked = !!checked;
  input.addEventListener("change", () => onChange(input.checked));
  return el("label", { class: "switch" }, [input, el("span", { class: "slider" })]);
}

export function slider(min, max, step, value, onChange, format) {
  const input = el("input", { type: "range", min, max, step, value });
  const out = el("span", { class: "sliderval", text: format ? format(value) : String(value) });
  input.addEventListener("input", () => {
    const v = Number(input.value);
    out.textContent = format ? format(v) : String(v);
    onChange(v);
  });
  return el("div", { class: "sliderwrap" }, [input, out]);
}

export function select(options, value, onChange) {
  const s = el("select", {});
  for (const [val, label] of options) {
    const o = el("option", { value: val, text: label });
    if (val === value) o.selected = true;
    s.appendChild(o);
  }
  s.addEventListener("change", () => onChange(s.value));
  s.value = value;
  return s;
}

export function button(label, onClick, cls = "") {
  return el("button", { class: "btn " + cls, text: label, onclick: onClick });
}

export function colorInput(value, onChange) {
  const input = el("input", { type: "text", value: value || "", class: "colorhex" });
  const swatch = el("input", { type: "color", value: rgbaToHex(value), class: "colorswatch" });
  input.addEventListener("change", () => onChange(input.value));
  swatch.addEventListener("input", () => { input.value = swatch.value; onChange(swatch.value); });
  return el("div", { class: "colorpick" }, [swatch, input]);
}

export function rgbaToHex(v) {
  if (!v) return "#ffffff";
  if (v.startsWith("#")) return v.slice(0, 7);
  const m = v.match(/rgba?\(([^)]+)\)/);
  if (!m) return "#ffffff";
  const parts = m[1].split(",").map((s) => parseFloat(s));
  const [r, g, b] = parts;
  const to = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, "0");
  return "#" + to(r) + to(g) + to(b);
}
