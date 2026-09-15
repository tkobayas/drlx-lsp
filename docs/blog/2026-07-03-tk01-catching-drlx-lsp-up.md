---
layout: post
title: "Catching drlx-lsp up to mvel-lsp"
date: 2026-07-03
type: phase-update
entry_type: note
subtype: diary
projects: [drlx-lsp]
tags: [dependencies, testing, antlr4-c3]
---

I'd been meaning to bring drlx-lsp's dependencies in line with mvel-lsp for a while. The two projects share the same architecture — ANTLR4-based parser, LSP4J server, VSCode extension — but drlx-lsp had fallen behind on versions. Some gaps were significant: `vscode-languageclient` was still at 5.x while mvel-lsp had moved to 9.x.

## The dependency alignment

I brought Claude in to help with the systematic upgrade. We filed a GitHub issue listing every version gap, then worked through it:

**Maven side:** lsp4j 0.19.0 → 0.24.0, logback 1.5.16 → 1.5.25, xtext 2.25.0 → 2.41.0. Also fixed two hardcoded versions in `drlx-completion/pom.xml` — lsp4j was pinned at 0.12.0 instead of using the parent property, and logback at 1.4.14 instead of `${version.ch.qos.logback}`.

**Client side:** the `vscode-languageclient` 5→9 jump required changing the import path to `vscode-languageclient/node` and fixing `start()` — it no longer returns a Disposable, so you push the client itself to subscriptions. The `glob` 7→11 upgrade broke the test runner (`index.ts`) since the callback API was removed in favour of promises.

One thing I caught on the first pass: Claude had used `^` ranges for devDependencies, which I'd intentionally pinned to exact versions in commit `eba14ed` to harden against supply chain attacks. We re-did the client deps with exact pinned versions — `"typescript": "5.9.3"` not `"^5.3.0"` — and kept the `overrides` block for transitive vulnerability fixes.

ESLint 9 required migrating from `.eslintrc.json` to `eslint.config.mjs` (flat config). The mvel-lsp version was a clean reference — same rules, new format.

## Completion tests for the growing grammar

With dependencies aligned, we moved to #3: adding completion tests for all the new grammar constructs that drlx-parser had added since the last catch-up (~30+ commits). These include `match` blocks, `if/else` branches, `not`/`exists`/`and`/`or` CE groups, `window` declarations, `groupBy`, `accumulate`, passive patterns, and custom constraints.

Writing the tests surfaced a design limitation: inside rule body, `antlr4-c3` correctly discovers keyword tokens (`not`, `exists`, `if`, `match`, etc.) alongside the `RULE_identifier` rule. But `isMajorIdentifierRule` sees the identifier candidate and routes entirely to semantic completions, discarding the keywords. At positions where the user most needs CE keyword suggestions, they get only `IDENTIFIER`.

We documented this in issue #5 and structured the tests around what the current code does correctly: keyword discovery at unit/class level, expression context filtering, oopath navigation, and no-crash verification for all new constructs.

## What's next

Two issues remain open: #4 (improve semantic completions) and #5 (revisit the completion routing, particularly the `isMajorIdentifierRule` either/or approach). The keyword suppression finding from this session makes #5 the more interesting problem — it's not just about incomplete code handling, but about how to surface both keywords and identifiers when the grammar offers both.
