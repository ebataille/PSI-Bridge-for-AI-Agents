#!/usr/bin/env node
/**
 * Bridge between Claude Code hooks and the IDE plugin.
 *
 * Two roles, picked by the first argument:
 *
 *   pre   (PreToolUse on Read/Grep/Glob)  : flushes the IDE's unsaved documents to disk, so the
 *                                           agent never reads a stale version of what the user
 *                                           has on screen.
 *
 *   post  (PostToolUse on Edit/Write)     : re-syncs the VFS for the files just written, then
 *                                           feeds their diagnostics back into the conversation.
 *                                           This is the red squiggle, for a model: it sees the
 *                                           error without having to think of asking.
 *
 * A hook must never break the session: any failure here exits quietly with code 0.
 */

import { readFileSync, existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const MODE = process.argv[2] === 'pre' ? 'pre' : 'post';
const REQUEST_TIMEOUT_MS = 15000;
/** Past this, the report costs more than the error it reports. */
const MAX_CONTEXT_CHARS = 4000;

function quit(payload) {
  if (payload) {
    process.stdout.write(JSON.stringify(payload));
  }
  process.exit(0);
}

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

function loadDescriptor(cwd) {
  const root = process.env.CLAUDE_PROJECT_DIR || cwd || process.cwd();
  const path = join(root, '.claude', 'ide-bridge.json');
  if (!existsSync(path)) {
    return null;
  }
  try {
    const descriptor = JSON.parse(readFileSync(path, 'utf8'));
    descriptor.__cachePath = join(root, '.claude', 'ide-bridge.url');
    return descriptor;
  } catch {
    return null;
  }
}

/**
 * From WSL2 without mirrored networking, 127.0.0.1 is the VM rather than the Windows host, so the
 * plugin publishes several addresses and we keep the first one that answers.
 */
function candidateUrls(descriptor) {
  const urls = [];
  if (existsSync(descriptor.__cachePath)) {
    try {
      const cached = readFileSync(descriptor.__cachePath, 'utf8').trim();
      if (cached) {
        urls.push(cached);
      }
    } catch {
      // unreadable cache: fall back to the published list
    }
  }
  for (const url of descriptor.urls || [descriptor.url]) {
    if (url && !urls.includes(url)) {
      urls.push(url);
    }
  }
  return urls;
}

async function callTool(descriptor, name, args) {
  const body = JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/call',
    params: { name, arguments: args },
  });

  for (const url of candidateUrls(descriptor)) {
    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
      if (!response.ok) {
        continue;
      }
      const payload = await response.json();
      try {
        writeFileSync(descriptor.__cachePath, url);
      } catch {
        // cache not written: harmless, detection will simply run again
      }
      const text = payload?.result?.content?.[0]?.text;
      return typeof text === 'string' ? text : null;
    } catch {
      // address unreachable: try the next one
    }
  }
  return null;
}

/** Claude Code has varied this field name across versions and tools. */
function editedPath(input) {
  if (!input || typeof input !== 'object') {
    return null;
  }
  for (const key of ['file_path', 'path', 'notebook_path', 'filePath']) {
    if (typeof input[key] === 'string' && input[key].length > 0) {
      return input[key];
    }
  }
  return null;
}

async function main() {
  const raw = await readStdin();
  let event;
  try {
    event = JSON.parse(raw);
  } catch {
    quit(null);
  }

  const descriptor = loadDescriptor(event.cwd);
  if (!descriptor) {
    quit(null);
  }

  if (MODE === 'pre') {
    await callTool(descriptor, 'ide_save_all', {});
    quit(null);
  }

  const path = editedPath(event.tool_input);
  if (!path) {
    quit(null);
  }

  await callTool(descriptor, 'ide_refresh', { paths: [path] });
  const diagnostics = await callTool(descriptor, 'get_diagnostics', {
    paths: [path],
    min_severity: 'warning',
    max_results: 30,
  });

  if (!diagnostics) {
    quit(null);
  }

  // The plugin opens its response with a machine-readable status line:
  //   idebridge status=ok|incomplete errors=N total=N files=N/N
  // We key off that rather than off prose: a degraded result must surface even when it carries no
  // diagnostic at all, otherwise a broken analysis reads as a success.
  const status = /^idebridge\s+status=(\S+)\s+errors=(\d+)\s+total=(\d+)/m.exec(diagnostics);
  const degraded = status ? status[1] !== 'ok' : false;
  const total = status ? Number(status[3]) : 1;

  if (total === 0 && !degraded) {
    quit(null);
  }

  quit({
    hookSpecificOutput: {
      hookEventName: 'PostToolUse',
      additionalContext:
        `IDE diagnostics for ${path} after this edit ` +
        `(TypeScript errors and inspections, already up to date):\n\n` +
        diagnostics.slice(0, MAX_CONTEXT_CHARS),
    },
  });
}

main().catch(() => process.exit(0));
