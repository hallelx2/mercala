package com.mercala.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByTenantId(UUID tenantId);

    Optional<Product> findByTenantIdAndId(UUID tenantId, UUID id);

    @Query(value = """
        SELECT p.* FROM product p
        WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
          AND p @@@ pdb.parse(:query)
        ORDER BY paradedb.score(p.id) DESC
        """,
        countQuery = """
        SELECT count(*) FROM product p
        WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
          AND p @@@ pdb.parse(:query)
        """,
        nativeQuery = true)
    Page<Product> searchLexical(@Param("query") String query, Pageable pageable);

    @Query(value = """
        SELECT p.* FROM product p
        WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
          AND p.embedding IS NOT NULL
        ORDER BY p.embedding <=> CAST(:queryEmbedding AS vector) ASC
        """,
        countQuery = """
        SELECT count(*) FROM product p
        WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
          AND p.embedding IS NOT NULL
        """,
        nativeQuery = true)
    Page<Product> searchSemantic(@Param("queryEmbedding") String queryEmbedding, Pageable pageable);
}
