package com.mercala.identity.web.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {}
