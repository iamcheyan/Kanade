import { defineProfile } from "../../framework/src/profile.ts";

// The RG34XX panel profile, unchanged from the boot launcher so the two apps
// share one admission hash and either one can be replaced without a rebuild.
export default defineProfile({
  id: "rocknix-h700-kms",
  platform: "linux",
  form: "embedded",
  tickHz: 60,
  display: {
    physicalViewport: [720, 480],
    logicalViewports: [[360, 240]],
    rasterDensity: 2,
    presentations: ["native", "integer-fit"],
  },
  capabilities: ["input.buttons", "text.glyphs.baked"],
  roleCapabilities: {},
});
