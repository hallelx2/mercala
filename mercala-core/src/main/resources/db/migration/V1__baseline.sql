-- Baseline migration: enable the Postgres extensions Mercala relies on.
--   vector    (pgvector)            -> semantic / embedding search
--   pg_search (ParadeDB, Tantivy)   -> BM25 lexical full-text search
-- Both ship in the paradedb/paradedb image used locally and in tests.
-- Domain tables start in M1 (identity).

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;
