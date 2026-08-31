# PSI Bridge for AI Agents

A JetBrains plugin that gives coding agents the parts of your IDE they cannot reproduce:
real PSI refactorings, resolved references, and diagnostics that are actually trustworthy —
instead of letting the agent rebuild all of that out of `grep`, `sed` and `tsc --noEmit`.

Built for **WebStorm** and TypeScript/JavaScript. Speaks **MCP**, so it works with Claude Code,
and with any other MCP client.

---

## Why this exists

JetBrains' official Claude Code plugin exposes exactly six MCP tools: `getDiagnostics`,
`openDiff`, `openFile`, `getOpenEditors`, `closeTab`, `reformatFile`. No PSI access, no
refactoring, no filesystem synchronisation. In practice it turns a very expensive IDE into a diff
viewer.

Three consequences, all of which you feel daily:

**1. The IDE never sees what the agent writes.** IntelliJ only re-reads the disk when its window
regains focus, and filesystem notifications are unreliable when the writes come from WSL. Your
editor keeps analysing a version of the file that no longer exists.

**2. The agent never sees what the IDE holds.** This one is silent and destructive. Without
`saveAllDocuments`, the agent reads a stale copy from disk of a file you have unsaved changes in —
and then overwrites it.

**3. IDE diagnostics are unusable, so the model runs `tsc` itself.** That is not stubbornness on
the model's part. `getDiagnostics` only reports files **currently open in the editor** and already
analysed. Ask it about a file the agent just modified but never opened, and it returns an empty
list. A silent false negative is worse than a missing tool: the model concludes the code compiles.

If you have ever watched an agent rewrite an import path by hand across nine files, or re-run a
90-second `tsc --noEmit` for the fifth time in one session, this plugin is for you.

---

## What it does

### The feedback loop

A `PostToolUse` hook injects the diagnostics of the edited file straight into the conversation
after every `Edit`/`Write`. The model does not have to ask for them — it sees the error appear the
same way you see the red squiggle. A `PreToolUse` hook flushes unsaved editor documents to disk
before any read.

This is deliberately **not** exposed as a tool the model may choose to call. If it were, the model
would forget half the time and you would be back to problem 1. Synchronisation has to be
structural.

### The tools

| Tool | What it replaces |
|---|---|
| `get_diagnostics` | `tsc --noEmit` — type errors (real `TS****` codes from tsserver), syntax errors, **plus** the IDE inspections tsc cannot see. Incremental, the service is already warm, and it analyses files that are **not open**. |
| `find_usages` | `grep` on a symbol name — resolved references, aliases and imports included, no homonyms from other modules, no hits in comments. |
| `rename_symbol` | search-and-replace — updates every reference and import, leaves unrelated text alone, stays undoable with a single Ctrl+Z. |
| `move_file` | `mv` followed by hand-fixing imports — recomputes every relative import path, in both directions. |
| `get_type_info` | guessing the shape of a value by reading the surrounding code. |
| `ide_refresh` / `ide_save_all` | nothing — this did not exist. |
| `ide_status` | knowing whether the index is ready before trusting any of the above. |

---

## Scope: what is TypeScript-specific, and what is not

Worth being precise, because it determines how far this generalises.

**Language-agnostic** — these ride on the generic IntelliJ PSI and would work on any language the
IDE supports: `find_usages`, `rename_symbol`, `move_file`, plus the syntax-and-inspections half of
`get_diagnostics`.

**TypeScript/JavaScript-specific** — `get_type_info` (via `JSTypeOwner`) and the tsserver source
inside `get_diagnostics`.

The plugin declares `<depends>JavaScript</depends>`, so it installs on IDEs that bundle the
JavaScript plugin — WebStorm, IntelliJ IDEA Ultimate, PhpStorm, PyCharm Professional. Dropping
that dependency and stubbing the two TS-specific pieces would make it work on IDEA Community,
PyCharm, GoLand and the rest. That is a plausible v2, not a rewrite.

---

## How it works

The MCP server lives **inside the IDE process**. That is the whole point: an external Node MCP
server would only reach files on disk, which is to say nothing more than `grep` already gives you.
The plugin grafts itself onto the IDE's built-in web server (port 63342), speaks JSON-RPC 2.0 over
unary HTTP, and carries no SDK dependency.

The server is **stateless** — no MCP session to establish, a lone `tools/call` is enough. This is
what lets a shell hook invoke a tool in a single POST instead of performing a full handshake on
every file edit.

Symbols are addressed by **position** (`file`, `line`, `column`) rather than by name: a name is
ambiguous the moment it appears twice, whereas the position comes straight from what the model
read moments earlier.

### Three things that only turned up at runtime

Documented here because they cost hours and are not in any documentation:

- **`runMainPasses` does not run the TypeScript pass.** It gives you syntax and inspections on a
  closed file, but no type errors — precisely what you wanted to stop running `tsc` for. The
  TypeScript service has to be queried separately.
- **tsserver needs all three of**: the request posted from the EDT, under an explicit read action
  (the EDT no longer grants one implicitly as of 2025.2), and the file open on the IDE side. On a
  closed file the request simply never answers, until an 18-second internal timeout. The plugin
  opens the file without focus and closes it afterwards.
- **Refactorings only touch in-memory documents.** The first working version cheerfully reported
  "6 references updated" while the disk was untouched. Every refactoring now ends with an explicit
  save.

### Output contract

Every `get_diagnostics` response opens with a machine-readable status line:

```
idebridge status=ok|incomplete errors=<n> total=<n> files=<analysed>/<requested>
```

`status=incomplete` means at least one source could not answer — the absence of diagnostics is
then **not** a clearance, and the response says so in full before listing anything. The hook keys
off this line rather than off prose.

