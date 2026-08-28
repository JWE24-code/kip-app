<h1 align="center">Kip</h1>

<p align="center">A pecking-first knowledge base — a fork of
<a href="https://github.com/logseq/logseq">Logseq</a> with an LLM retrieval layer.</p>

<p align="center"><em>v0.1 — early, rough, and looking for feedback.</em></p>

---

Kip is the Logseq editor (Markdown notes, journals, whiteboards, block
references — unchanged) plus a layer that turns documents you drop in into a
cross-linked wiki you can ask questions of. It opens straight into a chat
prompt; the editor is a mode you toggle into.

- **Hatch** — a document you drop into `eggs/` becomes a set of linked
  `entity` / `concept` / `source` pages ("the nest")
- **Peck** — ask the nest a question (answers cite `[[pages]]`), or tell it a
  fact / an upcoming meeting. A bounded skill loop can run mid-answer: web
  search, read a spreadsheet, build a Word doc or a deck
- **Groom** — read-only health checks over the generated wiki
- **Reminders** — "I have a meeting with Acme on Friday at 15h" → an OS
  notification beforehand, with a prep brief pulled from your notes

The retrieval layer lives in a separate repo,
**[JWE24-code/kip](https://github.com/JWE24-code/kip)** — this repo is the
desktop app that bundles it.

## Install

Download from [**Releases**](https://github.com/JWE24-code/kip-app/releases):

| Platform | File | Run |
|---|---|---|
| Windows 10/11 (x64) | `Kip-<v>-windows-x64.zip` | extract, run `Kip.exe` |
| Linux (x64) | `Kip-<v>-linux-x64.tar.gz` | `tar -xzf …`, run `./kip` |

- **Windows** — the binary is **unsigned**, so SmartScreen shows *"Windows
  protected your PC"*. Click **More info → Run anyway**.
- **Linux / Wayland (Hyprland etc.)** — run
  `./kip --ozone-platform-hint=auto`. If it complains about the sandbox,
  `sudo chmod 4755 chrome-sandbox` or add `--no-sandbox`. `packaging/linux/`
  has an `install.sh` (app-menu entry + `kip` on PATH) and a launcher with the
  flags baked in.
- **No auto-updater** — to update, download the new release and replace the
  folder.

## Quickstart

1. **Open a folder as your graph.** Everything Kip creates lives inside it
   (`eggs/`, `nest/`, `clucks/`, …).
2. **Set an LLM provider** — *Settings → LLM*. Anthropic / OpenAI / DeepSeek
   (API key), or **Local** (Ollama, on-device). Hit *Test connection*.
3. **Drop a document** — put a `.md` or `.txt` file in `<graph>/eggs/`.
4. **Hatch it** — Header *"…" menu → Hatch sources → Start*. It becomes pages
   under *The Nest*.
5. **Peck** — type a question in the prompt: *"what did we decide about the
   timeline?"* Answers link back to the pages they came from.
6. **Toggle the editor** with `Ctrl/⌘+1` when you want to read or write notes
   directly.

Full walkthrough: **[docs/GETTING-STARTED.md](https://github.com/JWE24-code/kip/blob/main/docs/GETTING-STARTED.md)**.

## Privacy

Your notes and questions are sent to whichever LLM provider you configure —
Anthropic, OpenAI, and DeepSeek are hosted services that receive that content.
Choose the **Local** provider (Ollama) to keep everything on your machine.

`<graph>/.henhouse/llm.json` stores your API keys **in plaintext** on your
disk — keep that folder out of shared/synced locations and out of version
control.

## Known limitations (v0.1)

- Windows + Linux x64 only — no macOS build, no mobile.
- Unsigned binaries; no auto-update.
- Folder-zip, not an installer.
- The retrieval layer needs an LLM provider configured — without one, Hatch
  and Peck don't work.
- Skills run as Node subprocesses with your privileges (no sandbox). A skill
  you add yourself is like running a shell script.
- It's a personal project at v0.1 — expect bugs and breaking changes.

## Feedback

Bugs and ideas → [**Issues**](https://github.com/JWE24-code/kip-app/issues).
Questions and general feedback →
[**Discussions**](https://github.com/JWE24-code/kip-app/discussions).
Security → see [`SECURITY.md`](SECURITY.md).
Changes → [CHANGELOG](https://github.com/JWE24-code/kip/blob/main/CHANGELOG.md).

## Build from source

Needs this repo **and** the `kip` repo as siblings (`<dir>/app` +
`<dir>/scripts`). `packaging/linux/build.sh` / `packaging/windows/build.ps1`
do the full assembly; `.github/workflows/build.yml` runs them in CI. See
[`docs/BUILD.md`](docs/BUILD.md).

## License & attribution

Kip is a fork of **Logseq**, © Logseq, under the **GNU AGPL-3.0** — see
[`LICENSE.md`](LICENSE.md) and [`NOTICE`](NOTICE). Kip is **not affiliated with
or endorsed by Logseq** and is distributed under the same license.

Vendored: tldraw v1 (`tldraw/`, MIT / Apache-2.0). Other dependencies keep
their own licenses.
