# Changelog

All notable changes to the Kip desktop app. The retrieval layer has its own
changelog at [JWE24-code/kip](https://github.com/JWE24-code/kip/blob/main/CHANGELOG.md).

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