This matters more than it looks. A tool that answers "no problems" when it checked nothing is
worse than no tool at all — it is the central flaw of the official integration, and the status
line is at the top rather than the bottom precisely because a hurried reader stops after the first
few words.

---

## Requirements

- WebStorm 2025.2+ (or another IDE bundling the JavaScript plugin)
- An MCP client — Claude Code, or any other
- Node.js, for the setup and hook scripts (already present if you run Claude Code)

No system JDK needed to build: `build.sh` falls back to the JBR shipped with a locally installed
JetBrains IDE, which is a full JDK 21. Set `JAVA_HOME` to override.

## Install

Not on the JetBrains Marketplace — grab it from GitHub.

**From a release (recommended)**

1. Download `idebridge-<version>.zip` from the
   [Releases page](https://github.com/ebataille/PSI-Bridge-for-AI-Agents/releases).
2. In the IDE: **Settings > Plugins > ⚙ (gear icon) > Install Plugin from Disk…**
3. Pick the zip — do **not** unzip it first — and restart the IDE.

You will know it worked when a "PSI Bridge is running" notification appears on project open, and
`.claude/ide-bridge.json` shows up in the project.

**From source**

```bash
git clone https://github.com/ebataille/PSI-Bridge-for-AI-Agents.git
cd PSI-Bridge-for-AI-Agents
./build.sh buildPlugin
# build/distributions/idebridge-0.1.0.zip
```

Then install that zip the same way. `build.sh` resolves a JDK 21 for you: it honours `JAVA_HOME`
when set, and otherwise borrows the JBR shipped with a locally installed JetBrains IDE — a
complete JDK — so no separate install is needed just to build.

Releases are built by CI: pushing a `v*` tag runs `.github/workflows/release.yml`, which builds
the plugin and attaches the zip to the release.

**Updating**: install the new zip over the old one and restart. Settings and the per-project
token are preserved, so your MCP registration keeps working.

For development, `./build.sh runIde` starts a sandboxed instance opened on `testdata/`, a small
TypeScript project used to exercise the refactorings and diagnostics (it contains a deliberate
type error).

## Configure your MCP client

On project open, the plugin writes `<project>/.claude/ide-bridge.json` (URL, port, token). From
the project root:

```bash
node /path/to/PSI-Bridge-for-AI-Agents/hooks/setup-claude.mjs
```

The script probes the published addresses, registers the MCP server for that project, and
installs the hooks **globally** in `~/.claude/settings.json` (backed up to `.bak`). The hooks lie
dormant in projects without a descriptor, so one setup covers the whole machine; the MCP server
is per-project, since the token is.

Flags: `--project-hooks` (hooks in the current project instead), `--no-hooks` (MCP only).

Verify with `/mcp` — you should see **8 tools** under `idebridge`.

> **Never name this server `ide`.** Claude Code reserves that name for its own native IDE
> integration. A server registered under it simply vanishes behind that one: `/mcp` then shows a
> lone `mcp__ide__getDiagnostics` and you conclude the bridge is broken.

### If your agent runs in WSL

The IDE's built-in server binds to loopback only, and from WSL2 `127.0.0.1` is the VM, not the
Windows host. The plugin therefore also publishes the machine's IPv4 addresses, and the setup
script tries each. If none answer:

- **mirrored networking** (recommended, Windows 11): `networkingMode=mirrored` in
  `%USERPROFILE%\.wslconfig`, then `wsl --shutdown`; or
- **external connections**: Settings > Build, Execution, Deployment > Debugger >
  *Can accept external connections*, plus a firewall rule for the port.

Paths are translated both ways (`/mnt/d/x` ↔ `D:\x`), and results come back as project-relative
paths, which sidesteps the question most of the time.

---

## Layout

```
server/     BridgeHttpHandler   grafted onto the IDE's built-in web server
            McpServer           JSON-RPC 2.0, unary HTTP transport, no SDK
core/       BridgeService       token -> project registry; the token is the secret
            Symbols             resolve a symbol from (file, line, column)
            PathMapper          WSL <-> Windows translation
tools/      one file per tool
startup/    BridgeStartup       publishes .claude/ide-bridge.json on open
hooks/      ide-sync.mjs        both hooks; setup-claude.mjs installs everything
```

## Known limitations

- Refactorings go through `RefactoringFactory` applying usages directly, which bypasses the
  preview window **and** the conflicts dialog. Conflicts are therefore not surfaced; `dry_run` on
  `rename_symbol` lets you inspect the blast radius first.
- `move_file` uses `MoveFilesOrDirectoriesProcessor.run()`, which *can* open a dialog on conflict.
- `get_diagnostics` briefly opens each analysed file in the editor (without focus) to wake
  tsserver, then closes it. On a large batch you will see it happen.
- `get_diagnostics` is capped at 60 files per call.
- `HighlightingSessionImpl`, `runMainPasses` and the TypeScript service are internal platform
  APIs with no compatibility guarantee — recheck on every major IDE upgrade. Each call is
  isolated, so a signature change degrades one source rather than breaking the tool.
- One IDE per project: the descriptor is rewritten on each start.
- The descriptor holds a secret token. Keep `.claude/ide-bridge.json` out of version control
  (it is only exploitable from the local machine, but there is no reason to publish it).

## Roadmap

`apply_quick_fix`, `change_signature` with call-site propagation, `safe_delete`, `call_hierarchy`,
test execution with structured runner results, and gutter markers on agent-touched lines.

---

## Disclaimer

Not affiliated with, endorsed by, or supported by Anthropic or JetBrains. "Claude" is a trademark
of Anthropic; it appears here only to describe what this plugin interoperates with.

## License

MIT — see [LICENSE](LICENSE).
