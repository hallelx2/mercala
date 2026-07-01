-- V6__semantic.sql
-- Semantic search with pgvector (embeddings)

-- 1. Add embedding vector column (1536 dimensions for OpenAI text-embedding-3-small)
ALTER TABLE product ADD COLUMN embedding vector(1536);

-- 2. Create HNSW index for fast Approximate Nearest Neighbor (ANN) cosine similarity search
CREATE INDEX product_embedding_idx ON product 
USING hnsw (embedding vector_cosine_ops);
