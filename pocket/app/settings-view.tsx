// NAS settings page.
//
// There is no text input on this profile, so every field opens the framework's
// on-screen keyboard (TextField does that on press; the controller lets the
// page drive it from the d-pad). Values go to the service one key at a time:
// the settings endpoint rejects unknown keys, so a partial write is the
// expected shape rather than a whole-map PUT.
import { createSignal } from "solid-js";
import { Text, View } from "@pocketjs/framework/solid/components";
import { Osk, createOsk } from "@pocketjs/framework/osk";
import { BTN } from "@pocketjs/framework/input";
import { onFrame } from "@pocketjs/framework/lifecycle";

import { call } from "./host";

type Field = {
  key: string;
  label: string;
  hint: string;
  secret?: boolean;
};

const FIELDS: Field[] = [
  { key: "nas_host", label: "HOST", hint: "server or ip" },
  { key: "nas_share", label: "SHARE", hint: "share name" },
  { key: "nas_dir", label: "DIR", hint: "folder on the share" },
  { key: "nas_user", label: "USER", hint: "account" },
  { key: "nas_password", label: "PASS", hint: "••••", secret: true },
];

const ACTIONS = ["ENABLED", "TEST", "PULL", "BACK"] as const;

const hex = (text: string): string => {
  let out = "";
  for (let i = 0; i < text.length; i += 1) {
    const code = text.charCodeAt(i);
    // The bridge carries argv, so only ASCII is expected in a share password;
    // anything wider is sent as UTF-8 bytes rather than truncated silently.
    const bytes = code < 0x80 ? [code] : Array.from(new TextEncoder().encode(text[i]));
    for (const byte of bytes) out += byte.toString(16).padStart(2, "0");
  }
  return out;
};

// Module scope on purpose: only one settings page exists, and the frame loop is
// owned by the list view (a second onFrame registration is not delivered), so
// the page exposes its controls instead of listening for itself.
const [cursor, setCursor] = createSignal(0);
const [note, setNote] = createSignal("");
// The documented seam: one controller, one <Osk> docked at the bottom of the
// column. TextField's overlay path did not surface on this profile.
const [editing, setEditing] = createSignal("");
const [buffer, setBuffer] = createSignal("");

export const settingsRowCount = (): number => ACTIONS.length + FIELDS.length;

export const settingsInput = (action: "up" | "down" | "activate" | "keys" | "back"): void => {
  const total = settingsRowCount();
  if (action === "up") {
    setCursor((value) => (value - 1 + total) % total);
    return;
  }
  if (action === "down") {
    setCursor((value) => (value + 1) % total);
    return;
  }
  if (action === "back") {
    settingsBack?.();
    return;
  }
  if (action === "keys") {
    settingsActivate?.();
    return;
  }
  settingsActivate?.();
};

export const settingsKeyboardOpen = (): boolean => osk.isOpen();

export const settingsRowKey = (): string => [...ACTIONS.map((a) => a), ...FIELDS.map((f) => f.key)][cursor()] ?? "";

export let settingsActivate: (() => void) | undefined;
export let settingsBack: (() => void) | undefined;

let saveField: ((key: string, value: string) => void) | undefined;

const osk = createOsk({
  value: buffer,
  setValue: (next) => setBuffer(next),
  onCommit: (text) => {
    const key = editing();
    setEditing("");
    saveField?.(key, text);
  },
  closeOnCommit: true,
});

