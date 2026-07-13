#!/usr/bin/env node
// Drive the Logseq web app (dev server on :3001) headlessly.
//
// Usage (from repo root, with `pnpm app-watch` already running):
//   node .claude/skills/run-logseq/driver.mjs shot [outfile.png]
//   node .claude/skills/run-logseq/driver.mjs smoke
//   node .claude/skills/run-logseq/driver.mjs eval '<js expression run in page>'
//
// Screenshots default to /tmp/logseq-shot.png (shot) and
// /tmp/logseq-smoke-{typed,reload}.png (smoke).

import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { chromium } from 'playwright';

const URL = process.env.LOGSEQ_URL || 'http://localhost:3001';

// The repo pins playwright 1.58.2, whose default chromium revision may not
// be in ~/.cache/ms-playwright. Fall back to any installed headless shell.
function findChromium() {
  const cache = join(homedir(), '.cache', 'ms-playwright');
  if (!existsSync(cache)) return undefined;
  const shells = readdirSync(cache)
    .filter(d => d.startsWith('chromium_headless_shell-'))
    .sort((a, b) => Number(b.split('-')[1]) - Number(a.split('-')[1]));
  for (const dir of shells) {
    const bin = join(cache, dir, 'chrome-headless-shell-linux64', 'chrome-headless-shell');
    if (existsSync(bin)) return bin;
  }
  return undefined;
}

async function launch() {
  let browser;
  try {
    browser = await chromium.launch();
  } catch {
    const executablePath = findChromium();
    if (!executablePath) {
      console.error('No chromium found. Run: npx playwright install chromium');
      process.exit(1);
    }
    browser = await chromium.launch({ executablePath });
  }
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  page.on('pageerror', err => console.log('[pageerror]', String(err).slice(0, 300)));
  return { browser, page };
}

async function loadApp(page) {
  try {
    await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
  } catch (e) {
    console.error(`Cannot reach ${URL} — is \`pnpm app-watch\` running?`);
    throw e;
  }
  // First shadow-cljs compile can take minutes; the title renders when ready.
  await page.waitForSelector('.ls-page-title', { timeout: 180000 });
  await page.waitForTimeout(5000); // let the db-worker settle
}

// Enter edit mode on the last content block. The page title is itself an
// .ls-block, so "last" skips it. Clicking a locator misses (the empty block
// has no .block-content hit target); raw mouse coordinates work.
async function editLastBlock(page) {
  const box = await page.evaluate(() => {
    const blocks = document.querySelectorAll('.ls-block');
    const r = blocks[blocks.length - 1].getBoundingClientRect();
    return { x: r.x + 40, y: r.y + r.height / 2 };
  });
  await page.mouse.click(box.x, box.y);
  await page.waitForSelector('textarea', { timeout: 15000 });
  // If the block already has text, start a fresh block below it.
  const val = await page.locator('textarea').first().inputValue();
  if (val.trim()) {
    await page.keyboard.press('End');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
  }
}

function blockTexts(page) {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('.ls-block .block-content'))
      .map(b => b.innerText.trim()).filter(Boolean));
}

const [, , cmd = 'shot', arg] = process.argv;
const { browser, page } = await launch();

try {
  await loadApp(page);

  if (cmd === 'shot') {
    const out = arg || '/tmp/logseq-shot.png';
    await page.screenshot({ path: out });
    console.log('title:', await page.title());
    console.log('screenshot:', out);
  } else if (cmd === 'eval') {
    console.log(JSON.stringify(await page.evaluate(arg), null, 2));
  } else if (cmd === 'smoke') {
    const stamp = `smoke ${process.pid}`;
    await editLastBlock(page);
    await page.keyboard.type(`Hello from driver ${stamp}`, { delay: 30 });
    await page.keyboard.press('Enter');
    await page.waitForTimeout(500);
    await page.keyboard.type('Link check: [[Test Page]]', { delay: 30 });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(2500);
    await page.screenshot({ path: '/tmp/logseq-smoke-typed.png' });
    console.log('typed:', JSON.stringify(await blockTexts(page)));

    // Reload to prove writes hit the sqlite-wasm db, not just the DOM.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForSelector('.ls-page-title', { timeout: 90000 });
    await page.waitForTimeout(6000);
    const after = await blockTexts(page);
    await page.screenshot({ path: '/tmp/logseq-smoke-reload.png' });
    console.log('after reload:', JSON.stringify(after));
    const ok = after.some(t => t.includes(stamp));
    console.log(ok ? 'SMOKE PASS — text persisted across reload' : 'SMOKE FAIL — text lost on reload');
    process.exitCode = ok ? 0 : 1;
  } else {
    console.error(`unknown command: ${cmd} (expected shot|smoke|eval)`);
    process.exitCode = 2;
  }
} finally {
  await browser.close();
}
