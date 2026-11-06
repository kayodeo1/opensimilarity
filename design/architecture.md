# Architecture (clean-room)

## Recommended stack (MVP)

**Prefer Java / Jakarta EE (TomEE or Quarkus) + PostgreSQL 18 + pgvector**, matching Kayode’s production comfort (Corretto 17+, EclipseLink/Hibernate, existing FYPMS / pgvector experience). Offer a thin Django sidecar only if a specific library (e.g. datasketch experimentation) is faster to prototype, then port hot paths.

Justification:

- One operational database for relational data, fingerprint postings, and vectors.
- Team already ships Jakarta EE services.
- Avoid early microservice sprawl.

## pgvector (verified 2026-11-06)

- Current release **0.8.7** (2026-10-01). Sources: [pgvector CHANGELOG](https://github.com/pgvector/pgvector/blob/master/CHANGELOG.md), [tags](https://github.com/pgvector/pgvector/tags).
- HNSW preferred over IVFFlat for query speed/recall; higher build memory.
- `vector` HNSW up to **2,000** dims; `halfvec` HNSW up to **4,000** dims; `halfvec` storage up to 16,000 dims.
- Iterative index scans exist since 0.8.0 for better filtered search behavior.
- Escape hatch later: pgvectorscale or external vector DB only if dept-scale evals fail.

## Fingerprint index (verbatim / near-copy)

Based on public algorithms only:

- **Broder** resemblance & containment via shingles + MinHash sketches ([Broder 1997, local PDF](file://tool-results)).
- **Winnowing** (Schleimer, Wilkerson, Aiken, SIGMOD 2003) for guaranteed detection of substring copies above threshold ([local PDF](file://tool-results)); MOSS lineage for code, adaptable to text with tuned k / window.

Storage sketch:

- `documents(id, owner, collection_id, sha256, retention_until, index_policy, ...)`
- `shingles(hash bigint, doc_id, pos)` partitioned by hash range or doc_id; btree `(hash, doc_id)` or GIN on hash arrays for candidate retrieval.
- `sketches(doc_id, minhash bytea)` for cheap Jaccard prefilter.

At 10^9–10^10 posting rows, plan table partitioning and possibly a RocksDB / custom posting store; **MVP stays in Postgres**.

## Semantic / paraphrase path

- Passage embeddings (e.g. multilingual E5 / BGE-M3 / LaBSE class; check license) into `halfvec` columns + HNSW cosine.
- Cross-encoder rerank top-k on CPU/GPU worker.
- Cross-language: multilingual embeddings first; African languages via Masakhane checkpoints where license OK.
- Citation-based signals (Gipp CbPD / HyPlag ideas) as a later phase using reference lists, not Turnitin data.

## Services

| Component | Role |
| --- | --- |
| API (Jakarta REST) | Auth (OIDC), assignments, ingest, report fetch |
| Ingest workers | Parse PDF/DOCX, normalize text, extract citations |
| Fingerprint workers | Winnow + MinHash, write postings |
| Embed workers | Chunk, embed, upsert pgvector |
| Report builder | Merge candidates, align spans, apply filters |
| Policy service | Retention, consent, deletion |

Async: Jakarta Batch or a Postgres-backed queue (keep ops simple). Celery-like only if Django path chosen.

## Security & tenancy

- Row-level tenancy by institution / department.
- Encryption at rest for full text; fingerprints alone are not enough to reconstruct essays but still treat as sensitive.
- No credentials or calls to Turnitin APIs.

## What we explicitly will not build into core

- Scrapers for authenticated publisher paywalls.
- Binary RE of commercial detectors.
- Silent global student corpus sharing.