export const SettingsView = (props: {
  values: () => Record<string, string>;
  active: () => boolean;
  onClose: () => void;
  onRefresh: () => void;
}) => {

  const rows = () => [...ACTIONS.map((a) => a), ...FIELDS.map((f) => f.key)];
  const total = () => rows().length;

  const enabled = () => (props.values().nas_enabled ?? "off").toLowerCase() === "on";

  const saveKey = (key: string, value: string) => {
    const answer = call("settings_put", JSON.stringify({ [key]: value }));
    props.onRefresh();
    setNote(answer.startsWith("{\"ok") || answer === "" ? `${key} saved` : "save rejected");
  };

  const submit = (field: Field, value: string) => {
    if (field.secret) {
      const answer = call("nas_password", hex(value));
      setNote(answer.includes("\"password_set\":true") ? "pass saved" : "pass rejected");
      props.onRefresh();
      return;
    }
    saveKey(field.key, value);
  };

  const activate = () => {
    const row = rows()[cursor()];
    if (row === "BACK") {
      props.onClose();
      return;
    }
    if (row === "ENABLED") {
      saveKey("nas_enabled", enabled() ? "off" : "on");
      setNote(enabled() ? "nas off" : "nas on");
      return;
    }
    if (row === "TEST") {
      const answer = call("nas_test");
      setNote(answer.startsWith("{\"files") ? answer.replace(/[{}"]/g, " ").trim() : "test failed");
      return;
    }
    if (row === "PULL") {
      const answer = call("nas_pull");
      setNote(answer.includes("\"started\":true") ? "pulling…" : "pull refused");
      return;
    }
    setEditing(row);
    setBuffer(props.values()[row] ?? "");
    osk.open();
  };

  // The list's frame loop calls into these; the page never registers its own.
  settingsActivate = activate;
  settingsBack = props.onClose;
  saveField = (key, value) => {
    const field = FIELDS.find((candidate) => candidate.key === key);
    if (field) submit(field, value);
  };

  return (
    <View class="w-[360px] h-[240px] bg-[#0c131a] flex-col">
      <View class="w-[360px] h-[22px] px-2 flex-row items-center justify-between bg-[#101c26]">
        <Text class="text-xs font-bold text-[#58c8e8]">NAS</Text>
        <Text class="text-xs font-bold text-[#d8e8ff]">{enabled() ? "ON" : "OFF"}</Text>
        <Text class="text-xs text-[#7d8fa0]">{props.values().nas_password_set === "yes" ? "PASS SET" : "NO PASS"}</Text>
      </View>

      <View class="w-[360px] h-[176px] px-2 pt-1 flex-col">
        {ACTIONS.map((action) => (
          <View
            class={
              rows()[cursor()] === action
                ? "w-[344px] h-[18px] flex-row items-center bg-[#1e3042] px-2"
                : "w-[344px] h-[18px] flex-row items-center bg-[#0c131a] px-2"
            }
          >
            <Text class="w-[10px] text-xs text-[#58c8e8]">{rows()[cursor()] === action ? ">" : " "}</Text>
            <Text class="w-[100px] text-xs text-[#d8e8ff]">{action}</Text>
            <Text class="w-[226px] text-xs text-[#5a6b7a]">
              {action === "ENABLED" ? (enabled() ? "on" : "off") : action === "BACK" ? "leave settings" : ""}
            </Text>
          </View>
        ))}
        {FIELDS.map((field) => (
          <View
            class={
              rows()[cursor()] === field.key
                ? "w-[344px] h-[18px] flex-row items-center bg-[#1e3042] px-2"
                : "w-[344px] h-[18px] flex-row items-center bg-[#0c131a] px-2"
            }
          >
            <Text class="w-[10px] text-xs text-[#58c8e8]">{rows()[cursor()] === field.key ? ">" : " "}</Text>
            <Text class="w-[52px] text-xs text-[#7d8fa0]">{field.label}</Text>
            {/* While the keyboard edits this row it shows the live buffer, so
                the panel and the OSK can never disagree about the text. */}
            <Text class="w-[282px] text-xs text-[#d8e8ff]">
              {editing() === field.key
                ? buffer() || field.hint
                : field.secret
                  ? (props.values().nas_password_set === "yes" ? "••••••" : field.hint)
                  : props.values()[field.key] || field.hint}
            </Text>
          </View>
        ))}
      </View>

      <Osk osk={osk} theme="dark" />

      <View class="w-[360px] h-[42px] px-2 flex-col bg-[#101c26]">
        <View class="w-[344px] h-[20px] flex-row items-center">
          <Text class="text-xs text-[#7d8fa0]">{note() || "A edit  L1 back"}</Text>
        </View>
        <View class="w-[344px] h-[20px] flex-row items-center justify-between">
          <Text class="text-xs text-[#3f5060]">START keys  B back</Text>
          <Text class="text-xs text-[#58c8e8]">{cursor() + 1}/{total()}</Text>
        </View>
      </View>
    </View>
  );
};
