# PokeClaw AI Index

This is the repo map for coding agents. Keep canonical information in existing files; do not create new root docs when one of these files already owns the topic.

## Canonical Root Docs

| File | Owns |
|------|------|
| `README.md` | Public product direction, roadmap, platform constraints, benchmark claims, changelog |
| `CLAUDE.md` | Agent/project working rules |
| `QA_CHECKLIST.md` | QA methodology, release gate, test cases, debug changelog |
| `RELEASING.md` | Release signing, tag workflow, stable APK publishing |
| `BACKLOG.md` | Prioritized bugs, features, QA gaps, ideas |
| `ARCHITECTURE_RECONSTRUCTION.md` | Historical architecture reconstruction plan and refactor guardrails |
| `CLA.md` | Contributor license agreement |
| `AI_INDEX.md` | This repo map |

## Directory Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/io/agents/pokeclaw/` | Android app source |
| `app/src/main/java/io/agents/pokeclaw/agent/AgentConfig.kt` | Cloud base system prompt restored to original text at user request; runtime round cap remains removed |
| `app/src/main/java/io/agents/pokeclaw/agent/DefaultAgentService.kt` | Agent loop and prompt composition; email-specific guard and its tests removed 2026-11-04, other existing guards retained |
| `app/src/main/assets/playbooks/` | Built-in playbooks used by the agent harness |
| `app/src/main/res/drawable/ic_bhavishya.xml` | Original B. vector mark for adaptive/themed icons and notification status |
| `app/build.gradle.kts` | Bhavishya APK naming and generated `assets/legal/` copies of root LICENSE/NOTICE; original package/signing retained |
| `app/src/test/` | JVM/unit regression tests |
| `scripts/` | QA and automation scripts |
| `.github/workflows/` | CI, CLA check, signed release workflow |
| `.vscode/launch.json` | Optional Java/JDWP attach to a locally forwarded Android debug process |
| `app/src/main/java/io/agents/pokeclaw/debug/AccessibilityDiagnostics.kt` | Explicit debug-only root/API and bounded node-metadata comparison; no polling or actions |
| `docs/` | GitHub Pages site and public site assets |
| `docs/screenshots/` | Screenshot assets used by the landing page |
| `demo/` | Legacy demo GIF assets |
| `Screenshots/` | Legacy screenshot assets |
| `prototype/` | Historical UI prototypes |
| `mockup/` | Early interactive mockups |
| `signatures/` | CLA signature state |

## Direction Rules

- PokeClaw is a generic Android mobile-agent harness with a product shell on top.
- Prefer fixing deterministic harness/runtime/device problems before tuning one stochastic task.
- Keep prompts, tools, skills, and playbooks generic.
- Treat Cloud/Local exploratory task success as a repeated-trial metric, not a single-run truth.
- For GPU/local runtime reports, collect logs and keep CPU fallback truthful before changing backend selection.
