import { api, postJson, readEvents } from "./api.js";
import { createChatList } from "./chat-list.js";
import { $, copyButton, el } from "./dom.js";
import { renderEvidence } from "./evidence.js";
import { NUMBER, indexCells, matchCells, separateList } from "./figures.js";
import { savedLanguage, saveLanguage } from "./language.js";
import { initSplitter, setProofWidth } from "./splitter.js";
import { UI } from "./strings.js";

/*
 * One list of chats. Each chat has one language: opening it switches the page to that language, and
 * pressing the other language starts a new chat, so the open chat's context never changes under it.
 * Answers still follow the language of each question, as the rules ask.
 *
 * Signed-in users keep their chats. A guest's chats last until the page is reloaded.
 */
let chats = [];
const loadedChats = new Map();
let active = null;
let lang = savedLanguage();
let username = null;
let busyChat = null;
let busy = false;
let liveTimer = null;

const ACTIVE_CHAT_KEY = "uzex-active-chat";
const PENDING_QUESTION_KEY = "uzex-pending-question";

const turns = () => (active ? active.turns : []);

const chatList = createChatList({
  chats: () => chats,
  activeId: () => (active ? active.id : null),
  isBusy: id => Boolean(busyChat && busyChat.id === id),
  onOpen: id => openChat(id).catch(() => loadChats()),
  onRename: renameChat,
  onDelete: deleteChat
});

function rememberChat(id) {
  try {
    if (id) localStorage.setItem(ACTIVE_CHAT_KEY, id);
    else localStorage.removeItem(ACTIVE_CHAT_KEY);
  } catch (e) { /* storage may be blocked */ }
}

async function loadChats() {
  try {
    chats = await api("/api/chats");
  } catch (e) {
    chats = [];
  }
  renderHistory();
}

async function openChat(id) {
  let chat = loadedChats.get(id);
  if (!chat) {
    const data = await api(`/api/chats/${id}`);
    chat = {
      id, lang: data.chat.lang, title: data.chat.title,
      turns: data.turns.map(turn => ({ question: turn.question, response: turn.response })),
      selected: null
    };
    chat.turns.forEach(indexCells);
    chat.selected = chat.turns[chat.turns.length - 1] || null;
    loadedChats.set(id, chat);
  }
  active = chat;
  rememberChat(id);
  closeDrawer();
  setPane("answer");
  applyLanguage(chat.lang);
}

function startNewChat() {
  active = null;
  rememberChat(null);
  closeDrawer();
  setPane("answer");
  renderAll();
  focusComposer();
}

/* On touch screens focus opens the keyboard, which would cover the answer. */
function focusComposer() {
  if (!window.matchMedia("(pointer: coarse)").matches) $("input").focus();
}

