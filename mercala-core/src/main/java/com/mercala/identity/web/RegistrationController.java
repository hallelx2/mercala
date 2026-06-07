package com.mercala.identity.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mercala.identity.AppUser;
import com.mercala.identity.Tenant;
import com.mercala.identity.service.RegistrationService;
import com.mercala.identity.web.dto.CreateTenantRequest;
import com.mercala.identity.web.dto.CreateUserRequest;
import com.mercala.identity.web.dto.TenantResponse;
import com.mercala.identity.web.dto.UserResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tenants")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = registrationService.createTenant(request);
        return new TenantResponse(tenant.getId(), tenant.getSlug(), tenant.getName(), tenant.getStatus().name());
    }

    @PostMapping("/{slug}/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse addUser(@PathVariable String slug, @Valid @RequestBody CreateUserRequest request) {
        AppUser user = registrationService.addUser(slug, request);
        return new UserResponse(user.getId(), user.getEmail(), user.getRole());
    }
}
