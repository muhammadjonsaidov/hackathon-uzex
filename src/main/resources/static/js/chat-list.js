import { el, icon } from "./dom.js";

const SEARCH_FROM = 6;

/*
 * The list of chats, with rename and delete done in place. It owns only what the list is doing
 * right now: which row is being edited, which is asking for confirmation, and the search text.
 * The chats themselves, and what renaming or deleting one means, belong to the page.
 */
export function createChatList({ chats, activeId, isBusy, onOpen, onRename, onDelete }) {
  let editingId = null;
  let confirmingId = null;
  let search = "";
  let listNode = null;
  let texts = null;

  function matches(chat) {
    return !search || chat.title.toLowerCase().includes(search.toLowerCase());
  }

  function iconButton(name, label, onClick) {
    const button = el("button", "chat-action");
    button.type = "button";
    button.title = label;
    button.setAttribute("aria-label", label);
    button.append(icon(name));
    button.addEventListener("click", onClick);
    return button;
  }

  function normalRow(chat) {
    const row = el("li", "chat-row");
    const open = el("button", "chat-open");
    open.type = "button";
    open.title = chat.title;
    open.append(el("span", "chat-lang", chat.lang.toUpperCase()), el("span", "chat-title", chat.title));
    if (chat.id === activeId()) open.setAttribute("aria-current", "true");
    open.addEventListener("click", () => onOpen(chat.id));
    const actions = el("div", "chat-actions");
    actions.append(iconButton("pencil", texts.rename, () => {
      editingId = chat.id;
      confirmingId = null;
      refresh();
    }));
    if (!isBusy(chat.id)) {
      actions.append(iconButton("trash", texts.delete, () => {
        confirmingId = chat.id;
        editingId = null;
        refresh();
        listNode.querySelector(".chat-confirm-keep")?.focus();
      }));
    }
    row.append(open, actions);
    return row;
  }

  function editingRow(chat) {
    const row = el("li", "chat-row is-editing");
    const input = el("input", "rename-input");
    input.value = chat.title;
    input.maxLength = 80;
    input.setAttribute("aria-label", texts.rename);
    let finished = false;
    const finish = save => {
      if (finished) return;
      finished = true;
      editingId = null;
      const title = input.value.trim();
      if (save && title && title !== chat.title) onRename(chat.id, title);
      refresh();
    };
    input.addEventListener("keydown", event => {
      if (event.key === "Enter") finish(true);
      if (event.key === "Escape") finish(false);
    });
    input.addEventListener("blur", () => finish(true));
    row.append(input);
    return row;
  }

  function confirmingRow(chat) {
    const row = el("li", "chat-row is-confirming");
    row.append(el("span", "chat-confirm-text", texts.deleteConfirm));
    const remove = el("button", "chat-confirm-delete", texts.delete);
    remove.type = "button";
    remove.addEventListener("click", () => {
      confirmingId = null;
      onDelete(chat.id);
    });
    const keep = el("button", "chat-confirm-keep", texts.cancel);
    keep.type = "button";
    keep.addEventListener("click", () => {
      confirmingId = null;
      refresh();
    });
    row.addEventListener("keydown", event => {
      if (event.key === "Escape") keep.click();
    });
    row.append(remove, keep);
    return row;
  }

  function row(chat) {
    if (chat.id === editingId) return editingRow(chat);
    if (chat.id === confirmingId) return confirmingRow(chat);
    return normalRow(chat);
  }

  function refresh() {
    if (!listNode) return;
    const shown = [...chats()].sort((a, b) => b.updatedAt - a.updatedAt).filter(matches);
    listNode.replaceChildren(...shown.map(row));
    if (search && !shown.length) listNode.append(el("li", "none", texts.noMatches));
    const input = listNode.querySelector(".rename-input");
    if (input) {
      input.focus();
      input.select();
    }
  }

  return {
    /* Redrawing now would take the text field away from someone typing a new name. */
    isEditing: () => editingId !== null,

    render(box, t) {
      texts = t;
      if (chats().length >= SEARCH_FROM) {
        const field = el("input", "chat-search");
        field.type = "search";
        field.placeholder = t.searchChats;
        field.setAttribute("aria-label", t.searchChats);
        field.value = search;
        field.addEventListener("input", () => {
          search = field.value;
          refresh();
        });
        box.append(field);
      } else {
        search = "";
      }
      listNode = el("ul", "chat-list");
      box.append(listNode);
      refresh();
    }
  };
}
