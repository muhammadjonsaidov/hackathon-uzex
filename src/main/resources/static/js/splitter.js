import { $ } from "./dom.js";

/* The evidence panel's width, kept as a share of the window so it survives a resize */
const MIN_PROOF_PX = 280;
const MIN_CHAT_PX = 360;

export function setProofWidth(px, remember) {
  const railIsColumn = document.body.classList.contains("rail-open") && !window.matchMedia("(max-width: 1099px)").matches;
  const rail = railIsColumn ? 250 : 0;
  const max = Math.max(MIN_PROOF_PX, window.innerWidth - rail - MIN_CHAT_PX);
  const width = Math.min(max, Math.max(MIN_PROOF_PX, px));
  const share = width / window.innerWidth * 100;
  document.documentElement.style.setProperty("--proof-width", `${share}vw`);
  $("splitter").setAttribute("aria-valuenow", String(Math.round(share)));
  if (remember) {
    try { localStorage.setItem("uzex-proof-width", String(share)); } catch (e) { /* storage may be blocked */ }
  }
}

export function initSplitter() {
  const splitter = $("splitter");
  try {
    const saved = parseFloat(localStorage.getItem("uzex-proof-width"));
    if (saved > 0) setProofWidth(saved / 100 * window.innerWidth, false);
  } catch (e) { /* storage may be blocked */ }
  if (!splitter.hasAttribute("aria-valuenow")) splitter.setAttribute("aria-valuenow", "46");

  splitter.addEventListener("pointerdown", e => {
    e.preventDefault();
    splitter.setPointerCapture(e.pointerId);
    document.body.classList.add("is-dragging");
  });
  splitter.addEventListener("pointermove", e => {
    if (document.body.classList.contains("is-dragging")) setProofWidth(window.innerWidth - e.clientX - 5, false);
  });
  const stop = e => {
    if (!document.body.classList.contains("is-dragging")) return;
    document.body.classList.remove("is-dragging");
    setProofWidth(window.innerWidth - e.clientX - 5, true);
  };
  splitter.addEventListener("pointerup", stop);
  splitter.addEventListener("pointercancel", stop);
  splitter.addEventListener("dblclick", () => setProofWidth(window.innerWidth * 0.46, true));
  splitter.addEventListener("keydown", e => {
    const step = e.shiftKey ? 96 : 24;
    const width = $("proof").offsetWidth;
    if (e.key === "ArrowLeft") setProofWidth(width + step, true);
    else if (e.key === "ArrowRight") setProofWidth(width - step, true);
    else return;
    e.preventDefault();
  });
}
