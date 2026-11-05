import { api, readEvents } from "./api.js";
import { $, el } from "./dom.js";
import { renderEvidence } from "./evidence.js";
import { NUMBER, indexCells, matchCells, separateList } from "./figures.js";
import { savedLanguage, saveLanguage } from "./language.js";
import { initSplitter, setProofWidth } from "./splitter.js";
import { UI } from "./strings.js";

/*
 * One list of chats, stored on the server. Each chat has one language: opening it switches the page
 * to that language, and pressing the other language starts a new chat, so the open chat's context
 * never changes under it. Answers still follow the language of each question, as the rules ask.
 */
let chats = [];
const loadedChats = new Map();
let active = null;
let lang = savedLanguage();
let username = null;
let busy = false;

const currentChat = () => active;
const turns = () => (active ? active.turns : []);

function rememberChat(id) {
  try {
    if (id) localStorage.setItem("uzex-active-chat", id);
    else localStorage.removeItem("uzex-active-chat");
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
  applyLanguage(chat.lang);
}

function startNewChat() {
  active = null;
  rememberChat(null);
  renderAll();
  $("input").focus();
}

function chatListItem(chat) {
  const li = el("li");
  const b = el("button");
  b.type = "button";
  b.title = chat.title;
  b.append(el("span", "chat-lang", chat.lang.toUpperCase()), el("span", "chat-title", chat.title));
  if (active && chat.id === active.id) b.setAttribute("aria-current", "true");
  b.addEventListener("click", () => openChat(chat.id).catch(() => loadChats()));
  li.append(b);
  return li;
}

function renderHistory() {
  const t = UI[lang];
  const box = $("history");
  box.textContent = "";
  box.append(el("h2", "", t.chats));
  const fresh = el("button", "new-chat", t.newChat);
  fresh.type = "button";
  fresh.addEventListener("click", startNewChat);
  box.append(fresh);
  if (chats.length) {
    const list = el("ul", "chat-list");
    [...chats].sort((a, b) => b.updatedAt - a.updatedAt).forEach(chat => list.append(chatListItem(chat)));
    box.append(list);
  } else {
    box.append(el("p", "none", t.noChats));
  }
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
}

/* Chats panel: open by default where there is room for it */
function setRailOpen(open, remember) {
  document.body.classList.toggle("rail-open", open);
  $("rail-toggle").setAttribute("aria-expanded", String(open));
  if (remember) {
    try { localStorage.setItem("uzex-rail", open ? "open" : "closed"); } catch (e) { /* storage may be blocked */ }
  }
}

function initialRailOpen() {
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

function renderAnswer(container, turn) {
  container.textContent = "";
  const text = separateList(turn.response.answer);
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
    const on = () => { selectTurn(turn); light(hits, true); };
    const off = () => light(hits, false);
    figure.addEventListener("mouseenter", on);
    figure.addEventListener("focus", on);
    figure.addEventListener("mouseleave", off);
    figure.addEventListener("blur", off);
    figure.addEventListener("click", () => {
      on();
      const first = document.querySelector("td.is-source");
      if (first) first.scrollIntoView({ block: "center", behavior: "smooth" });
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

/* Asking */
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

function renderTurn(turn) {
  const t = UI[lang];
  const node = el("article", "turn");
  turn.node = node;
  node.append(el("p", "question", turn.question));
  if (!turn.response) {
    const steps = el("ul", "steps");
    turn.steps.forEach(label => steps.append(el("li", "", label)));
    node.append(steps);
    return node;
  }
  const r = turn.response;
  if (r.status === "ERROR") node.classList.add("is-error");
  if (r.status === "NO_DATA") node.classList.add("is-nodata");
  if (r.status === "CLARIFY") node.classList.add("is-clarify");
  if (currentChat() && turn === currentChat().selected) node.setAttribute("aria-current", "true");

  const answer = el("p", "answer");
  renderAnswer(answer, turn);
  node.append(answer);

  if (r.assumptions && r.assumptions.length) {
    const list = el("ul", "assumptions");
    r.assumptions.forEach(a => list.append(el("li", "", a)));
    node.append(list);
  }
  const seconds = (r.durationMs / 1000).toFixed(1).replace(".", lang === "ru" ? "," : ".");
  if (r.status === "GLOSSARY") {
    node.append(el("p", "check is-plain", `${t.glossary}. ${t.seconds(seconds)}`));
  } else if (r.source === "FALLBACK") {
    node.append(el("p", "check is-plain", `${t.fallback}. ${t.seconds(seconds)}`));
  } else if (r.status === "ANSWER") {
    node.append(el("p", "check", `✓ ${t.verified}. ${t.seconds(seconds)}`));
  } else if (r.durationMs) {
    node.append(el("p", "check is-plain", t.seconds(seconds)));
  }
  const actions = el("div", "turn-actions");
  const show = el("button", "", t.show);
  show.type = "button";
  show.addEventListener("click", () => selectTurn(turn));
  actions.append(show);
  node.append(actions);
  return node;
}

function selectTurn(turn) {
  const chat = currentChat();
  if (!chat || chat.selected === turn) return;
  chat.selected = turn;
  chat.turns.forEach(x => {
    if (!x.node) return;
    if (x === turn) x.node.setAttribute("aria-current", "true");
    else x.node.removeAttribute("aria-current");
  });
  renderProof();
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
  const turn = { question, steps: [], response: null };
  chat.turns.push(turn);
  renderThread();
  scrollToEnd();
  try {
    const res = await fetch("/api/ask/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
      body: JSON.stringify({ question, chatId: chat.id, lang: chat.lang })
    });
    if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`);
    await readEvents(res.body, (event, data) => {
      if (event === "chat") {
        chatSaved(chat, data);
      } else if (event === "step") {
        turn.steps.push(data.label);
        if (chat === currentChat()) {
          renderThread();
          scrollToEnd();
        }
      } else if (event === "final") {
        finish(chat, turn, data);
      }
    });
    if (!turn.response) throw new Error("Stream ended without an answer");
  } catch (e) {
    finish(chat, turn, { status: "ERROR", answer: UI[chat.lang].failed, assumptions: [], evidence: [], durationMs: 0 });
  } finally {
    busy = false;
    $("send").disabled = false;
    $("input").focus();
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
  indexCells(turn);
  chat.selected = turn;
  const summary = chats.find(x => x.id === chat.id);
  if (summary) summary.updatedAt = Date.now();
  renderHistory();
  if (chat !== currentChat()) return;
  renderThread();
  renderProof();
  $("proof").scrollTop = 0;
  scrollToEnd();
}

async function loadStatus() {
  const notice = $("notice");
  try {
    const res = await fetch("/api/status");
    const status = await res.json();
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
document.querySelectorAll(".langs button").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang)));
$("rail-toggle").addEventListener("click", () => {
  setRailOpen(!document.body.classList.contains("rail-open"), true);
  setProofWidth($("proof").offsetWidth, false);
});
setRailOpen(initialRailOpen(), false);
initSplitter();
applyLanguage(lang);

(async () => {
  await loadAccount();
  await loadChats();
  let saved = null;
  try { saved = localStorage.getItem("uzex-active-chat"); } catch (e) { /* storage may be blocked */ }
  if (saved && chats.some(chat => chat.id === saved)) {
    await openChat(saved).catch(() => rememberChat(null));
  }
})();

function renderProof() {
  const selected = currentChat() ? currentChat().selected : null;
  renderEvidence($("proof"), selected, UI[lang]);
}
