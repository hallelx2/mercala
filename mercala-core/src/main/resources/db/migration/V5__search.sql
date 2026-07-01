-- V5__search.sql
-- Lexical search index with pg_search (BM25)

-- 1. Add tags array column to product table
ALTER TABLE product ADD COLUMN tags VARCHAR(255)[] NOT NULL DEFAULT '{}';

-- 2. Create ParadeDB BM25 index over product name, description, and tags
CREATE INDEX product_search_idx ON product 
USING bm25 (id, name, description, tags) 
WITH (key_field = 'id');
