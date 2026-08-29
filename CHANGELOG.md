# Changelog

All notable changes to the Kip desktop app. The retrieval layer has its own
changelog at [JWE24-code/kip](https://github.com/JWE24-code/kip/blob/main/CHANGELOG.md).

## [0.3.0] — 2026-08-29

Kip ships as an installer now — and can update itself.

- **Windows installer + Linux AppImage** — the Windows download is a real
  installer (`Kip-Setup-*.exe`): per-user, no admin prompt, start-menu and
  desktop shortcuts, choose-your-folder. Linux gets a self-updating
  AppImage; the portable `tar.gz` is still there for anyone who wants it.
  Windows binaries are code-signed (a self-issued certificate for now, so
  SmartScreen still warns).
- **In-app updates** — the "a newer Kip is available" banner gets an
  **Update** button that downloads the new release and restarts into it,
  with a progress readout. Works on the Windows installer and the Linux
  AppImage; a portable build still points you at the releases page.
- **Fixed: Hatch, Peck and Groom failed in the packaged app** — the
  bundled retrieval layer couldn't load its SQLite engine
  (`better-sqlite3` → `bindings`) from inside `app.asar`, so any run died
  with a "cannot find module" error. Regressed in 0.2.2 with the switch to
  a packed archive. `better-sqlite3` and its loader are now vendored next
  to the scripts.
- **Schedule the deep groom** — Settings → Features gains a "run the deep
  groom on a schedule" toggle (day of week + time). Runs in the main
  process like reminders; nothing fires while Kip is closed, and a slot
  that came due meanwhile runs on the next launch. Coop status shows the
  next run.

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
  erroring — and Hatch / Peck / Groom now refuse to run against the
  in-memory demo graph (or a graph whose folder has gone missing)
  instead of failing deep in a script with a cryptic path error.
- **Windows builds are code-signed** (self-signed for now) — the binary
  carries a publisher identity; SmartScreen still warns until there's a
  real certificate.
- **Hatch: recover from a stopped run** — if a batch dies partway, the
  modal shows how far it got; hatching again picks up the rest.
- **Hatch: review before writing** — an optional mode that walks each
  pending source one at a time and lets you keep or skip its proposed
  pages before they're written.
- **Custom skills must be approved once** — a skill you add under
  `.henhouse/skills/` runs with your privileges, so Settings → Skills now
  asks you to approve it (showing what it declares) before Peck can use it.
- Leaner package — dropped the non-English Electron locales (~12 MB).
- **Faster unzip and launch** — the app is now packed into a single
  `app.asar` instead of ~15k loose files; the retrieval layer (`scripts/`)
  and the native SQLite addon stay unpacked so Hatch/Peck/Groom still work.

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
