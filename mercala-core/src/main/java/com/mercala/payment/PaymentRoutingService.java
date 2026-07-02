package com.mercala.payment;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mercala.identity.Tenant;
import com.mercala.identity.TenantRepository;
import com.mercala.platform.multitenancy.TenantContext;

@Service
public class PaymentRoutingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRoutingService.class);

    private final Map<String, PaymentProvider> providers;
    private final TenantRepository tenantRepository;
    private final ProviderRefRepository providerRefRepository;

    public PaymentRoutingService(
            Map<String, PaymentProvider> providers,
            TenantRepository tenantRepository,
            ProviderRefRepository providerRefRepository) {
        this.providers = providers;
        this.tenantRepository = tenantRepository;
        this.providerRefRepository = providerRefRepository;
    }

    public PaymentProvider getProvider(String preferredProviderName) {
        UUID tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is required to route payment");
        }

        if (preferredProviderName != null && !preferredProviderName.isBlank()) {
            String cleanName = preferredProviderName.trim().toUpperCase();
            var refOpt = providerRefRepository.findByTenantIdAndProviderAndEnabledTrue(tenantId, cleanName);
            if (refOpt.isPresent()) {
                PaymentProvider provider = getProviderBean(cleanName);
                if (provider != null) {
                    log.info("Routed payment to explicitly requested provider: {} for tenant: {}", cleanName, tenantId);
                    return provider;
                }
            }
        }

        for (String providerName : new String[]{"STRIPE", "PAYSTACK", "FLUTTERWAVE"}) {
            var refOpt = providerRefRepository.findByTenantIdAndProviderAndEnabledTrue(tenantId, providerName);
            if (refOpt.isPresent()) {
                PaymentProvider provider = getProviderBean(providerName);
                if (provider != null) {
                    log.info("Routed payment to configured tenant provider: {} for tenant: {}", providerName, tenantId);
                    return provider;
                }
            }
        }

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        String region = tenant.getRegion();
        String routedProviderName = selectProviderForRegion(region);

        PaymentProvider provider = getProviderBean(routedProviderName);
        if (provider != null) {
            log.info("Routed payment to region-default provider: {} based on region: {} for tenant: {}", routedProviderName, region, tenantId);
            return provider;
        }

        log.warn("Failed to find provider: {}. Falling back to default stripePaymentProvider.", routedProviderName);
        return providers.get("stripePaymentProvider");
    }

    private String selectProviderForRegion(String region) {
        if (region == null) {
            return "STRIPE";
        }
        return switch (region.trim().toUpperCase()) {
            case "NG" -> "PAYSTACK";
            case "KE" -> "FLUTTERWAVE";
            default -> "STRIPE";
        };
    }

    private PaymentProvider getProviderBean(String providerName) {
        if (providerName == null) return null;
        return switch (providerName.toUpperCase()) {
            case "STRIPE" -> providers.get("stripePaymentProvider");
            case "PAYSTACK" -> providers.get("paystackPaymentProvider");
            case "FLUTTERWAVE" -> providers.get("flutterwavePaymentProvider");
            default -> null;
        };
    }
}
