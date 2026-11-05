import { copyButton, el } from "./dom.js";
import { IDENTIFIER_COLUMN, MAX_SHOWN_ROWS } from "./figures.js";

/*
 * Shows what an answer is based on: each query and its result table, exactly as returned.
 * While the agent works, steps appear as they finish, because rows from the database are real
 * even before the answer around them has been checked.
 */
export function renderEvidence(proof, selected, t) {
  proof.textContent = "";
  proof.append(el("h2", "", t.proof));
  if (!selected) {
    proof.append(el("p", "empty", t.proofEmpty));
    return;
  }
  const live = !selected.response;
  const evidence = live ? (selected.live || []) : (selected.response.evidence || []);
  evidence.forEach((step, s) => {
    const box = renderStep(step, s, t);
    if (live && s === evidence.length - 1) box.classList.add("is-new");
    proof.append(box);
  });
  if (live) proof.append(el("p", "empty is-working", t.proofWaiting));
  else if (!evidence.length) proof.append(el("p", "empty", t.noEvidence));
}

function renderStep(step, s, t) {
  const box = el("section", "step");
  const heading = el("h3");
  heading.append(el("span", "kind", `${t.kinds[step.kind] || step.kind}: `));
  heading.append(step.purpose || (step.kind === "sql" ? "" : step.input));
  box.append(heading);
  if (step.kind === "sql") {
    box.append(el("pre", "", step.input));
    box.append(copyButton(t.copySql, t.copied, () => step.input, "copy-sql"));
  }
  if (step.error) {
    box.append(el("p", "failed", `${t.rejected}: ${step.error}`));
    return box;
  }
  const result = step.result;
  if (!result || !result.rows.length) {
    box.append(el("p", "rows-note", t.noRows));
    return box;
  }
  const wrap = el("div", "table-wrap");
  wrap.append(renderTable(result, s, t));
  box.append(wrap);
  if (result.rows.length > MAX_SHOWN_ROWS) box.append(el("p", "rows-note", t.rows(MAX_SHOWN_ROWS, result.rows.length)));
  if (result.truncated) box.append(el("p", "rows-note", t.truncated));
  return box;
}

function renderTable(result, s, t) {
  const numeric = result.columns.map((name, c) => !IDENTIFIER_COLUMN.test(name)
    && result.rows.every(row => row[c] === null || /^-?\d+(\.\d+)?$/.test(row[c])));
  const rows = result.rows.slice(0, MAX_SHOWN_ROWS);
  const table = el("table");
  const tbody = el("tbody");
  rows.forEach((row, r) => {
    const tr = el("tr");
    tr.dataset.index = r;
    row.forEach((value, c) => {
      const td = el("td", numeric[c] ? "num" : "", value === null ? "—" : value);
      if (value === null) td.classList.add("is-null");
      td.dataset.cell = `${s}-${r}-${c}`;
      tr.append(td);
    });
    tbody.append(tr);
  });
  const head = el("tr");
  result.columns.forEach((name, c) => {
    const th = el("th", numeric[c] ? "num" : "");
    if (rows.length > 1) {
      const sort = el("button", "sort", name);
      sort.type = "button";
      sort.title = t.sort;
      sort.addEventListener("click", () => sortBy(table, tbody, rows, c, numeric[c], th));
      th.append(sort);
    } else {
      th.textContent = name;
    }
    head.append(th);
  });
  const thead = el("thead");
  thead.append(head);
  table.append(thead, tbody);
  return table;
}

/* Reorders the rows on screen only. Values stay as returned, and empty values always go last. */
function sortBy(table, tbody, rows, column, numeric, th) {
  const ascending = th.getAttribute("aria-sort") !== "ascending";
  table.querySelectorAll("th").forEach(other => other.removeAttribute("aria-sort"));
  th.setAttribute("aria-sort", ascending ? "ascending" : "descending");
  const direction = ascending ? 1 : -1;
  const order = rows.map((row, index) => ({ value: row[column], index })).sort((a, b) => {
    if (a.value === null) return 1;
    if (b.value === null) return -1;
    const compared = numeric ? parseFloat(a.value) - parseFloat(b.value) : a.value.localeCompare(b.value);
    return direction * compared || a.index - b.index;
  });
  const byIndex = new Map([...tbody.children].map(tr => [Number(tr.dataset.index), tr]));
  tbody.replaceChildren(...order.map(item => byIndex.get(item.index)));
}
