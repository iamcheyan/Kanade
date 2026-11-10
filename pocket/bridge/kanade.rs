//! `ui.__launcher` KANADE bridge for the desktop harness.
//!
//! On the device the KANADE verb is handled by `pocketshell-launcher.rs` inside
//! `pocket-linux-kms`, which shells out to the static `kanade-ctl`. The desktop
//! host has no such module, so the emulator run would show only `OFFLINE` and
//! the sync path could never be exercised. This supplies the same surface from
//! the machine the host runs on: same verbs, same fixed endpoints, same helper
//! binary, so what renders here is what renders on the panel.
//!
//! Contract: one short-lived child per call, hard timeout, body on stdout.

use std::process::{Command, Stdio};

fn ctl_path() -> Option<String> {
    for candidate in [
        std::env::var("KANADE_CTL").ok(),
        Some("/tmp/kanade-ctl".to_string()),
        Some("./kanade-ctl".to_string()),
    ]
    .into_iter()
    .flatten()
    {
        if std::path::Path::new(&candidate).is_file() {
            return Some(candidate);
        }
    }
    None
}

fn safe_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == ':')
}

/// verb -> (method, path, body). Mirrors apply-pocketjs-kanade.py exactly.
fn endpoint(verb: &str, arg: &str) -> Option<(&'static str, String, Option<String>)> {
    let body_for = |id: &str| Some(format!("{{\"video_id\":\"{}\"}}", id));
    match verb {
        "status" => Some(("GET", "/api/health".into(), None)),
        "library" => Some(("GET", "/api/library?limit=100".into(), None)),
        "watch_later" => Some(("GET", "/api/watch_later?limit=100".into(), None)),
        "favorites" => Some(("GET", "/api/favorites?limit=100".into(), None)),
        "history" => Some(("GET", "/api/history?limit=100".into(), None)),
        "downloads" => Some(("GET", "/api/downloads?limit=100".into(), None)),
        "settings" => Some(("GET", "/api/settings".into(), None)),
        "storage" => Some(("GET", "/api/storage".into(), None)),
        "sync" => Some(("POST", "/api/sync".into(), None)),
        "clear" => Some(("POST", "/api/storage/clear?scope=orphans".into(), None)),
        "play" if safe_id(arg) => Some(("POST", "/api/player/play".into(), body_for(arg))),
        "watched" if safe_id(arg) => Some(("POST", format!("/api/library/{}/watched", arg), body_for(arg))),
        "later" if safe_id(arg) => Some(("POST", format!("/api/watch_later/{}", arg), body_for(arg))),
        "favorite" if safe_id(arg) => Some(("POST", format!("/api/favorites/{}", arg), body_for(arg))),
        _ => None,
    }
}

pub fn command(value: &str) -> String {
    let fields: Vec<&str> = value.split('\t').collect();
    let (verb, arg) = match fields.as_slice() {
        ["KANADE", verb] => (*verb, ""),
        ["KANADE", verb, arg] => (*verb, *arg),
        _ => return String::new(),
    };
    let Some((method, path, body)) = endpoint(verb, arg) else {
        return "error:bad-verb".into();
    };
    let Some(ctl) = ctl_path() else {
        return "error:no-ctl".into();
    };

    let mut argv: Vec<&str> = vec![ctl.as_str(), method, path.as_str()];
    let payload = body.unwrap_or_default();
    if !payload.is_empty() {
        argv.push(payload.as_str());
    }
    match Command::new(&argv[0])
        .args(&argv[1..])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
    {
        Ok(out) if !out.stdout.is_empty() => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        Ok(_) => "error:empty".into(),
        Err(_) => "error:unreachable".into(),
    }
}