/* The new name shows at once, and goes back if the server refuses it. */
async function renameChat(id, title) {
  const summary = chats.find(chat => chat.id === id);
  if (!summary) return;
  const previous = summary.title;
  setTitle(id, summary, title);
  try {
    await api(`/api/chats/${id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title })
    });
  } catch (e) {
    setTitle(id, summary, previous);
    showNotice(UI[lang].renameFailed);
  }
  renderHistory();
}

function setTitle(id, summary, title) {
  summary.title = title;
  const loaded = loadedChats.get(id);
  if (loaded) loaded.title = title;
}

async function deleteChat(id) {
  try {
    await api(`/api/chats/${id}`, { method: "DELETE" });
  } catch (e) {
    showNotice(UI[lang].deleteFailed);
    renderHistory();
    return;
  }
  chats = chats.filter(chat => chat.id !== id);
  loadedChats.delete(id);
  if (active && active.id === id) {
    active = null;
    rememberChat(null);
    renderAll();
  } else {
    renderHistory();
  }
}

function renderHistory() {
  if (chatList.isEditing()) return;
  const t = UI[lang];
  const box = $("history");
  box.textContent = "";
  box.append(el("h2", "", t.chats));
  const fresh = el("button", "new-chat", t.newChat);
  fresh.type = "button";
  fresh.addEventListener("click", startNewChat);
  box.append(fresh);
  if (chats.length) chatList.render(box, t);
  else box.append(el("p", "none", t.noChats));
  box.append(accountPanel(t));
}

/* Accounts are optional: the rules require this page to work without registration. */
function accountPanel(t) {
  const panel = el("div", "account");
  const actions = el("div", "account-actions");
  if (username) {
    panel.append(el("p", "account-name", username));
    const out = el("button", "", t.signOut);
    out.type = "button";
    out.addEventListener("click", signOut);
    actions.append(out);
    panel.append(actions);
    return panel;
  }
  for (const [mode, label] of [["login", t.signIn], ["register", t.register]]) {
    const link = el("a", "", label);
    link.href = `/account?mode=${mode}`;
    actions.append(link);
  }
  panel.append(actions, el("p", "account-hint", t.accountHint));
  return panel;
}

async function loadAccount() {
  try {
    username = (await api("/api/me")).username;
  } catch (e) {
    username = null;
  }
}

async function signOut() {
  await api("/api/auth/logout", { method: "POST" }).catch(() => null);
  username = null;
  chats = [];
  loadedChats.clear();
  active = null;
  rememberChat(null);
  renderAll();
}

function renderAll() {
  renderHistory();
  renderThread();
  renderProof();
  renderGuestNote();
}

function renderProof() {
  renderEvidence($("proof"), active ? active.selected : null, UI[lang]);
  renderPaneTabs();
}

/* Says a guest's chat will not last, while there is still time to do something about it. */
function renderGuestNote() {
  const t = UI[lang];
  const note = $("guest-note");
  const show = !username && turns().length > 0;
  note.hidden = !show;
  if (!show) return;
  const link = el("a", "", t.guestNoteAction);
  link.href = "/account?mode=register";
  note.replaceChildren(`${t.guestNote} `, link);
}

/* Chats panel: a column on large screens, a drawer over the page on smaller ones */
const drawerScreen = window.matchMedia("(max-width: 1099px)");
const phoneScreen = window.matchMedia("(max-width: 899px)");

function setRailOpen(open, remember) {
  document.body.classList.toggle("rail-open", open);
  $("rail-toggle").setAttribute("aria-expanded", String(open));
  if (remember && !drawerScreen.matches) {
    try { localStorage.setItem("uzex-rail", open ? "open" : "closed"); } catch (e) { /* storage may be blocked */ }
  }
}

function closeDrawer() {
  if (drawerScreen.matches) setRailOpen(false, false);
}

/* On a phone the answer and its evidence take turns on one screen. */
function setPane(pane) {
  document.body.dataset.pane = pane;
  document.querySelectorAll("#pane-tabs button").forEach(button => {
    button.setAttribute("aria-pressed", String(button.dataset.pane === pane));
  });
}

function renderPaneTabs() {
  const t = UI[lang];
  $("tab-answer").textContent = t.paneAnswer;
  const selected = active ? active.selected : null;
  const count = selected ? (selected.response ? selected.response.evidence || [] : selected.live || []).length : 0;
  $("tab-proof").textContent = count ? `${t.paneProof} (${count})` : t.paneProof;
}

function initialRailOpen() {
  if (drawerScreen.matches) return false;
  try {
    const saved = localStorage.getItem("uzex-rail");
    if (saved) return saved === "open";
  } catch (e) { /* storage may be blocked */ }
  return window.innerWidth >= 1100;
}

function setLang(next) {
  if (next === lang) return;
  if (active && active.lang !== next) {
    active = null;
    rememberChat(null);
  }
  applyLanguage(next);
}

function applyLanguage(next) {
  lang = next;
  saveLanguage(next);
  document.documentElement.lang = next;
  const t = UI[next];
  $("title").textContent = t.title;
  $("input").placeholder = t.placeholder;
  $("send").textContent = t.send;
  $("rail-toggle").textContent = t.chats;
  $("splitter").setAttribute("aria-label", t.resize);
  document.querySelectorAll(".langs button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.lang === next)));
  renderAll();
  $("proof").scrollTop = 0;
  scrollToEnd();
  loadStatus();
}

/*
 * Answers: a ranking becomes compact rows with the value on the right, and everything else stays
 * as text. This changes layout only, so what was checked is what is shown.
 */
function renderAnswer(container, turn, fresh) {
  container.textContent = "";
  const order = { next: 0, fresh };
  const lines = separateList(turn.response.answer).split("\n");
  const items = lines.map(line => line.match(/^(\d{1,2})[.)]\s+(.*)$/));
  if (items.filter(Boolean).length < 2) {
    appendTraced(container, lines.join("\n"), turn, order);
    return;
  }
  let list = null;
  lines.forEach((line, i) => {
    if (!items[i]) {
      const text = el("p", "answer-text");
      appendTraced(text, line, turn, order);
      container.append(text);
      list = null;
      return;
    }
    if (!list) {
      list = el("ol", "answer-list");
      container.append(list);
    }
    list.append(rankingRow(items[i][1], items[i][2], turn, order));
  });
}

function rankingRow(rank, text, turn, order) {
  const row = el("li");
  row.append(el("span", "answer-rank", rank));
  const { name, value } = splitRankingItem(text);
  const nameNode = el("span", "answer-name");
  appendTraced(nameNode, name, turn, order);
  row.append(nameNode);
  if (value) {
    const valueNode = el("span", "answer-value");
    appendTraced(valueNode, value, turn, order);
    row.append(valueNode);
  }
  return row;
}

/* "Name — 123 сум" or "Name: 123 сум". Anything else stays whole, to avoid splitting a name. */
function splitRankingItem(text) {
  for (const separator of [" — ", " – ", ": "]) {
    const at = text.lastIndexOf(separator);
    const value = at > 0 ? text.slice(at + separator.length) : "";
    if (/\d/.test(value) && value.length <= 48) return { name: text.slice(0, at), value };
  }
  return { name: text, value: "" };
}

/* Adds text to a node, making every number that traces to a result cell a live figure. */
function appendTraced(container, text, turn, order) {
  const cells = turn.cells || [];
  let last = 0;
  for (const m of text.matchAll(NUMBER)) {
    const before = text[m.index - 1] || "";
    if (/[\p{L}\-.\d]/u.test(before)) continue;
    const hits = matchCells(m[1], m[2], cells);
    if (!hits.length) continue;
    container.append(text.slice(last, m.index));
    const figure = el("button", "fig", m[0]);
    figure.type = "button";
    figure.dataset.cells = hits.map(c => c.id).join(" ");
    if (order.fresh) figure.style.setProperty("--i", order.next++);
    const on = () => { selectTurn(turn); light(hits, true); };
    const off = () => light(hits, false);
    figure.addEventListener("mouseenter", on);
    figure.addEventListener("focus", on);
    figure.addEventListener("mouseleave", off);
    figure.addEventListener("blur", off);
    figure.addEventListener("click", () => {
      on();
      setPane("proof");
      pin(hits);
    });
    container.append(figure);
    last = m.index + m[0].length;
  }
  container.append(text.slice(last));
}

function light(hits, on) {
  hits.forEach(c => {
    const cell = document.querySelector(`td[data-cell="${c.id}"]`);
    if (cell) cell.classList.toggle("is-source", on);
  });
}

/* On a phone there is no hover, so a tap keeps the cell lit and brings it into view. */
function pin(hits) {
  document.querySelectorAll("td.is-pinned").forEach(cell => cell.classList.remove("is-pinned"));
  hits.forEach(c => document.querySelector(`td[data-cell="${c.id}"]`)?.classList.add("is-pinned"));
  document.querySelector("td.is-pinned")?.scrollIntoView({ block: "center", behavior: "smooth" });
}

function lightFiguresFor(cell, on) {
  const node = active && active.selected && active.selected.node;
  if (!node) return;
  node.querySelectorAll(".fig").forEach(figure => {
    if (figure.dataset.cells.split(" ").includes(cell.dataset.cell)) figure.classList.toggle("is-on", on);
  });
}

function renderThread() {
  const thread = $("thread");
  thread.textContent = "";
  const t = UI[lang];
  if (!turns().length) {
    const intro = el("div", "intro");
    intro.append(el("p", "", t.intro));
    const list = el("ul", "examples");
    t.examples.forEach(q => {
      const li = el("li");
      const b = el("button", "", q);
      b.type = "button";
      b.addEventListener("click", () => ask(q));
      li.append(b);
      list.append(li);
    });
    intro.append(list);
    thread.append(intro);
    return;
  }
  turns().forEach(turn => thread.append(renderTurn(turn)));
}

function formatSeconds(ms) {
  return (ms / 1000).toFixed(1).replace(".", lang === "ru" ? "," : ".");
}

function renderWorking(node, turn) {
  const steps = el("ol", "steps");
  turn.steps.forEach((label, i) => {
    steps.append(el("li", i === turn.steps.length - 1 ? "is-current" : "is-done", label));
  });
  node.append(steps);
  const timer = el("p", "timer", formatSeconds(Date.now() - turn.startedAt));
  timer.id = "live-timer";
  node.append(timer);
}

function renderTurn(turn) {
  const t = UI[lang];
  const node = el("article", "turn");
  turn.node = node;
  node.append(el("p", "question", turn.question));
  if (!turn.response) {
    renderWorking(node, turn);
    return node;
  }
  const r = turn.response;
  if (r.status === "ERROR") node.classList.add("is-error");
  if (r.status === "NO_DATA") node.classList.add("is-nodata");
  if (r.status === "CLARIFY") node.classList.add("is-clarify");
  if (active && turn === active.selected) node.setAttribute("aria-current", "true");
  const fresh = turn.fresh;
  if (fresh) {
    node.classList.add("is-fresh");
    turn.fresh = false;
  }

  const answer = el("div", "answer");
  renderAnswer(answer, turn, fresh);
  node.append(answer);
  node.append(turnMeta(turn, t));
  return node;
}

/* Everything about the answer, in one quiet row: its check, its conditions and what to do with it. */
function turnMeta(turn, t) {
  const r = turn.response;
  const meta = el("div", "turn-meta");
  const seconds = t.secondsShort(formatSeconds(r.durationMs));
  if (r.status === "GLOSSARY") {
    meta.append(el("span", "check is-plain", t.glossary));
  } else if (r.source === "FALLBACK") {
    meta.append(el("span", "check is-plain", t.fallback));
  } else if (r.status === "ANSWER") {
    const check = el("span", "check", `✓ ${t.verifiedShort}, ${seconds}`);
    check.title = t.verified;
    meta.append(check);
  } else if (r.durationMs) {
    meta.append(el("span", "check is-plain", seconds));
  }
  meta.append(...turnActions(turn, t));
  if (r.assumptions && r.assumptions.length) {
    const details = el("details", "assumptions");
    details.append(el("summary", "", t.conditions(r.assumptions.length)));
    const list = el("ul");
    r.assumptions.forEach(a => list.append(el("li", "", a)));
    details.append(list);
    meta.append(details);
  }
  return meta;
}

function turnActions(turn, t) {
  if (turn.response.status === "ERROR") {
    const retry = el("button", "", t.retry);
    retry.type = "button";
    retry.addEventListener("click", () => {
      active.turns = active.turns.filter(x => x !== turn);
      ask(turn.question);
    });
    return [retry];
  }
  const show = el("button", "show-evidence", t.show);
  show.type = "button";
  show.addEventListener("click", () => {
    selectTurn(turn);
    setPane("proof");
  });
  return [show, copyButton(t.copyAnswer, t.copied, () => turn.response.answer)];
}

function selectTurn(turn) {
  if (!active || active.selected === turn) return;
  active.selected = turn;
  active.turns.forEach(x => {
    if (!x.node) return;
    if (x === turn) x.node.setAttribute("aria-current", "true");
    else x.node.removeAttribute("aria-current");
  });
  renderProof();
}

function startLiveTimer(turn) {
  stopLiveTimer();
  liveTimer = setInterval(() => {
    const timer = $("live-timer");
    if (timer) timer.textContent = formatSeconds(Date.now() - turn.startedAt);
  }, 100);
}

function stopLiveTimer() {
  clearInterval(liveTimer);
  liveTimer = null;
}

function sendQuestion(question, chat) {
  return fetch("/api/ask/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
    body: JSON.stringify({ question, chatId: chat.id, lang: chat.lang })
  });
}

/* Talking to the server */
async function ask(question) {
  if (busy) return;
  busy = true;
  $("send").disabled = true;
  $("input").value = "";
  resizeInput();
  /* A reply may arrive after the user switched chat, so it stays tied to this one. */
  if (!active) active = { id: null, lang, title: question, turns: [], selected: null };
  const chat = active;
  busyChat = chat;
  const turn = { question, steps: [], live: [], response: null, startedAt: Date.now() };
  chat.turns.push(turn);
  chat.selected = turn;
  setPane("answer");
  renderHistory();
  renderThread();
  renderProof();
  renderGuestNote();
  scrollToEnd();
  startLiveTimer(turn);
  try {
    let res = await sendQuestion(question, chat);
    if (res.status === 404 && chat.id) {
      /* This chat is gone, for example a guest reloaded in another tab. Carry on in a new one. */
      loadedChats.delete(chat.id);
      chats = chats.filter(x => x.id !== chat.id);
      chat.id = null;
      res = await sendQuestion(question, chat);
    }
    if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
    await readEvents(res.body, (event, data) => handleEvent(event, data, chat, turn));
    if (!turn.response) throw new Error("Stream ended without an answer");
  } catch (e) {
    finish(chat, turn, { status: "ERROR", answer: UI[chat.lang].failed, assumptions: [], evidence: [], durationMs: 0 });
  } finally {
    busy = false;
    busyChat = null;
    stopLiveTimer();
    $("send").disabled = false;
    renderHistory();
    if (document.activeElement === document.body) focusComposer();
  }
}

function handleEvent(event, data, chat, turn) {
  if (event === "chat") {
    chatSaved(chat, data);
  } else if (event === "step") {
    turn.steps.push(data.label);
    if (chat === active) {
      renderThread();
      scrollToEnd();
    }
  } else if (event === "evidence") {
    turn.live.push(data);
    if (chat === active && chat.selected === turn) {
      renderProof();
      $("proof").scrollTop = $("proof").scrollHeight;
    }
  } else if (event === "final") {
    finish(chat, turn, data);
  }
}

function chatSaved(chat, summary) {
  chat.id = summary.id;
  chat.title = summary.title;
  chat.lang = summary.lang;
  loadedChats.set(chat.id, chat);
  chats = [summary, ...chats.filter(x => x.id !== summary.id)];
  if (chat === active) rememberChat(chat.id);
  renderHistory();
}

function finish(chat, turn, response) {
  turn.response = response;
  turn.fresh = true;
  indexCells(turn);
  chat.selected = turn;
  const summary = chats.find(x => x.id === chat.id);
  if (summary) summary.updatedAt = Date.now();
  renderHistory();
  if (chat !== active) return;
  renderThread();
  renderProof();
  $("proof").scrollTop = 0;
  showStartOf(turn);
}

/* A long answer should open at its first line, with the question above it. */
function showStartOf(turn) {
  if (turn.node) turn.node.scrollIntoView({ block: "start" });
}

function showNotice(message) {
  const notice = $("notice");
  notice.textContent = message;
  notice.hidden = false;
  setTimeout(() => loadStatus(), 4000);
}

async function loadStatus() {
  const notice = $("notice");
  try {
    const status = await api("/api/status");
    const t = UI[lang];
    const message = !status.database ? t.noDb : (!status.llmConfigured ? t.noKey : "");
    notice.textContent = message;
    notice.hidden = !message;
  } catch (e) {
    notice.hidden = true;
  }
}

function scrollToEnd() {
  const thread = $("thread");
  thread.scrollTop = thread.scrollHeight;
}

function resizeInput() {
  const input = $("input");
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight + 4, 160)}px`;
  input.style.overflowY = input.scrollHeight + 4 > 160 ? "auto" : "hidden";
}

/* A guest's chats end with the page, unless they register or sign in first. */
async function startSession() {
  await loadAccount();
  if (username) {
    await loadChats();
    let saved = null;
    try { saved = localStorage.getItem(ACTIVE_CHAT_KEY); } catch (e) { /* storage may be blocked */ }
    if (saved && chats.some(chat => chat.id === saved)) {
      await openChat(saved).catch(() => rememberChat(null));
    }
  } else {
    rememberChat(null);
    await postJson("/api/chats/clear-guest", {}).catch(() => null);
    renderHistory();
  }
  renderGuestNote();
  askPendingQuestion();
}

/* A question typed on the landing page. */
function askPendingQuestion() {
  let question = null;
  try {
    question = sessionStorage.getItem(PENDING_QUESTION_KEY);
    sessionStorage.removeItem(PENDING_QUESTION_KEY);
  } catch (e) { /* storage may be blocked */ }
  if (question) ask(question);
}

$("form").addEventListener("submit", e => {
  e.preventDefault();
  const q = $("input").value.trim();
  if (q) ask(q);
});
$("input").addEventListener("input", resizeInput);
$("input").addEventListener("keydown", e => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    $("form").requestSubmit();
  }
});
$("proof").addEventListener("mouseover", e => {
  const cell = e.target.closest("td[data-cell]");
  if (cell) lightFiguresFor(cell, true);
});
$("proof").addEventListener("mouseout", e => {
  const cell = e.target.closest("td[data-cell]");
  if (cell) lightFiguresFor(cell, false);
});
document.addEventListener("keydown", e => {
  const typing = e.target.closest("input, textarea");
  if (e.key === "/" && !typing && !e.ctrlKey && !e.metaKey) {
    e.preventDefault();
    $("input").focus();
  }
});
document.querySelectorAll(".langs button").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang)));
$("rail-toggle").addEventListener("click", () => {
  setRailOpen(!document.body.classList.contains("rail-open"), true);
  if (!drawerScreen.matches) setProofWidth($("proof").offsetWidth, false);
});
$("rail-backdrop").addEventListener("click", closeDrawer);
document.addEventListener("keydown", e => {
  if (e.key === "Escape" && !e.target.closest(".chat-row")) closeDrawer();
});
document.querySelectorAll("#pane-tabs button").forEach(button => {
  button.addEventListener("click", () => setPane(button.dataset.pane));
});
drawerScreen.addEventListener("change", () => setRailOpen(initialRailOpen(), false));
phoneScreen.addEventListener("change", () => setPane("answer"));
setPane("answer");
setRailOpen(initialRailOpen(), false);
initSplitter();
applyLanguage(lang);
startSession();
