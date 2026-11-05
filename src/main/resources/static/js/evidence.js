import { el } from "./dom.js";
import { IDENTIFIER_COLUMN, MAX_SHOWN_ROWS } from "./figures.js";

/* Shows what an answer is based on: each query and its result table, exactly as returned. */
export function renderEvidence(proof, selected, t) {
  proof.textContent = "";
  proof.append(el("h2", "", t.proof));
  if (!selected || !selected.response) {
    proof.append(el("p", "empty", t.proofEmpty));
    return;
  }
  const evidence = selected.response.evidence || [];
  if (!evidence.length) {
    proof.append(el("p", "empty", t.noEvidence));
    return;
  }
  evidence.forEach((step, s) => proof.append(renderStep(step, s, t)));
}

function renderStep(step, s, t) {
  const box = el("section", "step");
  const heading = el("h3");
  heading.append(el("span", "kind", `${t.kinds[step.kind] || step.kind}: `));
  heading.append(step.purpose || (step.kind === "sql" ? "" : step.input));
  box.append(heading);
  if (step.kind === "sql") box.append(el("pre", "", step.input));
  if (step.error) {
    box.append(el("p", "failed", `${t.rejected}: ${step.error}`));
    return box;
  }
  const result = step.result;
  if (!result || !result.rows.length) {
    box.append(el("p", "rows-note", t.noRows));
    return box;
  }
  const numeric = result.columns.map((name, c) => !IDENTIFIER_COLUMN.test(name)
    && result.rows.every(row => row[c] === null || /^-?\d+(\.\d+)?$/.test(row[c])));
  const table = el("table");
  const head = el("tr");
  result.columns.forEach((name, c) => head.append(el("th", numeric[c] ? "num" : "", name)));
  const thead = el("thead");
  thead.append(head);
  const tbody = el("tbody");
  result.rows.slice(0, MAX_SHOWN_ROWS).forEach((row, r) => {
    const tr = el("tr");
    row.forEach((value, c) => {
      const td = el("td", numeric[c] ? "num" : "", value === null ? "—" : value);
      if (value === null) td.classList.add("is-null");
      td.dataset.cell = `${s}-${r}-${c}`;
      tr.append(td);
    });
    tbody.append(tr);
  });
  table.append(thead, tbody);
  const wrap = el("div", "table-wrap");
  wrap.append(table);
  box.append(wrap);
  if (result.rows.length > MAX_SHOWN_ROWS) box.append(el("p", "rows-note", t.rows(MAX_SHOWN_ROWS, result.rows.length)));
  if (result.truncated) box.append(el("p", "rows-note", t.truncated));
  return box;
}
