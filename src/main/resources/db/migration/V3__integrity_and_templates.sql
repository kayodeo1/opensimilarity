-- Integrity flags found at upload (hidden text needs the original file, which is not kept), and assignment templates.

-- JSON written by the ingest pipeline. NULL for documents uploaded before this migration.
ALTER TABLE documents ADD COLUMN integrity jsonb;

-- Text supplied with an assignment (instructions, cover sheet, question prompts). Matches on it are excluded.
CREATE TABLE collection_templates (
    collection_id uuid PRIMARY KEY REFERENCES collections (id) ON DELETE CASCADE,
    title         text NOT NULL,
    full_text     text NOT NULL,
    word_count    integer NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    text NOT NULL
);
