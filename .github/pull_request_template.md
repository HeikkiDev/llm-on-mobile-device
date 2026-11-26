## What

<!-- The change, one line per item. -->

## Why

<!-- The problem or SPEC section this addresses. -->

## Evidence

A change is not done without proof (AGENTS.md). Fill every box or move it to "Not verified".

- [ ] CI green on both workflows
- [ ] UI check, iOS Simulator: paste `summary.md` below and attach its screenshots
- [ ] UI check, Android emulator: paste `summary.md` below and attach its screenshots
- [ ] Manual checks for what the script does not cover: what you did, what you saw, screenshot or log

<!--
scripts/ui-check.py ios     # add scenario names to run more than "translate"
scripts/ui-check.py android
Results go to evidence/<timestamp>-<platform>/ (gitignored).
-->

## Not verified

<!-- Everything not proven above, and why (no device, needs a phone, etc.). "None" if none. -->
