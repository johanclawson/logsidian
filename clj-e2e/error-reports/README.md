# Error Reports

This directory contains generated test error reports from the TDD loop.

## Files

- `latest-tdd.md` - Most recent TDD loop report (human-readable)
- `latest.edn` - Most recent structured report (machine-readable)
- `latest.md` - Most recent test reporter output

## Usage

Reports are generated automatically by:
- `scripts/tdd-loop.ps1` - The TDD loop script
- `logseq.e2e.test-reporter` - The Clojure test reporter

## Reading Reports

```bash
# View latest TDD report
cat clj-e2e/error-reports/latest-tdd.md

# View latest structured report
cat clj-e2e/error-reports/latest.edn
```

## Cleaning Up

All generated files (except this README and .gitignore) are ignored by git.
To clean up:

```bash
rm clj-e2e/error-reports/*.edn clj-e2e/error-reports/*.md
```
