# MVP roadmap (2–4 weeks focused)

Assumes one strong backend engineer + part-time design help. Phases are sequential; do not start Phase 3 until Phase 2 acceptance passes.

## Phase 0 – Foundations (days 1–3)

**Milestones**

- Repo, CI, Postgres 18 + pgvector 0.8.7 local compose.
- Document + collection schema with retention / index_policy enums.
- PDF/DOCX → plain text pipeline (Apache Tika or equivalent).

**Acceptance**

- Upload 20 sample FYP-like PDFs; text extract succeeds ≥95%.

## Phase 1 – Fingerprint similarity (week 1)

**Milestones**

- Winnowing + shingle postings; MinHash prefilter.
- Report v1: overall overlap %, match list with spans and source doc ids.
- Filters: exclude bibliography heuristic, quotes, small matches (n-word threshold).

**Acceptance**

- Planted verbatim paragraph across two docs is detected with span highlight.
- Bibliography-only overlap drops below threshold when filter on.
- No external network required for dept-corpus checks.

## Phase 2 – Embedding paraphrase assist (week 2)

**Milestones**

- Chunking + embedding job; `halfvec` HNSW index.
- Merge fingerprint + embedding candidates; tag method on each match.
- Basic instructor UI (or API-first + minimal web) for review.

**Acceptance**

- Light paraphrase plant (synonym rewrite) appears as embedding match when fingerprint misses.
- Each match shows algorithm tag and source.

## Phase 3 – Policy & student agency (week 3)

**Milestones**

- Opt-in indexing, TTL deletion job, export ZIP (text + report JSON).
- Assignment defaults: never-index / dept-only / course-only.
- Audit log for views and deletes.

**Acceptance**

- Delete request removes full text + postings + vectors within SLA (e.g. 24h job).
- Export opens in a clean viewer without proprietary formats.

## Phase 4 – Hardening & demo (week 4)

**Milestones**

- Seed Nigerian HE sample corpus (synthetic + open theses where license allows).
- Eval notebook: precision/recall on planted plagiarism set; TeSToP-inspired cases.
- One-click demo for FYPMS-adjacent workflow.

**Acceptance**

- Written eval numbers checked into `research/eval-baseline.md`.
- Demo script ≤10 minutes for a supervisor persona.

## Explicitly deferred

- AI-writing classifier productization.
- Cross-institution federation protocol.
- Full LMS LTI 1.3 suite (stub only).
- Publisher-scale web crawl.
- Mobile apps.
- Gradescope-like grading features.

## Top dependencies / risks

- PDF extraction quality on scanned theses (may need OCR later).
- Embedding model license + GPU cost.
- Fingerprint index growth; monitor posting table size weekly.
