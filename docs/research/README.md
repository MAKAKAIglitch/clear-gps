# Research notes

This directory stores dated research and technical reviews for Clear GPS.

## File naming

Use this exact pattern for every research document:

`YYYY-MM-DD-short-topic-name.md`

- Use the calendar date when the research was completed.
- Keep the date first so files sort chronologically.
- Use lowercase English words separated by hyphens for the topic.
- Keep one main subject per document.
- Do not silently overwrite an older result. Add a new dated document for a follow-up, correction or fresh verification.

Examples:

- `2026-11-21-navigation-ui-baseline.md`
- `2026-11-22-geocoding-provider-review.md`
- `2026-11-24-offline-map-storage-options.md`

## What every research document should contain

1. **Date and scope** — what was investigated and what was outside the scope.
2. **Snapshot** — repository branch and commit SHA, app/library version, or the exact product versions reviewed.
3. **Method and sources** — source files, official documentation, papers, repositories or other URLs, with access/retrieval dates where relevant.
4. **Findings** — facts supported by the cited sources. Keep implementation facts separate from assumptions and recommendations.
5. **Gaps and uncertainty** — what could not be verified, what was not tested, and what decisions remain open.
6. **Recommendations / next steps** — actionable follow-up work, ordered by priority where useful.

For codebase reviews, link to the exact repository files and identify the reviewed commit. For external research, link directly to the original sources and record when they were checked. Do not describe a build, test, device run, benchmark or external service as successful unless it was actually executed and verified.

## Research index

- [2026-11-21 — Navigation UI baseline review](2026-11-21-navigation-ui-baseline.md)
