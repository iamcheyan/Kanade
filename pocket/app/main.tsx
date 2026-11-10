import { createSignal, onMount } from "solid-js";
import { mount } from "@pocketjs/framework/solid";
import { getOps } from "@pocketjs/framework";
import { Image, Text, View } from "@pocketjs/framework/solid/components";
import { BTN, onTextEvent } from "@pocketjs/framework/input";
import { onFrame } from "@pocketjs/framework/lifecycle";

import { coverHandle } from "./covers";
import { SettingsView, settingsInput, settingsKeyboardOpen } from "./settings-view";
import { call } from "./host";
import { TABS, clip, clock, parseVideos, serviceUp, storageSummary, type VideoRow } from "./kanade-model";

// A cover is a texture bound after its node exists: the guest cannot fetch and
// cannot decode, so the handle comes from the service through the bridge and is
// attached with setImage, which is also how the framework's resource images work
// when the pixels arrive late.
const Cover = (props: { id: string }) => {
  // Bind in the ref rather than in an effect: the texture is computed once per
  // id and a deferred effect would leave the node without a handle for a frame,
  // which renders as an empty cell.
  const bind = (node: { id: number }) => {
    const handle = coverHandle(props.id);
    if (handle >= 0) getOps().setImage(node.id, handle);
  };
  // An image node needs explicit layout: `class` alone leaves it unmeasured and
  // the renderer drops it.
  return <Image ref={(n: unknown) => bind(n as { id: number })} style={{ width: 32, height: 16 }} />;
};

// 360x240 logical at 2x raster: a 22px header, seven 22px rows, a 42px footer.
const ROWS = 7;
const POLL_FRAMES = 120;

