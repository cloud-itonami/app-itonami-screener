# Operator quickstart — app-screener

This is the sanctions / AML screening service extracted from
`etzhayyim/root/60-apps/etzhayyim-project-screener` (see `migration.edn`).

**Read this before you try to run anything.** One part of this repository runs
today and is worth running on a schedule; the rest is a design that has never
been started. This document says which is which, and every command below was
executed verbatim to produce the output shown.

Measured 2026-08-30 against `53cf85e`.

---

## What is actually runnable today

| Part | State | Command |
|---|---|---|
| Citation verifier (`scripts/verify-sanctions-sources.cljk`) | **runs** | step 2 / step 3 below |
| Next.js UI (`app/`, `components/`) | **cannot start** — see step 4 | — |
| Screening API (`app/api/screen/transaction`) | **cannot answer** — see step 5 | — |
| Rules-change API (`app/api/rules/update`) | publishes nothing — see step 5 | — |
| Kafka rules consumer (`scripts/rules-engine-consumer/`) | never wired to a start script | — |

Only the first row is a working program. If you are here to operate something,
you are here for the verifier.

---

## 1. Get the code

This repository is a west project of the `com-junkawasaki/root` superproject:

```sh
cd ~/github/com-junkawasaki
west update --fetch smart app-screener
cd orgs/cloud-itonami/app-screener
```

Standalone is fine too — the verifier needs nothing from the superproject:

```sh
git clone git@github.com:cloud-itonami/app-screener
cd app-screener
```

