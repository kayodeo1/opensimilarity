# OpenSimilarity

A self-hostable tool that finds overlapping text between academic documents and shows a reviewer exactly where it is.
It reports spans, sources and the method behind each match. **It does not decide whether anything is plagiarism, and it
does not try to detect AI writing.** People make those judgements.

It is a clean-room design built from published algorithms: shingling and MinHash (Broder 1997) and winnowing
(Schleimer, Wilkerson and Aiken 2003). It does not call or reverse-engineer any commercial product. The reasoning is in
[`design/`](design) and [`research/`](research).

**Status: MVP, covering roadmap Phases 0 to 2. There is no authentication yet, so do not expose it to a network.** See
[Limitations](#limitations).

## Run it

### With Docker Compose (database and app)

```bash
cp .env.example .env        # then set OPENSIM_DB_PASSWORD
docker compose up --build
```

Then open <http://localhost:8080>. The port is published on loopback only.

### From source, with your own database

Requires Java 17, Maven and PostgreSQL with [pgvector](https://github.com/pgvector/pgvector) (run against PostgreSQL 16
and pgvector 0.8.7). If the database user is not a superuser, run `CREATE EXTENSION vector;` first. Migrations run
automatically at startup.

```bash
mvn -q package
export OPENSIM_DB_PASSWORD=...
java -jar target/opensimilarity.jar
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `OPENSIM_DB_PASSWORD` | none, required | Database password |
| `OPENSIM_DB_URL` | `jdbc:postgresql://localhost:5432/opensimilarity` | JDBC URL |
| `OPENSIM_DB_USER` | `opensim` | Database user |
| `OPENSIM_BIND_ADDRESS` | `127.0.0.1` | Listen address |
| `OPENSIM_PORT` | `8080` | Listen port |
| `OPENSIM_EMBEDDING_PROVIDER` | `minilm` | `none` turns paraphrase assist off |

If the app hangs at `HikariPool-1 - Starting...`, the port may be accepting connections without forwarding them. This
happens when dockerd runs with `--userland-proxy=false --iptables=false`. Connect to the container's IP instead, which
`docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' <container>` prints.

## See it work

With the server running, `scripts/demo.sh` uploads everything in [`samples/`](samples) and checks the results. It
needs `curl` and `jq`, and exits non-zero if any expected result is missing.

```bash
scripts/demo.sh http://localhost:8080
```

Or by hand:

```bash
B=http://localhost:8080

# 1. Create a collection
C=$(curl -s -X POST $B/api/collections -H 'Content-Type: application/json' \
      -d '{"name":"CVE 401 projects","department":"Civil Engineering","indexPolicy":"COURSE_ONLY","retentionDays":365}' | jq -r .id)

# 2. Upload two documents that share a paragraph. Indexing is opt-in, so consent must be stated.
curl -s -X POST $B/api/collections/$C/documents -F file=@samples/source_paper.pdf -F consentToIndex=true | jq '{id,title,wordCount,indexPolicy}'
D=$(curl -s -X POST $B/api/collections/$C/documents -F file=@samples/student_verbatim.txt -F consentToIndex=true | jq -r .id)

# 3. Run a check and show the match with its spans
CHECK=$(curl -s -X POST $B/api/documents/$D/checks)
jq '{overlap: .summary.overlapPercent, matches: [.matches[] | {method, source: .sourceTitle, matchedWords,
     queryChars: [.query.charStart, .query.charEnd], sourceChars: [.source.charStart, .source.charEnd], text: .query.text}]}' <<<"$CHECK"

# 4. Fetch the stored report again
curl -s $B/api/checks/$(jq -r .checkId <<<"$CHECK") | jq .summary
```

### What the samples show

| Sample | Purpose | Expected result |
| --- | --- | --- |
| `source_paper.pdf` | The source | |
| `student_verbatim.txt`, `.pdf`, `.docx` | One paragraph planted word for word | `FINGERPRINT` match with spans |
| `student_bibliography_only.txt` | Only the reference list is shared | 0% with the filter on, and the removed match stays listed |
| `student_quoted.txt` | Copied sentence inside quotation marks | 0% with the filter on, and the removed match stays listed |
| `student_paraphrase_light.txt` | Synonym rewrite of one paragraph | 0% exact overlap, plus an `EMBEDDING` lead |
| `student_paraphrase_heavy.txt` | Restructured rewrite | **Not flagged** at the default threshold (see [Limitations](#limitations)) |
| `control_same_topic.txt`, `control_unrelated.txt` | Original writing | No matches |

All samples are invented for this project.

## API

Send an optional `X-Actor` header to label audit rows. It is self-declared, not authentication.

| Method and path | Purpose |
| --- | --- |
| `GET /api/health` | Status, database, pgvector version, embedding model |
| `POST /api/collections` | Create. JSON: `name`, `department`, `indexPolicy`, `retentionDays` |
| `GET /api/collections`, `GET /api/collections/{id}` | Read collections |
| `POST /api/collections/{id}/documents` | Upload PDF, DOCX or TXT. Multipart: `file`, `title`, `ownerRef`, `consentToIndex`, `indexPolicy` |
| `GET /api/collections/{id}/documents` | List documents |
| `GET /api/documents/{id}`, `GET /api/documents/{id}/text` | Metadata, extracted text |
| `POST /api/documents/{id}/checks` | Run a check. Optional JSON: `minMatchWords`, `excludeBibliography`, `excludeQuotes`, `useEmbeddings`, `minCosine` |
| `GET /api/checks/{id}` | Fetch a stored report |

### Reading a report

- `summary.overlapPercent` is `matchedWords / consideredWords`. Only words exactly shared with a source count, and
  `consideredWords` is the total minus words removed by the filters.
- `summary.paraphraseLeadPercent` is separate because it rests on a model's judgement rather than on exact overlap.
- Every match has a `method` (`FINGERPRINT` or `EMBEDDING`), a source document, and `query` and `source` spans.
  Character offsets are UTF-16 code units into the extracted text, and ranges are half-open.
- `excludedMatches` lists what the filters removed and why, so a filter never hides a match silently. The list is
  capped at 100 entries; `excludedMatchesTotal` has the full count.
- `corpus` shows how the search narrowed. `eligibleDocuments: 0` means there was nothing to compare against, which is
  different from a document having no overlap.

## How matching works

1. **Extract.** Apache Tika turns the upload into plain text. Only the text is stored, not the file.
2. **Tokenize.** Words are NFKC-normalized and lower-cased, keeping their original offsets. Invisible characters are
   ignored and hyphenated line breaks are rejoined.
3. **Fingerprint.** Every 5-word shingle is hashed and winnowed with a window of 4, and the selected hashes become
   postings. Any shared run of at least 8 words is guaranteed to share a fingerprint.
4. **Find candidates.** A document is aligned if it shares enough fingerprints *or* its MinHash estimate marks it as a
   near duplicate. Near duplicates rank first, so they survive the cap of 50 aligned documents per check.
   MinHash is deliberately not the only gate: one copied paragraph in a long document gives a Jaccard near zero, so a
   pure resemblance filter would lose the case that matters most.
5. **Align.** Every shingle of the candidate is compared to recover exact word-level spans. Runs separated by up to 4
   changed words merge into one match, but only the exact words count.
6. **Filter.** The heuristics remove the reference list (a heading such as "References" in the last 70% of the text),
   text in double quotation marks (up to 1,500 characters) and matches under 12 words.
7. **Paraphrase assist.** Passages not already explained by exact matches are embedded with all-MiniLM-L6-v2 (384
   dimensions, running locally with the model bundled in the jar) and searched through a `halfvec` HNSW index. Matches
   at cosine 0.72 or above are reported as `EMBEDDING` leads for review.

## Privacy defaults

- **Indexing is opt-in.** Without `consentToIndex=true`, a document can be checked but is never a source for anyone,
  and no postings or vectors are stored for it.
- **`COURSE_ONLY`** documents are visible only within their collection, and **`DEPT_ONLY`** within collections of the
  same department. A document can narrow its collection's policy but not widen it.
- **Retention.** After `retentionDays`, a document stops being a source and its API returns `410 Gone`.
  **It is not yet erased from the database**, because the purge job is Phase 3.
- **No outside calls for matching.** Fingerprinting and embedding run locally.

## Limitations

Please read these before relying on results.

- **No authentication or tenancy.** Anyone who can reach the port can read every document. This is why it binds to
  loopback by default.
- **Paraphrase detection is an aid and is weak on heavy rewrites.** The threshold comes from only 5 paraphrase pairs
  and 72 unrelated paragraph pairs, all synthetic, so treat it as a starting point rather than an evaluated figure:

  | Pair type | Cosine |
  | --- | --- |
  | Synonym rewrites | 0.79 to 0.86 |
  | Original writing on the same topic (highest seen) | 0.66 |
  | Heavily restructured rewrites | 0.63 to 0.67 |

  Restructured rewrites fall in the same range as honest writing, so they are missed at the default. Lowering
  `minCosine` finds them but also flags original work on the same subject.
- **The model is mainly for English.** Fingerprint matching handles other scripts, including Yoruba with diacritics,
  but paraphrase assist has not been evaluated on African languages.
- **Filters are simple heuristics.** A reference list with no heading is not detected, and quote detection covers
  double quotation marks only. Filtered matches stay listed in the report so a reviewer can check them.
- **Scanned PDFs are rejected,** because there is no OCR.
- **Processing is synchronous.** On a test machine, a 5,000-word check took under a second and a 57,000-word check
  about 6 seconds, each against one document of similar size. Larger corpora have not been measured.
- **Text is not encrypted at rest,** and there is no deletion or export endpoint yet (Phase 3).
- **Phase 0 extraction** was tested on 20 generated PDFs, not on real theses.
- **Do not use the `-q` quantized MiniLM artifact.** On an AVX-VNNI Xeon it returned different vectors for identical
  text. `MiniLmEmbedderTest` guards against this for the model in use.

## Tests

```bash
mvn -q package    # unit tests, including the real embedding model; no database needed
```

`scripts/demo.sh` is the end-to-end test against a running server and database.

## License

[MIT](LICENSE). The bundled all-MiniLM-L6-v2 model is Apache-2.0.
