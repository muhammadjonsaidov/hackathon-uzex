const KEY = "lang";

export function savedLanguage() {
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === "ru" || saved === "uz") return saved;
  } catch (e) { /* storage may be blocked */ }
  return (navigator.language || "").startsWith("ru") ? "ru" : "uz";
}

export function saveLanguage(lang) {
  try { localStorage.setItem(KEY, lang); } catch (e) { /* storage may be blocked */ }
}
