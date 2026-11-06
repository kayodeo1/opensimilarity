-- Paraphrase assist: passage embeddings with an HNSW cosine index.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunks (
    doc_id     uuid    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    ordinal    integer NOT NULL,
    word_start integer NOT NULL,
    word_end   integer NOT NULL,
    char_start integer NOT NULL,
    char_end   integer NOT NULL,
    model      text    NOT NULL,
    embedding  halfvec(384) NOT NULL,
    PRIMARY KEY (doc_id, ordinal)
);
CREATE INDEX chunks_embedding_hnsw_idx ON chunks USING hnsw (embedding halfvec_cosine_ops);
