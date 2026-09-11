# app-screener — sanctions / AML screening service

Screening UI and rules plumbing for the `cloud-itonami` fleet, extracted from
`etzhayyim/root/60-apps/etzhayyim-project-screener` (`migration.edn`). Screens
customer names and transactions against the seven designation lists named in
[`data/sanctions-sources.edn`](data/sanctions-sources.edn) — OFAC SDN and
Consolidated, the UN Security Council list, the EU FSF, the UK OFSI ConList and
FCDO Sanctions List, and Japan's 財務省 asset-freeze list.

## State: design, plus one working check

Only one part of this repository is a running program today.

- **Runs:** `scripts/verify-sanctions-sources.cljk` re-fetches every citation in
  the catalog and reads the CTR threshold constant back out of the TypeScript,
  so neither the list publishers nor the regulation behind
  `DEFAULT_TRANSACTION_THRESHOLD_USD` can drift without failing the check.
- **Does not run:** the Next.js UI has no `next` dependency, no lockfile and an
  empty `scripts` block; the screening route cannot return a decision because
  its ksqlDB transport is disabled; the rules-update route logs its event
  instead of publishing it.

**Read [`docs/operator-quickstart.md`](docs/operator-quickstart.md) before
running anything.** It walks the check, explains why it exits `3` rather than
`0` when it could not measure, and records exactly which parts of the app are
not startable and why.

```sh
kbb --backend sci scripts/verify-sanctions-sources.cljk .            # 0 clean · 1 broken · 3 could not measure
kbb --backend sci scripts/verify-sanctions-sources.cljk . --offline  # tree-only; always 3
```

## Provenance

The UI under `app/` and `components/` was generated with [v0.dev](https://v0.dev).
The v0 sync described in this README's previous revision targeted a different
repository (`etzhayyim/v0-sanctions-screening-service` on Vercel); nothing pushes
into this one, which was extracted from the etzhayyim monorepo at the revision
recorded in `migration.edn`. This tree is the record.

`PROJECT.jsonld` carries the fleet project record. `NOTICE` states the licence:
Apache 2.0 plus the etzhayyim Charter Compliance Rider v3.1 — note that the
`CHARTER-RIDER.md` it points at was not carried across in the extraction and is
not in this repository.
