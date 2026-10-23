package com.logimarui.platform.security.principal;
import java.time.Instant;
import java.util.UUID;
public record IiamPrincipal(UUID identityId, UUID sessionId, UUID tokenId, Instant expiresAt) {}
