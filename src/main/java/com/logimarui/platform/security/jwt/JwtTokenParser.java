package com.logimarui.platform.security.jwt;

import com.logimarui.platform.security.principal.IiamPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenParser {
    private final IiamJwtDecoder decoder;
    public IiamPrincipal parseAndValidate(String encoded) {
        var token = decoder.decode(encoded);
        return new IiamPrincipal(UUID.fromString(token.getSubject()), UUID.fromString(token.getClaimAsString("sid")),
                UUID.fromString(token.getId()), token.getExpiresAt());
    }
}
