## Don't browse your notes. Peck them.
- **Kip** is the full [Logseq](https://github.com/logseq/og) editor — Markdown notes, journals, whiteboards, block references — plus a layer that turns documents you drop in into a **cross-linked wiki you can ask questions of**. It opens straight into a chat prompt; the editor is a mode you toggle into.
- This is a demo graph. Nothing here is saved until you open a real folder — do that from the graph menu at the top left when you're ready.
#+BEGIN_TIP
Press `Ctrl/⌘ + 1` to switch between **Peck** (the chat) and **Documents** (this editor).
Type `Enter` for a new block, `Shift+Enter` for a new line, `/` for commands.
#+END_TIP
- ## The farm, in three verbs
    - **Hatch** — drop a `.md` or `.txt` file into `eggs/` inside your graph folder and Kip turns it into linked `entity` / `concept` / `source` pages: [[The Nest]].
    - **Peck** — ask the nest a question and get an answer that cites the `[[pages]]` it came from. Or tell Kip a fact, or an upcoming meeting — it files the fact and sets a reminder.
    - **Groom** — read-only health checks that keep the nest trustworthy as it grows: broken links, orphans, near-duplicates, contradictions.
- ## The coop
    - `eggs/` — the source documents you add
    - `nest/` — the wiki Kip builds from them
    - `clucks/` — Kip's activity log
    - `.henhouse/` — your LLM provider and skills config
    - `.roost/` — the search index (disposable — rebuild any time)
- ## First five minutes
    - LATER Open a folder as your graph
    - LATER Set an LLM provider in *Settings → LLM* (Anthropic / OpenAI / DeepSeek, or Local via Ollama)
    - LATER Drop a document into `<graph>/eggs/`
    - LATER Hatch it — header *"…"* menu → *Hatch sources → Start*
    - LATER Peck — type a question in the prompt
- More at the [Kip website](https://jwe24-code.github.io/kip-site/) · [Getting-started guide](https://github.com/JWE24-code/kip/blob/main/docs/GETTING-STARTED.md) · [Releases](https://github.com/JWE24-code/kip-app/releases)
