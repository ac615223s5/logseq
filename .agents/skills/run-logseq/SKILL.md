---
name: run-logseq
description: Build, run, and drive the Logseq web app for testing. Use when asked to run/start Logseq, take a screenshot of its UI, verify a change in the running app, or interact with the editor programmatically.
---

Logseq is a ClojureScript (shadow-cljs) + React notes app. The verified
path is the **web build**: start the watch build, then drive the app
headlessly with `node .agents/skills/run-logseq/driver.mjs` (a small
Playwright CLI committed next to this file). All paths are relative to
the repo root.

## Prerequisites

No apt packages were needed. Verified toolchain on this machine:
Node 24 (repo requires >=22.20), pnpm 10 (via `packageManager` field),
OpenJDK 21, Clojure CLI 1.12. `babashka` (`bb`) is NOT required for the
web path (it is missing here; only the Electron path in `bb.edn` needs it).

## Setup

```bash
pnpm install
```

## Run the dev server

```bash
pnpm app-watch > /tmp/logseq-watch.log 2>&1 &
```

This runs gulp asset watch + shadow-cljs `watch app db-worker` + webpack.
The dev server listens on **http://localhost:3001** (nREPL on 8701).
The **first cold compile takes several minutes**; a warm restart is
~20s. Ready when the log shows both:

```bash
grep 'Build completed' /tmp/logseq-watch.log
# [:db-worker] Build completed. (...)
# [:app] Build completed. (...)
```

Port 3001 opens *before* compilation finishes — wait for the log lines,
not the port. To stop: kill the backgrounded `pnpm app-watch`.

## Run (agent path)

Drive the running app with the driver (Playwright resolves from the
repo's own `node_modules`):

```bash
node .agents/skills/run-logseq/driver.mjs shot /tmp/logseq-shot.png   # load app, screenshot
node .agents/skills/run-logseq/driver.mjs smoke                       # type blocks, verify persistence
node .agents/skills/run-logseq/driver.mjs eval 'document.title'       # run JS in the page
```

| command | what it does |
|---|---|
| `shot [out.png]` | Loads the app, waits for the page title to render, screenshots. Default `/tmp/logseq-shot.png`. |
| `smoke` | Clicks into the journal, types two blocks (incl. a `[[Test Page]]` link), reloads, and asserts the text survived — proves editor + sqlite-wasm persistence. Exit 0 on pass. Screenshots at `/tmp/logseq-smoke-{typed,reload}.png`. |
| `eval '<js>'` | Evaluates a JS expression in the page and prints the JSON result. |

The app auto-loads a demo graph on first visit — no onboarding to click
through. The driver waits up to 3 min for `.ls-page-title`, so it
tolerates a still-compiling server.

## Run (human path)

Same `pnpm app-watch`, then open http://localhost:3001 in a browser.

## Test

Unit tests per `AGENTS.md`: `bb dev:lint-and-test` — requires babashka,
which is not installed here; not verified by this skill.

## Gotchas

- **The page title is itself an `.ls-block`** — selecting
  `.ls-block:first` gets "Jul 13th, 2026", not a content block. The
  driver uses the *last* `.ls-block`.
- **Locator clicks miss the empty journal block** (no `.block-content`
  hit target when empty). Raw `page.mouse.click()` at the block's
  bounding-box coordinates works — that's what `editLastBlock` in the
  driver does.
- **The block editor is a `<textarea>`**, not contenteditable — wait
  for `textarea` after clicking, then use `keyboard.type`.
- **Each driver run gets a fresh browser profile**, so the demo graph
  resets between invocations — runs are idempotent, but you cannot
  inspect state written by a previous driver run. Persistence is only
  checkable within one run (the `smoke` command reloads in-context).
- **Dev build logs `TypeError: b_XXXX.getBoundingClientRect is not a
  function` page errors while typing.** Editing and persistence still
  work; treat as noise unless you're working on the editor.
- **webpack prints two "Critical dependency" warnings** for
  `@sqlite.org/sqlite-wasm` — normal, not a failure.

## Troubleshooting

- **`browserType.launch: Executable doesn't exist at
  .../chromium_headless_shell-1208/...`** — the repo pins Playwright
  1.58.2 but `~/.cache/ms-playwright` holds other revisions. The driver
  auto-falls back to any cached `chromium_headless_shell-*`; if none
  exists, run `npx playwright install chromium`.
- **Driver says "Cannot reach http://localhost:3001"** — `pnpm
  app-watch` isn't running. Blank/erroring page instead — it's running
  but still compiling; wait for both `Build completed` lines in
  `/tmp/logseq-watch.log`.
