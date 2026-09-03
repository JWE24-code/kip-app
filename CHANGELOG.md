# Changelog

All notable changes to the Kip desktop app. The retrieval layer has its own
changelog at [JWE24-code/kip](https://github.com/JWE24-code/kip/blob/main/CHANGELOG.md).

## [Unreleased]

## [0.4.5] — 2026-09-03

- **Keep a Peck answer in your nest** — a settled answer that came from your
  notes now has a "⬇ File into the nest" control: it becomes a `concept` page
  tagged `from-peck`, so Peck can find it next time you ask. Hidden for
  web-backed answers and answers with no source pages. Every question you ask
  in the panel is also recorded once in the Coop activity view, the way the
  CLI already did (kip-app#112).
- **Peck flags its own shaky sources** — if an answer leans on a page your
  last groom flagged (orphaned, contradicted, a near-duplicate, drifted from
  disk), a short "⚠" note now appears under the answer naming the problem.
  Nothing shows for a clean answer or a nest you've never groomed
  (kip-app#116).
- **Peck won't quietly pick a side** — when the pages behind an answer
  disagree on a date or a value, Peck now says so and cites both instead of
  presenting one as settled fact. Groom's known contradictions for the pages
  in play are fed into the answer too (kip-app#116).
- **Hatch updates read what's already on the page** — when a source touches a
  page you already have, the update is now written as a delta against the
  existing content instead of a fresh restatement drafted blind, so pages stop
  accumulating near-duplicate paragraphs that a later groom has to flag
  (kip-app#114).

## [0.4.4] — 2026-09-02

- **Whiteboards now run on Excalidraw** — the tldraw-based whiteboard has been
  replaced with an Excalidraw canvas. Whiteboards are stored as plain
  `.excalidraw` files in `whiteboards/` (with a sibling `.svg` for previews and
  embeds), so they open in any Excalidraw editor too. Existing whiteboards keep
  working; the old tldraw toolchain and its bundled ~40k-line build are gone
  (kip-app#102).
- **App updates no longer break the desktop launcher** — if you launch Kip
  through a stable symlink (`~/Applications/Kip.AppImage` pointing at the
  versioned file, the way `Kip.desktop` does), every self-update used to leave
  that symlink aimed at the deleted old version, so the launcher silently did
  nothing until you relaunched by hand. The updater now repoints any symlink
  that referenced the old file the moment it installs the new one (kip-app#98).

## [0.4.3] — 2026-08-31

- **Peck searches the web when your notes come up short** — ask a question
  your nest doesn't cover and Kip no longer just shrugs: it runs a web search
  and answers from that, offering the results as a source you can hatch. Uses
  the built-in web search (DuckDuckGo, no key needed); it stays out of the way
  on a regenerate and when a skill already searched. Turn off web search in
  Settings → Skills to keep it strictly notes-only.
- **Peck follows the answer as it arrives** — the conversation now scrolls
  itself to the newest turn and rides the streaming text down, and lets go
  the moment you scroll up to read back.
- **Peck understands other languages** — a question typed without a "?" in
  German, French, Spanish, Dutch, Italian or Portuguese is now answered
  instead of being filed into your nest as a fact. Non-English page titles
  also keep their letters ("Größe" was becoming "gr-e").
- **New app icon** — Kip now wears its own egg mark (the same one on the
  website and the PWA) in the taskbar, the installer, the window and the
  favicon, instead of the leftover Logseq logo. `resources/icons/gen-kip-icons.mjs`
  regenerates the set from the mark.

## [0.4.2] — 2026-08-31

- **The answer streams in** — a Peck answer now appears word by word as the
  model writes it, instead of after a wait then all at once. Works on the
  Anthropic and OpenAI-compatible (OpenAI / DeepSeek / local / custom)
  providers; the managed Kip backend still returns the answer in one piece
  for now. Pairs with the retrieval-layer speedups (skip the key-term pass
  when the direct search is already confident; skip the skills tool-loop
  when the nest can answer on its own).
- **Drop in Word, Excel, PowerPoint and PDF files** — the Peck and Hatch
  views now take `.docx`, `.xlsx` / `.xls` / `.csv`, `.pptx` and `.pdf`
  alongside Markdown and text. Kip converts each to a compact Markdown
  version on the way in — Word keeps its headings and lists, a spreadsheet
  becomes a table per sheet, a deck becomes its slide text and notes, a PDF
  its text — so Hatch reads a few kilobytes of prose instead of choking on
  megabytes of binary, and the LLM bill for hatching a document drops
  accordingly. The original file is kept aside (outside your graph, so it
  doesn't sync or clutter `eggs/`). Old `.doc` / `.ppt` and OpenDocument
  files ask you to re-save as the modern format.

## [0.4.1] — 2026-08-31

- **Fixed: Dropbox sync failed on any file with an accent, em dash or other
  non-ASCII character in its name** — "Sync this graph" aborted partway
  through with *"Cannot convert argument to a ByteString"* and rolled itself
  back. The file's path went into a Dropbox request header unescaped, and
  HTTP headers can't carry those characters. They're now escaped as the
  Dropbox API expects, so a note titled *"Weekly review — 2026"* (or in any
  non-Latin script) syncs fine.

## [0.4.0] — 2026-08-31

- **Subscribe to a calendar** — the Reminders panel gains a *Calendar feeds*
  section: paste a live ICS or `webcal://` URL (Google / Outlook / Fastmail
  "secret address in iCal format") and Kip pulls your upcoming events in as
  reminders — each one notified ahead of time with a prep brief drawn from
  your nest, exactly like a reminder you'd type yourself. Feeds refresh
  every 20 minutes; the URL is stored like an API key, never in the graph.
- **Sync a graph through Dropbox** — Settings → General → Dropbox: connect
  an account (one-tap browser consent, OAuth with PKCE — no password, no
  secret in the app), then **Sync this graph**. Two-way, over the Dropbox
  API — no need for the Dropbox desktop app. Kip only ever touches a folder
  it creates for itself (`Apps/Kip-ai/`). On a conflict the newest change
  wins and Dropbox keeps the older version in its history. Your notes sync;
  the search cache and your API keys stay on the machine. Git's auto-commit
  stays on as a local undo history, no longer a sync mechanism.
  - *Advanced:* Kip connects through its own registered Dropbox app. If you'd
    rather use your own (for a separate rate-limit quota), drop your app key
    in Settings → General → Dropbox → advanced, or set `KIP_DROPBOX_APP_KEY`.
- **Peck follows a conversation** — ask a follow-up ("expand on that", "and
  their salary?", "what about the second one?") and Kip now knows what
  you're referring to. It carries the last few turns into the next
  question — for finding the right pages *and* for the answer — so you can
  keep pecking at a topic instead of re-explaining it each time. The buffer
  is small and lives only for the session.
- **Keep a web search** — when a Peck answer used web search, it now offers
  "↓ Save these web results as a source". One click writes the result list
  into `eggs/` as a Markdown doc; run Hatch and it becomes reference
  material in your nest instead of vanishing with the turn.

## [0.3.7] — 2026-08-30

- **The deep-groom schedule moved to Coop status** — it's no longer in
  Settings → Features. The Coop status panel now reads, top to bottom:
  the coop overview, **Groom** (run / deep-groom / last report), the weekly
  **Schedule**, then **Recent clucks** — so everything about grooming is in
  one place.
- **Regenerate a Peck answer** — every answer now has a `↻ Regenerate`
  button that re-asks the same question and adds a fresh answer below.
  Works on any provider. On the Kip (managed) backend a regenerate also
  quietly tells the router the first answer missed — nothing but that one
  bit, no text — and the re-run comes from a different model, so the new
  answer carries a one-tap **"better than the last one?"** strip. Answer
  it or ignore it; either way nothing but the verdict leaves the app.
- **Rate a Peck answer** — when you're on the Kip (managed) backend, a
  Peck answer now carries a small 👍 / 👎 control. It sends nothing but a
  thumbs-up or thumbs-down against that one answer — no text, no page
  names — so the managed router can learn which model / workload pairings
  actually land. It's invisible on every other provider.

## [0.3.6] — 2026-08-30

- **Fixed: "Run the deep groom on a schedule" never stuck** — ticking the
  box in Settings → Features did nothing: the day/time controls never
  appeared and the schedule was always saved as *off*. The setting crossed
  the process boundary with the wrong key shape, so the main process read
  every field as blank and wrote back "disabled". The scheduled deep groom
  has been non-functional since it shipped in 0.3.0; it works now.

## [0.3.5] — 2026-08-30

- **Reasoning models are first-class** — pick `deepseek-reasoner`,
  `deepseek-r1`, OpenAI's `o1` / `o3` / `o4-mini` or similar and Kip no
  longer wastes a round-trip on every call discovering they don't support
  forced-JSON mode: the common ones are known by name, and any other model
  that rejects the parameter is remembered for the rest of the session
  after the first time. They still think before answering, so they're
  slower — best kept for the managed backend, which routes only the
  workloads that benefit to one.
- **Docs: the managed connector and Add-a-connector, explained** — Settings
  → LLM now has user docs for the "Kip (managed)" provider, installing an
  `@kip-ai/*` connector from a `.tgz`, and why only that name is allowed
  (a connector runs in Kip's own process).

## [0.3.4] — 2026-08-29

- **Fixed: Peck crashed on a stale search index** — if the index still
  listed a nest page whose file had been deleted or moved (or, on OneDrive
  / iCloud, wasn't downloaded yet), *every* Peck turn — questions, "tell it
  something new", web-search-to-nest — died before it started. It now skips
  the missing page and carries on. If you hit this: the graph is best kept
  outside a cloud-sync folder, and a Hatch (or `rebuild-roost`) cleans the
  index.

## [0.3.3] — 2026-08-29

- **Managed connector: clearer connection errors** — a failed call to the
  Kip backend now says what to check (nothing listening, no response,
  can't resolve the host, wrong protocol) instead of a bare "fetch
  failed", and **Test connection** checks your key + reachability against
  a lightweight endpoint, reporting your plan and monthly token cap.

## [0.3.2] — 2026-08-29

- **Fixed: Windows in-app updates were rejected** — electron-updater
  insisted the downloaded installer's certificate chain to a trusted root,
  which our self-signed cert can't, so *every* Windows update failed with
  "not signed by the application owner". The check is now off (the download
  is still integrity-checked against the release's SHA-512); it comes back
  when there's a real certificate. **This build must be installed by hand
  once** — 0.3.0 / 0.3.1 shipped with the broken check and can't update
  themselves. Linux (AppImage) was never affected.

## [0.3.1] — 2026-08-29

- **The managed Kip connector** — one `kip_` key routes every Hatch/Peck/
  Groom call through the managed Kip backend, which picks the model per
  task, enforces your plan and meters usage — instead of setting up
  Anthropic/OpenAI/DeepSeek keys yourself. It's invite-only for now: pick
  "Have a Kip backend key?" under Settings → LLM, enter the key (and a
  Base URL for a self-hosted backend). It updates with the app.
- **Data-driven LLM settings** — the provider list and each provider's
  fields are now generated, not hardcoded. A new **Add a connector** row
  installs a connector package from a `.tgz` or a URL (`@kip-ai/*` only);
  installed ones can be removed.
- **Clearer quota / billing errors** — running out of included tokens or
  budget (your own provider's, or the managed backend's) now says so,
  instead of reading like a rate-limit.
- The first-run "no LLM provider" nudge now trusts the connector's own
  readiness check, so it stops nagging once a provider works via an
  environment variable, not only via the settings form.

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
