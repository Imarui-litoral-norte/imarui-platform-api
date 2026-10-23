package com.logimarui.platform.security.jwt;

import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.*;
import java.util.UUID;

/** The IIAM V1 consumer contract; no locally issued JWT is accepted. */
public record IiamTokenValidator(String issuer, String audience, Clock clock) implements OAuth2TokenValidator<Jwt> {
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            canonicalUuid(token.getSubject()); canonicalUuid(token.getClaimAsString("sid")); canonicalUuid(token.getId());
            Instant now = clock.instant(), issued = token.getIssuedAt(), expires = token.getExpiresAt();
            if (!"RS256".equals(token.getHeaders().get("alg")) || !"at+jwt".equals(token.getHeaders().get("typ"))
                    || !(token.getHeaders().get("kid") instanceof String kid) || kid.isBlank()
                    || !issuer.equals(token.getClaimAsString("iss")) || !token.getAudience().contains(audience)
                    || issued == null || expires == null || issued.isAfter(now) || !expires.isAfter(now)
                    || !expires.isAfter(issued) || Duration.between(issued, expires).compareTo(Duration.ofSeconds(900)) > 0
                    || token.getNotBefore() != null && token.getNotBefore().isAfter(now)) return invalid();
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException malformed) { return invalid(); }
    }
    private static UUID canonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return parsed;
    }
    private OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid IIAM access token", null));
    }
}