mount(() => {
  const [tab, setTab] = createSignal(0);
  const [rows, setRows] = createSignal<VideoRow[]>([]);
  const [cursor, setCursor] = createSignal(0);
  const [online, setOnline] = createSignal(false);
  const [note, setNote] = createSignal("starting…");
  const [noteFrames, setNoteFrames] = createSignal(0);
  // Cached: a bridge call is a synchronous host round-trip on the render thread
  // and must never run from a per-repaint accessor.
  const [storage, setStorage] = createSignal("--");
  // The NAS page is a mode, not a tab: it has its own pad handling and its own
  // fields, and the list must stop reacting while it is up.
  const [settingsOpen, setSettingsOpen] = createSignal(false);
  const [settingsValues, setSettingsValues] = createSignal<Record<string, string>>({});

  let previousButtons = 0;
  let frames = 0;
  let armed = false;

  const flash = (message: string) => {
    setNote(message);
    setNoteFrames(90);
  };

  const readSettings = () => {
    try {
      setSettingsValues(JSON.parse(call("settings")) as Record<string, string>);
    } catch {
      setSettingsValues({});
    }
  };

  const load = () => {
    const health = call("status");
    const up = serviceUp(health);
    setOnline(up);
    if (!up) {
      setRows([]);
      setStorage("--");
      flash("videod offline");
      return;
    }
    const items = parseVideos(call(TABS[tab()].verb));
    setRows(items);
    setCursor((current) => Math.max(0, Math.min(Math.max(0, items.length - 1), current)));
    setStorage(storageSummary(call("storage")));
    flash(`${items.length} item(s)`);
  };

  const selected = (): VideoRow | undefined => rows()[cursor()];

  const play = () => {
    const item = selected();
    if (!item) return;
    const answer = call("play", item.id || "");
    flash(answer.startsWith("error:") ? `play failed (${answer.slice(6)})` : `playing ${clip(item.title, 16)}`);
  };

  const toggleFavorite = () => {
    const item = selected();
    if (!item) return;
    flash(call("favorite", item.id).startsWith("error:") ? "favorite failed" : "favorite toggled");
  };

  const addLater = () => {
    const item = selected();
    if (!item) return;
    flash(call("later", item.id).startsWith("error:") ? "watch later failed" : "added to watch later");
  };

  const markWatched = () => {
    const item = selected();
    if (!item) return;
    flash(call("watched", item.id).startsWith("error:") ? "mark failed" : "marked watched");
    load();
  };

  const switchTab = (next: number) => {
    setTab((next + TABS.length) % TABS.length);
    setCursor(0);
    load();
  };

  // R1 asks the service to scan the card. The reply carries how many files were
  // new, so the panel can say what changed instead of "requested" forever.
  const syncNow = () => {
    flash("syncing…");
    const answer = call("sync");
    if (!answer || answer.startsWith("error:")) {
      flash("sync unavailable");
      return;
    }
    let added = -1;
    try {
      added = Number(JSON.parse(answer).local_added ?? -1);
    } catch {
      added = -1;
    }
    load();
    if (added > 0) flash(`+${added} new`);
    else if (added === 0) flash("no new files");
    else flash("sync requested");
  };

  onMount(load);

  // Keyboard path for the desktop harness and a USB keyboard on the device.
  onTextEvent((event) => {
    if (event.kind !== "key" || !event.pressed) return;
    if (event.key === "ArrowUp") setCursor((c) => Math.max(0, c - 1));
    else if (event.key === "ArrowDown") setCursor((c) => Math.min(Math.max(0, rows().length - 1), c + 1));
    else if (event.key === "ArrowLeft") switchTab(tab() - 1);
    else if (event.key === "ArrowRight") switchTab(tab() + 1);
    else if (event.key === "Enter" || event.key === " ") play();
  });

  onFrame((buttons: number) => {
    frames += 1;
    const pressed = buttons & ~previousButtons;
    previousButtons = buttons;

    if (noteFrames() > 0) setNoteFrames((n) => n - 1);

    // Arm on a frame counter: this pad never reports an all-clear snapshot, so
    // gating on `buttons === 0` would leave the app permanently deaf.
    if (!armed) {
      if (frames > 3) armed = true;
      return;
    }

    // The settings page has no frame loop of its own (a second registration is
    // not delivered), so the list routes the pad into it while it is up.
    if (settingsOpen()) {
      // The OSK is modal and owns the pad while it is up; raw onFrame reads are
      // not blocked for us, so skip them or every key is typed twice.
      if (settingsKeyboardOpen()) return;
      if (pressed & BTN.UP) settingsInput("up");
      if (pressed & BTN.DOWN) settingsInput("down");
      if (pressed & BTN.CROSS) settingsInput("activate");
      if (pressed & BTN.START) settingsInput("keys");
      if (pressed & BTN.CIRCLE) settingsInput("back");
      return;
    }
    if (pressed & BTN.DOWN) setCursor((c) => Math.min(Math.max(0, rows().length - 1), c + 1));
    if (pressed & BTN.UP) setCursor((c) => Math.max(0, c - 1));
    if (pressed & BTN.RIGHT) switchTab(tab() + 1);
    if (pressed & BTN.LEFT) switchTab(tab() - 1);
    if (pressed & BTN.CIRCLE) play();
    if (pressed & BTN.SQUARE) toggleFavorite();
    if (pressed & BTN.TRIANGLE) addLater();
    if (pressed & BTN.START) markWatched();
    if (pressed & BTN.RTRIGGER) syncNow();
    if (pressed & BTN.SELECT) {
      readSettings();
      setSettingsOpen(true);
    }
    if (pressed & BTN.CROSS) { setCursor(0); load(); }

    if (frames % POLL_FRAMES === 0) load();
  });

  const windowed = () => {
    const total = rows().length;
    if (total <= ROWS) return rows().map((row, index) => ({ row, index }));
    const start = Math.max(0, Math.min(total - ROWS, cursor() - Math.floor(ROWS / 2)));
    return rows().slice(start, start + ROWS).map((row, i) => ({ row, index: start + i }));
  };

  const counter = () => (rows().length === 0 ? "0/0" : `${cursor() + 1}/${rows().length}`);

  // A conditional in the component body runs once: this renderer mounts the
  // view a single time, so the page switch has to be a memo inside the tree.
  return (
    <View class="w-[360px] h-[240px] bg-[#0c131a]">
      {settingsOpen() ? (
        <SettingsView
          values={settingsValues}
          active={settingsOpen}
          onClose={() => setSettingsOpen(false)}
          onRefresh={readSettings}
        />
      ) : (
      <View class="w-[360px] h-[240px] bg-[#0c131a] flex-col">
      <View class="w-[360px] h-[22px] px-2 flex-row items-center justify-between bg-[#101c26]">
        <Text class="text-xs font-bold text-[#58c8e8]">KANADE</Text>
        <Text class="text-xs font-bold text-[#d8e8ff]">{TABS[tab()].label}</Text>
        <Text class="text-xs font-bold text-[#7d8fa0]">{online() ? "ONLINE" : "OFFLINE"}</Text>
      </View>

      <View class="w-[360px] h-[176px] px-2 pt-1 flex-col">
        {windowed().map((entry) => (
          <View
            class={
              entry.index === cursor()
                ? "w-[344px] h-[22px] flex-row items-center bg-[#1e3042] px-2"
                : "w-[344px] h-[22px] flex-row items-center bg-[#0c131a] px-2"
            }
          >
            <Text class="w-[10px] text-xs text-[#58c8e8]">{entry.index === cursor() ? ">" : " "}</Text>
            <Cover id={entry.row.id} />
            <Text class="w-[240px] text-xs text-[#d8e8ff]">{clip(entry.row.title, 20)}</Text>
            <Text class="w-[46px] text-xs text-[#5a6b7a]">
              {entry.row.progress > 0 ? `${Math.round(entry.row.progress * 100)}%` : clock(entry.row.duration)}
            </Text>
          </View>
        ))}
        {rows().length === 0 ? (
          <View class="w-[344px] h-[22px] px-2 flex-row items-center">
            <Text class="text-xs text-[#5a6b7a]">
              {online() ? "no items — press R1 to sync" : "videod is not running"}
            </Text>
          </View>
        ) : null}
      </View>

      <View class="w-[360px] h-[42px] px-2 flex-col bg-[#101c26]">
        <View class="w-[344px] h-[20px] flex-row items-center justify-between">
          <Text class="text-xs text-[#5a6b7a]">A play  X fav  Y later</Text>
          <Text class="text-xs text-[#58c8e8]">{counter()}</Text>
        </View>
        <View class="w-[344px] h-[20px] flex-row items-center justify-between">
          <Text class="text-xs text-[#7d8fa0]">{noteFrames() > 0 ? note() : storage()}</Text>
          <Text class="text-xs text-[#3f5060]">R1 sync  L1 NAS  B reload</Text>
        </View>
      </View>
      </View>
      )}
    </View>
  );
});
