---
name: idebridge
description: Use when working in a project where the idebridge MCP server is connected (a JetBrains IDE is open on it), to decide when the IDE tools beat grep, sed, tsc and per-file edits - and, just as importantly, when they do not. Covers get_diagnostics, get_outline, find_usages, find_implementations, find_callers, get_type_info, apply_quick_fix, structural_replace, rename_symbol, move_file, safe_delete, optimize_imports, format_code.
---

# IDE tools — MCP `idebridge`

When `idebridge` is connected, **its tools come before their shell or generic equivalents**. They
run on the IDE index and the already-warm language service: they see resolved references and real
types, not textual matches.

| Need                                | Use                                    | Instead of                                  |
| ----------------------------------- | -------------------------------------- | ------------------------------------------- |
| Understand a file you have not read | `get_outline`, then read the lines it returns | reading the whole file                |
| Type errors, inspections            | `get_diagnostics`                      | `tsc --noEmit`, `eslint`                    |
| Fix an error the IDE already flags  | `apply_quick_fix` (batch several)      | editing each error by hand                  |
| References to a symbol              | `find_usages`                          | `grep` on the name                          |
| Concrete implementations            | `find_implementations`                 | `find_usages` on an interface               |
| Who calls this, transitively        | `find_callers`                         | chaining `find_usages` level by level       |
| Signature, inferred type            | `get_type_info`                        | reading the code and deducing               |
| Rename a symbol                     | `rename_symbol`                        | `sed`, or one `Edit` per file               |
| Reshape an expression project-wide  | `structural_replace` (`dry_run` first) | `grep` followed by a series of edits        |
| Move or rename a file               | `move_file`                            | `mv` / `git mv`, which break the imports    |
| Remove dead code                    | `safe_delete`                          | deleting the lines and hoping               |
| Tidy up before committing           | `optimize_imports` + `format_code` with `scope: changed` | doing it by hand          |

The two rows that change the most are the first and the last: reading an outline before a file is
where the token budget is won, and running the style tools on `scope: changed` is what keeps the
diff reviewable.

## Limits — do not over-apply

This half matters more than the table. A rule that fires everywhere produces `find_usages` calls
looking for a config key.

- `grep` is still the right tool for what is **not a symbol**: string literals, config keys,
  `.json` / `.md` / `.yml`, TODOs, exploratory search.
- `sed` / `mv` stay legitimate **outside source code** (generated files, fixtures, scripts) and for
  bulk substitutions that are not a symbol rename.
- `structural_replace` is **not** a rename. It rewrites the matched expressions and leaves the
  declaration and the imports alone, so using it on a symbol produces a project that no longer
  compiles. Use `rename_symbol`.
- The `PostToolUse` hook already reports diagnostics after `Edit` / `Write`: do not re-run
  `get_diagnostics` on the same file without a reason. Do run it after `rename_symbol`,
  `move_file`, `apply_quick_fix` or `structural_replace`, which the hook does not cover.
- Every write tool edits in-memory documents first and then saves. When one reports files written,
  **re-read them** before editing them again — your copy is stale.
- Unsure whether the tools are usable (IDE closed, indexing in progress) → `ide_status` before
  concluding that one is broken.
- If `idebridge` is not connected, fall back to the shell equivalents without asking.

## Reading the `get_diagnostics` status line

The response opens with a machine-readable header:

```
idebridge status=ok errors=1 total=3 files=1/1
```

`status=incomplete` means at least one source failed — the header names which. **The absence of
diagnostics is then not a clearance**: verify with the project's own `tsc` and linter rather than
concluding the file is clean. This happens for real, most often after an IDE upgrade changes an
internal API out from under the plugin.

Diagnostics carry the quick fixes the IDE offers, indented underneath:

```
src/order/service.ts:7:9   warning   Unused constant total
                           fix: Remove unused constant 'total'
```

Those `fix:` lines are what `apply_quick_fix` takes, at the `file` and `line` of their diagnostic.
Send several in one call — one round trip for N fixes is the entire point of the tool.
