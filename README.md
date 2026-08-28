<h1 align="center">Kip</h1>

<p align="center">A pecking-first knowledge base — a fork of
<a href="https://github.com/logseq/logseq">Logseq</a> with an LLM retrieval layer bolted on.</p>

---

Kip is the [Logseq](https://github.com/logseq/logseq) editor (the Markdown
notes, journals, whiteboards, block references — all unchanged) plus a layer
that turns documents you drop in into an LLM-maintained wiki you can ask
questions of:

- **Hatch** — ingest sources into cross-linked `entity` / `concept` / `source` pages
- **Peck** — ask your nest, or tell it a fact / an upcoming meeting; a bounded
  skill loop (web search, spreadsheets, Word/PowerPoint, reminders) runs mid-answer
- **Groom** — read-only health checks over the generated wiki
- **Reminders** — natural-language, with a meeting-prep brief pulled from your notes

The retrieval layer itself lives in a separate repo:
**[JWE24-code/kip](https://github.com/JWE24-code/kip)** (`scripts/`). This repo
is the desktop app that bundles it.

## Install

Grab a build from [**Releases**](https://github.com/JWE24-code/kip-app/releases)
— `Kip-<version>-windows-x64.zip` or `Kip-<version>-linux-x64.tar.gz`. Extract
and run `Kip.exe` / `kip`.

- **Windows**: SmartScreen will warn (the binary is unsigned) — *More info →
  Run anyway*.
- **Linux / Wayland**: run with `--ozone-platform-hint=auto`; if the sandbox
  complains, `chmod 4755 chrome-sandbox` (as root) or add `--no-sandbox`. See
  `packaging/linux/`.
- No auto-updater — re-download to update.

You'll be asked to open a folder as your graph. Peck/Hatch/Groom/Reminders need
an LLM provider configured in **Settings → LLM** (an API key, or a local Ollama).

## Build from source

Needs this repo **and** the `kip` repo checked out as siblings
(`<dir>/app` + `<dir>/scripts`). See `docs/BUILD.md`. `packaging/linux/build.sh`
and `packaging/windows/build.ps1` do the full assembly; `.github/workflows/build.yml`
runs them in CI.

## License & attribution

Kip is a fork of **Logseq**, © Logseq, licensed under the **GNU AGPL-3.0** —
see [`LICENSE.md`](LICENSE.md) and [`NOTICE`](NOTICE). Kip is **not affiliated
with or endorsed by Logseq**. It keeps the same AGPL-3.0 license.

Vendored: tldraw v1 (`tldraw/`, MIT/Apache-2.0). Other dependencies retain
their own licenses.
