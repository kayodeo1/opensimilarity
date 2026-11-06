-- Core relational schema: collections, documents, postings, sketches, checks, audit log.

CREATE TABLE collections (
    id              uuid PRIMARY KEY,
    name            text NOT NULL,
    department      text,
    -- Upper bound for documents in this collection. A document may only narrow it.
    index_policy    text NOT NULL CHECK (index_policy IN ('NEVER_INDEX', 'COURSE_ONLY', 'DEPT_ONLY')),
    -- NULL means no TTL.
    retention_days  integer CHECK (retention_days > 0),
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text NOT NULL,
    CHECK (index_policy <> 'DEPT_ONLY' OR department IS NOT NULL)
);

CREATE TABLE documents (
    id                uuid PRIMARY KEY,
    collection_id     uuid NOT NULL REFERENCES collections (id) ON DELETE CASCADE,
    title             text NOT NULL,
    original_filename text,
    mime_type         text NOT NULL,
    byte_size         bigint NOT NULL,
    sha256            char(64) NOT NULL,
    owner_ref         text,
    consent_to_index  boolean NOT NULL,
    index_policy      text NOT NULL CHECK (index_policy IN ('NEVER_INDEX', 'COURSE_ONLY', 'DEPT_ONLY')),
    retention_until   timestamptz,
    -- Extracted text only; the uploaded file itself is not kept.
    full_text         text NOT NULL,
    word_count        integer NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        text NOT NULL,
    CHECK (consent_to_index OR index_policy = 'NEVER_INDEX')
);
CREATE INDEX documents_collection_idx ON documents (collection_id);
CREATE INDEX documents_sha256_idx ON documents (sha256);

-- Winnowed shingle postings. Only documents that may be matched against have rows here.
CREATE TABLE shingles (
    hash    bigint  NOT NULL,
    doc_id  uuid    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    pos     integer NOT NULL,
    PRIMARY KEY (hash, doc_id, pos)
);
CREATE INDEX shingles_doc_idx ON shingles (doc_id);

-- MinHash signature over all of a document's shingles.
CREATE TABLE sketches (
    doc_id  uuid PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    minhash bytea NOT NULL
);

CREATE TABLE checks (
    id              uuid PRIMARY KEY,
    document_id     uuid NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    overlap_percent numeric(5, 2) NOT NULL,
    report          jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      text NOT NULL
);
CREATE INDEX checks_document_idx ON checks (document_id);

-- No foreign keys on purpose: audit rows must outlive the records they describe.
CREATE TABLE audit_log (
    id          bigserial PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,
    entity_type text NOT NULL,
    entity_id   uuid NOT NULL,
    detail      jsonb
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id);
