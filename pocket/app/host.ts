// The one host surface this app uses. The device runtime exposes it as
// `globalThis.ui.__launcher`; a host without it answers nothing, which every
// caller here turns into an empty list rather than a crash.
export interface KanadeHost {
  __launcher?: (command: string) => string;
}

export const host = (): KanadeHost | undefined =>
  (globalThis as typeof globalThis & { ui?: KanadeHost }).ui;

/** Run one KANADE bridge verb and return the raw body, or "" when unavailable. */
export const call = (verb: string, arg?: string): string => {
  const ui = host();
  if (!ui?.__launcher) return "";
  const command = arg === undefined ? `KANADE\t${verb}` : `KANADE\t${verb}\t${arg}`;
  try {
    return ui.__launcher(command) ?? "";
  } catch {
    return "";
  }
};
