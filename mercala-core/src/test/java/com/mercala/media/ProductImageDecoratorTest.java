package com.mercala.media;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.mercala.catalog.ProductStatus;
import com.mercala.catalog.web.dto.ProductResponse;
import com.mercala.platform.multitenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Attaching imagery to a page of products.
 *
 * <p>The query count is the whole reason this class exists rather than a line in the product
 * mapper, so it is asserted rather than assumed: a storefront page of twenty-four products
 * must cost one image query, not twenty-four.
 */
class ProductImageDecoratorTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SIGNED = "http://storage/x.png?X-Amz-Signature=abc";

    /** Counts calls, so the batching claim is measured rather than believed. */
    private static class CountingRepository {
        final AtomicInteger batchCalls = new AtomicInteger();
        final ProductImageRepository mock = Mockito.mock(ProductImageRepository.class);

        ProductImageRepository returning(List<ProductImage> images) {
            when(mock.findByTenantIdAndProductIdInOrderByCreatedAtDescIdDesc(any(), any()))
                    .thenAnswer(invocation -> {
                        batchCalls.incrementAndGet();
                        Collection<UUID> asked = invocation.getArgument(1);
                        return images.stream()
                                .filter(image -> asked.contains(image.getProductId()))
                                .toList();
                    });
            return mock;
        }
    }

    private static ProductImage image(UUID productId, String url) {
        return new ProductImage(TENANT, productId, url);
    }

    private static ProductResponse product(UUID id, String name) {
        return new ProductResponse(
                id, TENANT, name, "a description", ProductStatus.ACTIVE,
                java.math.BigDecimal.TEN, null, List.of(), List.of(), null, null);
    }

    private ProductImageDecorator decorator(ProductImageRepository repository) {
        MediaObjectStorage storage = Mockito.mock(MediaObjectStorage.class);
        when(storage.objectKeyOf(anyString())).thenReturn("tenant/product.png");
        when(storage.presignedView(anyString(), any(Duration.class))).thenReturn(SIGNED);
        return new ProductImageDecorator(repository, storage, Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aPageOfProductsCostsOneImageQuery() {
        TenantContext.setCurrentTenant(TENANT);
        List<ProductResponse> products = java.util.stream.IntStream.range(0, 24)
                .mapToObj(index -> product(UUID.randomUUID(), "Product " + index))
                .toList();

        CountingRepository repository = new CountingRepository();
        Page<ProductResponse> page = new PageImpl<>(products, PageRequest.of(0, 24), 24);

        decorator(repository.returning(List.of())).decorate(page);

        assertThat(repository.batchCalls.get())
                .as("one query for the whole page, not one per product")
                .isEqualTo(1);
    }

    @Test
    void eachProductGetsItsOwnImagesAndNobodyElses() {
        TenantContext.setCurrentTenant(TENANT);
        UUID shirtId = UUID.randomUUID();
        UUID trousersId = UUID.randomUUID();

        CountingRepository repository = new CountingRepository();
        ProductImageRepository stub = repository.returning(List.of(
                image(shirtId, "http://storage/shirt-1.png"),
                image(shirtId, "http://storage/shirt-2.png"),
                image(trousersId, "http://storage/trousers.png")));

        List<ProductResponse> decorated = decorator(stub).decorate(
                List.of(product(shirtId, "Linen shirt"), product(trousersId, "Linen trousers")));

        assertThat(decorated.get(0).images()).hasSize(2);
        assertThat(decorated.get(0).images())
                .extracting(view -> view.url())
                .containsExactly("http://storage/shirt-1.png", "http://storage/shirt-2.png");
        assertThat(decorated.get(1).images()).hasSize(1);
    }

    @Test
    void aProductWithNoImagesGetsAnEmptyListRatherThanNull() {
        TenantContext.setCurrentTenant(TENANT);
        CountingRepository repository = new CountingRepository();

        ProductResponse decorated = decorator(repository.returning(List.of()))
                .decorate(product(UUID.randomUUID(), "Linen shirt"));

        assertThat(decorated.images()).isNotNull().isEmpty();
    }

    @Test
    void eachImageCarriesBothTheStoredUrlAndASignedOne() {
        TenantContext.setCurrentTenant(TENANT);
        UUID productId = UUID.randomUUID();
        CountingRepository repository = new CountingRepository();

        ProductResponse decorated = decorator(
                repository.returning(List.of(image(productId, "http://storage/shirt.png"))))
                .decorate(product(productId, "Linen shirt"));

        assertThat(decorated.images().get(0).url()).isEqualTo("http://storage/shirt.png");
        assertThat(decorated.images().get(0).viewUrl()).isEqualTo(SIGNED);
    }

    /** Storage being unreachable costs a picture, not the page it was on. */
    @Test
    void anImageThatCannotBeSignedKeepsItsIdentityAndLosesItsViewUrl() {
        TenantContext.setCurrentTenant(TENANT);
        UUID productId = UUID.randomUUID();
        CountingRepository repository = new CountingRepository();

        MediaObjectStorage storage = Mockito.mock(MediaObjectStorage.class);
        when(storage.objectKeyOf(anyString())).thenReturn("tenant/product.png");
        when(storage.presignedView(anyString(), any(Duration.class)))
                .thenThrow(new MediaObjectStorage.MediaStorageException("storage is down"));

        ProductResponse decorated = new ProductImageDecorator(
                repository.returning(List.of(image(productId, "http://storage/shirt.png"))),
                storage,
                Duration.ofHours(1))
                .decorate(product(productId, "Linen shirt"));

        assertThat(decorated.images()).hasSize(1);
        assertThat(decorated.images().get(0).url()).isEqualTo("http://storage/shirt.png");
        assertThat(decorated.images().get(0).viewUrl()).isNull();
    }

    /**
     * Without a tenant there is nothing to scope the lookup to. Returning no imagery is the
     * only safe answer — the alternative is a query that reads across stores.
     */
    @Test
    void withNoTenantInContextNoImagesAreLookedUpAtAll() {
        TenantContext.clear();
        CountingRepository repository = new CountingRepository();

        ProductResponse decorated = decorator(repository.returning(List.of()))
                .decorate(product(UUID.randomUUID(), "Linen shirt"));

        assertThat(decorated.images()).isEmpty();
        assertThat(repository.batchCalls.get()).isZero();
    }

    @Test
    void anEmptyPageIsNotQueriedFor() {
        TenantContext.setCurrentTenant(TENANT);
        CountingRepository repository = new CountingRepository();

        decorator(repository.returning(List.of()))
                .decorate(new PageImpl<ProductResponse>(List.of(), PageRequest.of(0, 24), 0));

        assertThat(repository.batchCalls.get()).isZero();
    }
}
