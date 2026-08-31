#!/usr/bin/env node
/**
 * Configures Claude Code to talk to the plugin.
 *
 * Run it from the root of a project that is open in the IDE:
 *
 *   node <plugin-path>/hooks/setup-claude.mjs
 *
 * Three things:
 *   1. finds which of the addresses published by the IDE actually answers (WSL or not);
 *   2. registers the MCP server for THIS project (the token is per-project);
 *   3. installs the sync hooks once for ALL projects, in the user config: they turn themselves
 *      off wherever the plugin is not active.
 *
 * --project-hooks  install the hooks in the current project instead of the user config.
 * --no-hooks       only register the MCP server.
 */

import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { execFileSync, execSync } from 'node:child_process';

/**
 * Anything but "ide": Claude Code reserves that name for its own native IDE integration (the
 * official plugin, which only exposes getDiagnostics). Registering the bridge under that name
 * simply makes it disappear behind it.
 */
const SERVER_NAME = 'idebridge';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECT = process.cwd();
const HOME_BRIDGE = join(homedir(), '.claude', 'ide-bridge');
const ARGS = new Set(process.argv.slice(2));

function fail(message) {
  console.error(`\n  ${message}\n`);
  process.exit(1);
}

function readJson(path, fallback) {
  if (!existsSync(path)) {
    return fallback;
  }
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return fallback;
  }
}

const descriptorPath = join(PROJECT, '.claude', 'ide-bridge.json');
const descriptor = readJson(descriptorPath, null);
if (!descriptor) {
  fail(
    `No descriptor at ${descriptorPath}.\n  ` +
      `Open this project in the IDE with the plugin installed: it writes that file on startup.`,
  );
}

async function reachable(url) {
  const body = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} });
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body,
      signal: AbortSignal.timeout(5000),
    });
    if (!response.ok) {
      return false;
    }
    const payload = await response.json();
    return Array.isArray(payload?.result?.tools);
  } catch {
    return false;
  }
}

/**
 * On Windows, `claude` is a script (.cmd / .ps1): without going through the shell, Node resolves
 * it differently from the terminal and may target a different installation than the user's — npm
 * and WinGet happily coexist, with separate configs. Going through the shell reproduces the
 * user's own PATH resolution exactly.
 */
function runClaude(args, stdio) {
  if (process.platform === 'win32') {
    const quoted = args.map((a) => `"${a}"`).join(' ');
    return execSync(`claude ${quoted}`, { stdio });
  }
  return execFileSync('claude', args, { stdio });
}

function claudeVersion() {
  try {
    if (process.platform === 'win32') {
      return execSync('claude --version', { encoding: 'utf8' }).trim();
    }
    return execFileSync('claude', ['--version'], { encoding: 'utf8' }).trim();
  } catch {
    return 'not found on PATH';
  }
}

function installHooks(settingsPath, command) {
  const settings = readJson(settingsPath, {});
  settings.hooks = settings.hooks || {};

  const entries = [
    ['PreToolUse', 'Read|Grep|Glob', `${command} pre`, 20],
    ['PostToolUse', 'Edit|Write|NotebookEdit', `${command} post`, 30],
  ];

  for (const [event, matcher, cmd, timeout] of entries) {
    settings.hooks[event] = settings.hooks[event] || [];
    // Idempotent: replace our own entry rather than stacking a new one.
    settings.hooks[event] = settings.hooks[event].filter(
      (group) => !JSON.stringify(group).includes('ide-sync.mjs'),
    );
    settings.hooks[event].push({
      matcher,
      hooks: [{ type: 'command', command: cmd, timeout }],
    });
  }

  if (existsSync(settingsPath)) {
    copyFileSync(settingsPath, `${settingsPath}.bak`);
  }
  mkdirSync(dirname(settingsPath), { recursive: true });
  writeFileSync(settingsPath, `${JSON.stringify(settings, null, 2)}\n`);
}

async function main() {
  const urls = descriptor.urls?.length ? descriptor.urls : [descriptor.url];
  let url = null;
  for (const candidate of urls) {
    process.stdout.write(`  probing ${candidate} ... `);
    if (await reachable(candidate)) {
      console.log('OK');
      url = candidate;
      break;
    }
    console.log('unreachable');
  }

  if (!url) {
    fail(
      `None of the published addresses answered.\n  ` +
        `If your agent runs in WSL2, either enable mirrored networking (networkingMode=mirrored\n  ` +
        `in %USERPROFILE%\\.wslconfig, then wsl --shutdown), or allow external connections on the\n  ` +
        `built-in server (Settings > Build, Execution, Deployment > Debugger >\n  ` +
        `"Can accept external connections") and open port ${descriptor.port} in the firewall.`,
    );
  }

  console.log(`\n  claude CLI in use: ${claudeVersion()}`);

  try {
    runClaude(['mcp', 'remove', SERVER_NAME], 'ignore');
  } catch {
    // not registered yet: expected
  }
  try {
    runClaude(['mcp', 'add', '--transport', 'http', SERVER_NAME, url], 'inherit');
    console.log(`  MCP server "${SERVER_NAME}" registered for ${descriptor.projectName}.`);
  } catch (e) {
    fail(
      `The claude CLI could not register the server (${e.message}).\n  ` +
        `Do it by hand, from this directory:\n\n  ` +
        `claude mcp add --transport http ${SERVER_NAME} ${url}`,
    );
  }

  if (ARGS.has('--no-hooks')) {
    return;
  }

  let command;
  if (ARGS.has('--project-hooks')) {
    command = `node "${resolve(HERE, 'ide-sync.mjs')}"`;
    installHooks(join(PROJECT, '.claude', 'settings.local.json'), command);
    console.log(`  Hooks installed in ${join(PROJECT, '.claude', 'settings.local.json')}.`);
  } else {
    mkdirSync(HOME_BRIDGE, { recursive: true });
    const installed = join(HOME_BRIDGE, 'ide-sync.mjs');
    copyFileSync(join(HERE, 'ide-sync.mjs'), installed);
    command = `node "${installed}"`;
    installHooks(join(homedir(), '.claude', 'settings.json'), command);
    console.log(`  Hooks installed globally (~/.claude/settings.json, backed up as .bak).`);
    console.log(`  They stay inert in projects without a .claude/ide-bridge.json descriptor.`);
  }

  console.log(`\n  Done. Restart Claude Code in this project, then check with /mcp.\n`);
}

main();
