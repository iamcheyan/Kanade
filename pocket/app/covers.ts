// Row covers.
//
// The remote cover URL in the library is unrenderable on the panel: the guest
// has no network and the runtime has no image decoder. The service side decodes
// the sidecar JPEG next to the download and returns raw RGBA over the bridge as
// base64, which is the one shape `uploadTexture` accepts. Handles are cached per
// video id and the cache is bounded, because texture slots are a fixed pool.
import { getOps } from "@pocketjs/framework";

import { call } from "./host";

// The runtime rejects textures whose dimensions are not 16-aligned
// (upload_texture returns -1 for 17x16 and 1024x16), so the row cover is 32x16
// rather than a 16:9 cell.
export const COVER_W = 32;
export const COVER_H = 16;
const LIMIT = 48;

const cache = new Map<string, number>();

function base64ToBytes(text: string): Uint8Array | null {
  const table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const lookup = new Int16Array(128).fill(-1);
  for (let i = 0; i < table.length; i += 1) lookup[table.charCodeAt(i)] = i;
  let length = text.length;
  while (length > 0 && text.charCodeAt(length - 1) === 61) length -= 1;
  const out = new Uint8Array(Math.floor((length * 3) / 4));
  let buffer = 0;
  let bits = 0;
  let written = 0;
  for (let i = 0; i < length; i += 1) {
    const value = lookup[text.charCodeAt(i)];
    if (value < 0) return null;
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[written] = (buffer >> bits) & 0xff;
      written += 1;
    }
  }
  return out.subarray(0, written);
}

/** Texture handle for `id`, or -1 when the service has no cover for it. */
export function coverHandle(id: string): number {
  if (!id) return -1;
  const cached = cache.get(id);
  if (cached !== undefined) return cached;

  const answer = call("thumb", id);
  let handle = -1;
  if (answer && !answer.startsWith("error:")) {
    const bytes = base64ToBytes(answer);
    if (bytes && bytes.length === COVER_W * COVER_H * 4) {
      try {
        // PSM 3 = 8888, uploaded verbatim as RGBA.
        handle = getOps().uploadTexture(bytes, COVER_W, COVER_H, 3);
        if (handle < 0) handle = -1;
      } catch {
        handle = -1;
      }
    }
  }

  if (cache.size >= LIMIT) {
    const [oldest, oldHandle] = cache.entries().next().value as [string, number];
    cache.delete(oldest);
    if (oldHandle >= 0) {
      try {
        getOps().freeTexture?.(oldHandle);
      } catch {
        // A stale handle draws nothing; nothing to recover from here.
      }
    }
  }
  cache.set(id, handle);
  return handle;
}
