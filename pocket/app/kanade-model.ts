// Shape of what videod returns, reduced to what the panel draws.
//
// Parsing is defensive on purpose: the service is a separate process and may
// be stopped, restarting, or answering an older schema. Every field falls back
// to a placeholder so a row renders instead of the whole list disappearing.

export interface VideoRow {
  id: string;
  title: string;
  channel: string;
  duration: number;
  progress: number;
  url: string;
}

export interface Tab {
  key: string;
  verb: string;
  label: string;
}

// L/R cycles these; the verbs are the bridge verbs, not raw paths.
export const TABS: Tab[] = [
  { key: "library", verb: "library", label: "LIBRARY" },
  { key: "later", verb: "watch_later", label: "LATER" },
  { key: "favorites", verb: "favorites", label: "FAVORITES" },
  { key: "history", verb: "history", label: "HISTORY" },
];

const asString = (value: unknown, fallback = ""): string =>
  typeof value === "string" ? value : fallback;

const asNumber = (value: unknown, fallback = 0): number => {
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : fallback;
};

export const parseVideos = (raw: string): VideoRow[] => {
  const text = (raw ?? "").trim();
  if (!text.startsWith("[")) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  return parsed.map((entry) => {
    const row = (entry ?? {}) as Record<string, unknown>;
    return {
      id: asString(row.id) || asString(row.video_id),
      title: asString(row.title, "(untitled)"),
      channel: asString(row.channel_title) || asString(row.channel_id),
      duration: Math.max(0, Math.round(asNumber(row.duration))),
      progress: Math.min(1, Math.max(0, asNumber(row.progress) > 1 ? asNumber(row.progress) / 100 : asNumber(row.progress))),
      url: asString(row.url),
    };
  }).filter((row) => row.id !== "" || row.title !== "(untitled)");
};

/** "1:23:45" / "3:07" — the panel has no room for a suffix. */
export const clock = (seconds: number): string => {
  const s = Math.max(0, Math.round(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => (n < 10 ? `0${n}` : String(n));
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`;
};

/** Trim to a cell budget without splitting a wide glyph in half. */
export const clip = (text: string, cells: number): string => {
  const chars = Array.from(text ?? "");
  if (chars.length <= cells) return text ?? "";
  if (cells <= 1) return chars.slice(0, cells).join("");
  return chars.slice(0, cells - 1).join("") + "…";
};

/** Healthy service = the health endpoint answered with status ok. */
export const serviceUp = (raw: string): boolean => (raw ?? "").includes("\"status\"");

export const storageSummary = (raw: string): string => {
  const text = (raw ?? "").trim();
  if (!text.startsWith("{")) return "--";
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>;
    const mb = Math.round(asNumber(parsed.bytes) / (1024 * 1024));
    const files = Math.round(asNumber(parsed.files));
    return `${files} files / ${mb} MB`;
  } catch {
    return "--";
  }
};
