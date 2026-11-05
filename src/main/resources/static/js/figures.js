export const MAX_SHOWN_ROWS = 50;
/* Identifiers look numeric but are not quantities: no digit grouping, no figure links. */
export const IDENTIFIER_COLUMN = /(^|_)(inn|no|code|year|month|quarter)$/i;

/* Layout only: the model sometimes writes a ranking as "1) … 2) … 3) …" in one paragraph. */
export function separateList(text) {
  const start = text.match(/(^|\s)1[.)]\s/);
  if (!start) return text;
  const parts = [];
  let from = start.index + start[1].length;
  const intro = text.slice(0, from).trimEnd();
  for (let n = 2; ; n++) {
    const next = text.slice(from + 2).match(new RegExp(`\\s${n}[.)]\\s`));
    if (!next) break;
    const end = from + 2 + next.index;
    parts.push(text.slice(from, end).trimEnd().replace(/[;,]$/, ""));
    from = end + 1;
  }
  if (!parts.length) return text;
  parts.push(text.slice(from).trimEnd());
  return (intro ? `${intro}\n` : "") + parts.join("\n");
}


/* Figures: wrap each number of the answer that traces to a result cell. */
export const NUMBER = /(\d{1,3}(?:[    ]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?)(\s*(?:тыс\.?|тысяч\S*|ming|минг|млн\.?|миллион\S*|mln\.?|million|млрд\.?|миллиард\S*|mlrd\.?|milliard\S*|трлн\.?|триллион\S*|trln\.?|trillion\S*))?/g;

function scaleOf(word) {
  if (!word) return 1;
  const w = word.trim().toLowerCase();
  if (/^(тыс|ming|минг)/.test(w)) return 1e3;
  if (/^(млн|миллион|mln|million)/.test(w)) return 1e6;
  if (/^(млрд|миллиард|mlrd|milliard)/.test(w)) return 1e9;
  return 1e12;
}

export function matchCells(digits, word, cells) {
  const compact = digits.replace(/[    ]/g, "").replace(",", ".");
  const shown = parseFloat(compact);
  const decimals = (compact.split(".")[1] || "").length;
  const scale = scaleOf(word);
  if (scale === 1 && decimals === 0 && (shown <= 12 || (shown >= 2022 && shown <= 2026))) return [];
  const half = 0.5 * Math.pow(10, -decimals) * scale;
  return cells.filter(c => Math.abs(c.value - shown * scale) <= half);
}


/* Collected once per turn so figures and table cells share ids. */
export function indexCells(turn) {
  turn.cells = [];
  (turn.response.evidence || []).forEach((step, s) => {
    if (step.kind !== "sql" || !step.result) return;
    step.result.rows.slice(0, MAX_SHOWN_ROWS).forEach((row, r) => row.forEach((value, c) => {
      if (value !== null && !IDENTIFIER_COLUMN.test(step.result.columns[c]) && /^-?\d+(\.\d+)?$/.test(value)) {
        turn.cells.push({ id: `${s}-${r}-${c}`, value: parseFloat(value) });
      }
    }));
  });
}
