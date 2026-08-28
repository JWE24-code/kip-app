# Security Policy

## Reporting a vulnerability

**Do not open a public issue for security problems.**

Report privately through GitHub's private vulnerability reporting:

> **https://github.com/JWE24-code/kip-app/security/advisories/new**

You'll get an acknowledgement as soon as possible. If the issue is in the
retrieval layer, the same applies at
https://github.com/JWE24-code/kip

## Scope

Kip is pre-1.0 software maintained by one person in their spare time — expect
gaps, and only the latest release is supported. Things that are known and *not*
vulnerabilities:

- `<graph>/.henhouse/llm.json` stores your LLM API keys in plaintext on your
  own machine. That's by design; keep the folder out of any shared/synced
  location and out of version control.
- Skills are arbitrary Node scripts that run with your privileges — a
  user-added skill under `<graph>/.henhouse/skills/` is like running a shell
  script. Built-in skills are reviewed in this repo.
- Your notes and questions are sent to whichever LLM provider you configure.
  Use the `local` (Ollama) provider to keep everything on-device.

Kip is a fork of Logseq; vulnerabilities in unmodified upstream code should
also be reported to the Logseq project.
