# Product brief: OpenSimilarity

Working name: **OpenSimilarity** (alternatives: Matchlight, CiteTrace, ClearMatch). Prefer OpenSimilarity for now.

## Vision

A self-hostable similarity and academic-integrity **support** tool for universities (starting with Nigerian / African HE and departments that already run systems like FYPMS). It helps educators find overlapping text and suspicious reuse, with student-respecting defaults. It does **not** claim to detect plagiarism or AI authorship by itself.

## Who it serves

1. **Primary:** course instructors and project supervisors reviewing coursework and final-year projects.
2. **Secondary:** academic integrity officers who need auditable evidence packs.
3. **Tertiary:** students who want formative self-checks before submission (opt-in, no silent repository ingest).

## Core workflows (MVP)

1. Create assignment / project collection with retention policy (days, never-index, index-in-dept-only).
2. Ingest documents (PDF, DOCX, plain text) with consent metadata.
3. Run dual check: fingerprint (winnowing) + optional embedding nearest neighbors against chosen corpora (course corpus, department corpus, curated open web snapshot, Crossref OA subset if licensed).
4. Review explainable report: spans, sources, filters applied, confidence, algorithm tag.
5. Export / delete; student request-to-delete honored within policy SLA.

## What we will NOT copy from Turnitin

- Closed global student-paper marketplace dynamics and perpetual irrevocable license framing.
- Opaque proprietary match databases presented as a black-box %.
- Shipping AI-writing “guilt scores” as a primary product feature.
- Pixel-for-pixel UI clones or proprietary report schemas.
- Any reverse-engineered Turnitin protocol, binary, or private algorithm.

## Principles

- Clean-room design from public literature (Broder resemblance/containment, Schleimer winnowing, PAN evaluations, citation-based methods).
- Human-in-the-loop; software finds similarity, people decide integrity.
- Stack fits Kayode’s strengths: Java/Jakarta EE or Python/Django + PostgreSQL + pgvector (see architecture.md).
- Privacy by default for African HE contexts (NDPR-aware).

## Success metrics (product)

- Instructor time-to-first-useful-match < 2 minutes on a 5k-word paper against a 10k-doc dept corpus.
- False-alarm rate on template / bibliography content < 5% after default filters (internal eval set).
- 100% of matches show source + span + method.
- Student deletion requests completable without vendor tickets.
