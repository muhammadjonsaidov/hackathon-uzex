/* Calls the JSON API and throws on any error status, with the server's error code when it sends one. */
export async function api(path, options) {
  const res = await fetch(path, options);
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    const error = new Error(`HTTP ${res.status}`);
    error.code = body.error;
    throw error;
  }
  return res.status === 204 ? null : res.json();
}

export function postJson(path, body) {
  return api(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

/* Reads a server-sent event stream from a POST response. */
export async function readEvents(body, onEvent) {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = "";
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += value.replace(/\r\n/g, "\n");
    let end;
    while ((end = buffer.indexOf("\n\n")) >= 0) {
      const chunk = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = "message";
      let data = "";
      chunk.split("\n").forEach(line => {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        if (line.startsWith("data:")) data += line.slice(5);
      });
      if (data) onEvent(event, JSON.parse(data));
    }
  }
}
