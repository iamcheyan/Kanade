import { defineRuntimeConfig } from "../../framework/src/runtime-config.ts";
import { linuxProgramProfile } from "../linux-kms/pocket.runtime-config.ts";
import profile from "./profile.ts";

export default defineRuntimeConfig({ profile: linuxProgramProfile(profile) });