You need [`nbb`](https://github.com/babashka/nbb) on `PATH`. Nothing else — no
`npm install`, no `node_modules`, no network for step 2.

---

## 2. Check the catalog without the network

`data/sanctions-sources.edn` names the seven designation lists this service
screens against, and the one regulation behind the single numeric constant
compiled into the request path. Start offline, because the offline leg is the
one that checks *this tree* rather than the outside world:

```sh
kbb --backend sci scripts/verify-sanctions-sources.cljk . --offline
```

Observed:

```
SCANNED	10
sources=7 thresholds=1 statutes=1 citations=10
  tree  OK  	ctr-usd	= 10000
INCONCLUSIVE	--offline: the tree agreed, but no citation was measured
```

**Exit code 3.** Not 0. That is deliberate and it is the most important thing in
this document to understand:

| exit | meaning |
|---|---|
| `0` | every citation resolved **and** every cited constant matched the tree |
| `1` | a citation broke, or the tree and the catalog disagree — **FAIL** |
| `3` | the check could not be run — **INCONCLUSIVE**, never treat as clean |

A catalog of remote URLs cannot tell "the citations are fine" from "I have no
network" if both leave through exit 0, so this script refuses to report a pass
it did not measure. `--offline` always exits 3, even when everything it *could*
check agreed. Never wire `--offline` into a green check.

The `SCANNED` line is the evidence floor: a run without one has not measured
anything, whatever else it printed.

> The script's header says `<dir>` "must come FIRST if given". Measured: it does
> not have to. The tree is `(first (remove #(str/starts-with? % "--") argv))`,
> which selects by predicate and not by position, so `--offline .`, `. --offline`
> and bare `--offline` from the repository root all give the identical exit 3 and
> identical output. The hazard that rule is guarding against is a flag that takes
> a *separate value* — with `--min 10 .` the tree would resolve to `"10"` — and
> this script has no such flag. Order it however you like; just do not add a
> value-taking flag later without fixing the argument parse.

### What the offline leg actually proved

The `tree OK ctr-usd = 10000` line is not decoration. The catalog claims that
`DEFAULT_TRANSACTION_THRESHOLD_USD` in `app/api/screen/transaction/route.ts` is
`10000` because 31 CFR 1010.311 says "more than $10,000". The verifier reads
that literal out of the TypeScript and fails if it drifts. Change the constant
without moving the citation — or cite a rule that no longer says that number —
and this line goes `FAIL`.

---

## 3. Check that the citations still resolve

```sh
kbb --backend sci scripts/verify-sanctions-sources.cljk .
```

Observed **today**:

```
SCANNED	10
sources=7 thresholds=1 statutes=1 citations=10
  tree  OK  	ctr-usd	= 10000
  cite  FAIL	ctr-usd	503
  cite  OK  	ctr-usd-human	206
  cite  OK  	eu-fsf	200
  cite  OK  	jp-act-prevention-transfer-criminal-proceeds	200
  cite  OK  	jp-mof-economic-sanctions	206
  cite  OK  	ofac-consolidated	206
  cite  OK  	ofac-sdn	206
  cite  OK  	uk-ofsi-conlist	206
  cite  OK  	uk-sanctions-list	206
  cite  OK  	un-sc-consolidated	206
FAIL	0 tree disagreement(s), 1 broken citation(s)
```

**Exit code 1.** This repository does not currently pass its own citation check,
and you should not "fix" it by repointing the URL. Read on.

`206` and `200` are the same answer here — the verifier issues a Range request
because some of these documents run to 28 MB, and normalises `206` to `200`.
`403`, `404` and `5xx` are never normalised.

### The one failing citation, and how to tell what broke

`ctr-usd` is the eCFR *versioner full-XML render* of 31 CFR 1010.311. It returns
`503` after about 40 seconds. That is the heavy render path timing out
server-side — **not** the citation moving. The rest of the same API answers
immediately:

```sh
curl -s -o /dev/null -w '%{http_code} (%{time_total}s)\n' \
  'https://www.ecfr.gov/api/versioner/v1/titles.json'
curl -s -o /dev/null -w '%{http_code} (%{time_total}s)\n' \
  'https://www.ecfr.gov/api/versioner/v1/structure/2026-01-01/title-31.json'
```

Observed: `200 (0.49s)` and `200 (1.04s)`. The API is up; one endpoint is slow
enough to fail. Note also that `ctr-usd-human` — the same section on the human
eCFR site — resolves fine, so the regulation itself is reachable and the quoted
text in the catalog is still checkable by hand.

Use that pair of probes to classify any future `ctr-usd` failure:

- API root `200`, versioner-full `503`/timeout → **the render path is down.**
  Transient. Re-run later. Do not edit the catalog.
- API root `200`, versioner-full `404` → **the citation moved.** Fix
  `:threshold/source-url` and re-record `:source/retrieved-at`.
- Everything unreachable → the verifier will exit `3` by itself rather than
  reporting all ten sources as broken. That is egress, not the citations.

**Do not repoint a URL to make this green.** The catalog's whole purpose is that
a sanctions screener names its publishers; a citation edited for the sake of an
exit code destroys the only thing it was for.

---

## 4. Why you cannot start the app

```sh
npm run dev
```

Observed:

```
npm error Missing script: "dev"
```

Exit code 1. `package.json` ships `"scripts": {}` — there is no `dev`, `build`,
`start` or `lint`. That is not the only obstacle:

- **`next` is not a dependency.** The tree has `next.config.mjs`, the `app/`
  router, `tsconfig.json` and `import { NextResponse } from "next/server"`, but
  `next` appears nowhere in `package.json`. Check with `grep '"next"'
  package.json` — it returns nothing.
- **There is no lockfile** (no `package-lock.json`, no `pnpm-lock.yaml`), so
  `npm ci` cannot run either, and 37 dependencies are pinned to `"latest"`
  (`grep -c '"latest"' package.json`) — two installs a week apart are not the
  same tree.

So the UI has never been started from this repository as extracted. Standing it
up means adding `next` plus a `scripts` block and committing a lockfile; that is
a real piece of work, not a missing paragraph in this document.

---

## 5. What the server routes would do if you did start it

Read, not run — the app does not start (step 4), so these are code readings with
file pointers, not observations. They matter because both routes read as working
in the source and are not.

**`app/api/screen/transaction/route.ts` cannot return a screening decision.**
It calls `executeKsqlQuery`, and `lib/ksqldb-client.ts` ends in an unconditional

```ts
throw new Error("Unsupported transport: direct runtime HTTP calls are disabled ...")
```

The throw happens *before* the line that would fall back to
`DEFAULT_TRANSACTION_THRESHOLD_USD`, so the route's `catch` returns HTTP 500 for
every request. The fallback threshold is unreachable at runtime.

This contradicts `data/sanctions-sources.edn`, which says the route "falls back
to a threshold of 10000 when ksqlDB does not answer". It does not; it 500s. The
constant is still worth citing — it is what the code would use once a transport
exists — but do not read that comment as a description of current behaviour.

**`app/api/rules/update/route.ts` publishes nothing.** The `kafkaProducer.send`
call is commented out. The route builds an event, `console.log`s it, sleeps
500 ms and returns `200` with `"Rule change event published successfully"`. An
operator watching only the HTTP status would conclude a rule change had been
distributed. It has not.

**`scripts/rules-engine-consumer/` is real kafkajs code with no way to start
it** — no script entry, and it defaults to `localhost:9092` when `KAFKA_BROKERS`
is unset.

Environment variables referenced by the tree, none of which are consumed by
anything that currently runs: `KSQLDB_ENDPOINT`, `KSQLDB_KEY`,
`KSQLDB_KEY_SECRET`, `KAFKA_BROKERS`.

---

## 6. What to run on a schedule

Only step 3, and treat its exit code exactly as the table in step 2 says:

```sh
kbb --backend sci scripts/verify-sanctions-sources.cljk .
```

Sanctions lists are republished continuously — OFAC's SDN list carried a publish
date one day older than the catalog's measurement when it was recorded. The
value of this check is that it fails loudly when a publisher moves a URL, and
declines to answer when it cannot see the network, so a silent outage never
accumulates as a green tick.

Do not paper over exit 3. It is the answer "I did not measure", and it is the
whole reason this check is worth having.
