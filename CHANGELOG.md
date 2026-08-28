# Changelog

All notable changes to the Kip desktop app. The retrieval layer has its own
changelog at [JWE24-code/kip](https://github.com/JWE24-code/kip/blob/main/CHANGELOG.md).

## [0.2.2] — 2026-08-28

De-Logseq'd the first-run experience and a batch of polish.

- **The demo graph is Kip's now** — the unsaved graph you land in before
  opening a folder used to seed Logseq's own tutorial. It's a Kip welcome
  journal: the slogan, the farm metaphor, hatch / peck / groom, the coop
  folders, and a first-five-minutes checklist, with a "The Nest" page
  linked from it.
- **Settings cleanup** — removed the inherited Logseq account, Logseq Sync
  and local-git version-control UI; Kip has no account backend of its own.
  Also removed the `mod+g c` git-commit shortcut.
- **Egg + slogan** — the Peck home screen shows an egg (matching the
  website) and "Don't browse your notes. Peck them. Get answers." The
  onboarding screen carries the slogan too.
- **Welcome card** — a one-time card on the first open of a coop: the
  metaphor in three lines and a shortcut into Settings → LLM.
- **Paste text as a source** — a "Paste text…" button in Hatch saves
  pasted text into `eggs/` as a Markdown file, hatchable like any other.
- **Metaphor tooltips** — `eggs` / `nest` / `clucks` / `roost` /
  `henhouse` carry hover glosses throughout the UI, with a one-line legend
  in Coop status.
- **Citations peek** — clicking a `[[page]]` citation in a Peck answer
  opens it in the right sidebar instead of switching to Documents;
  cmd/ctrl-click still opens Documents.
- **Trimmed the "…" menu** — dropped the leftover Logseq entries (plugins,
  themes, export graph, import) and the redundant Peck entry.
- Opening Hatch with no folder open now explains itself instead of
  erroring.
- **Windows builds are code-signed** (self-signed for now) — the binary
  carries a publisher identity; SmartScreen still warns until there's a
  real certificate.

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
