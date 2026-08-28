# Changelog

All notable changes to the Kip desktop app. The retrieval layer has its own
changelog at [JWE24-code/kip](https://github.com/JWE24-code/kip/blob/main/CHANGELOG.md).

## [0.2.1] — 2026-08-28

More onboarding polish, plus a way to know when a new build exists. (The
features below were merged after `v0.2.0` was cut.)

- **In-app update check** — on launch and every 24h, Kip checks GitHub
  Releases for a newer version and shows a dismissible header banner if one
  exists. No download, no auto-install; any failure is silent. Settings →
  About gains a manual "Check now".
- **"Ask Kip about them" after a hatch** — when a Hatch run creates new nest
  pages, a button drops you into Peck with a question about the first one
  pre-filled.
- **"Add source…" file picker in Hatch** — pick one or more Markdown/text
  files from a dialog to copy into `eggs/`; byte-identical duplicates are
  skipped, unsupported types are rejected visibly.
- **Local/Ollama reachability check in Settings → LLM** — when the provider is
  Local or Other, a live status line under Base URL shows whether the endpoint
  answers and lists its models; actionable message when it doesn't.
- **"Your coop" overview in Coop status** — counts of sources in `eggs/` and
  entity/concept/source pages in the nest, last hatch and last groom times,
  and a shortcut to hatch anything pending.
- **Kip-native help panel** — the in-app help replaces the old Logseq links
  with Getting started / how it fits together / the coop / feedback, pointing
  at the Kip repos.
- **Friendlier LLM errors** — provider failures in Peck, Hatch and Settings
  now show a short plain-language cause and hint, with the raw error behind a
  "Show details" toggle.

## [0.2.0] — 2026-08-28

Onboarding polish release.

- **LLM-provider banner** — Peck and Hatch now show a non-blocking banner when
  no usable provider is configured, with a shortcut to Settings → LLM.
- **Drag-and-drop sources** — drop a Markdown or text file onto Peck or Hatch
  to copy it into `eggs/` and optionally hatch it immediately.
- **First-run checklist** — Peck's empty state shows a 3-step checklist (set
  provider, add source, hatch it) until Kip is ready, then the example prompts
  return.
- **i18n fix** — "About Logseq" updated to "About Kip" across all locales and
  matching test assertions.
- Removed the managed-routing provider option; the backend will return as a
  separate project later.

## [0.1.0] — 2026-08-28

First public release. Early and rough — built to gather feedback.

- Pecking-first Logseq fork with LLM retrieval layer integration.
- Panels: Peck, Hatch sources, Coop status / Groom, Exports, Reminders.
- Settings tabs: LLM, Skills.
- Whiteboards as mindmaps with curved connectors and keyboard mindmap mode.
- Rebranded from Logseq (`logseq/og`): name, icons, URL scheme (`kip://`),
  config dir (`~/.kip`), bundle id (`app.kip`).
- Windows + Linux x64 builds via GitHub Actions.

### Known limitations

Unsigned binaries; no auto-updater; no installer (folder-zip); no macOS or
mobile; skills run unsandboxed.
