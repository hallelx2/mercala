package com.mercala.catalog;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantRepository extends JpaRepository<Variant, UUID> {

    Optional<Variant> findBySku(String sku);
}
