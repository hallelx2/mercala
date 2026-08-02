package com.mercala.identity.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Picks which of the caller's stores the session should act on (HAL-556). */
public record SwitchStoreRequest(@NotBlank String slug) {}
