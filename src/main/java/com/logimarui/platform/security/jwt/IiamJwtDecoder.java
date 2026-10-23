package com.logimarui.platform.security.jwt;

import com.logimarui.gateway.core.application.ServiceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Clock;

/** Lazy JWKS resolution avoids a Core/IIAM startup cycle and follows the runtime port. */
@Component
@RequiredArgsConstructor
public class IiamJwtDecoder implements JwtDecoder {
    private final ServiceRegistry services;
    private final Environment configuration;
    private String endpoint;
    private JwtDecoder consumer;
    @Override public Jwt decode(String encoded) throws JwtException { return consumer().decode(encoded); }
    private synchronized JwtDecoder consumer() {
        String scheme = configuration.getProperty("SERVICE_IIAM_UPSTREAM_SCHEME", "https");
        String url = services.findAll().stream().filter(route -> route.getId().equals("iiam")).findFirst()
                .map(route -> {
                    URI base = URI.create(route.getTargetUri());
                    return scheme + "://" + base.getHost() + ":" + base.getPort() + "/.well-known/jwks.json";
                }).orElseGet(() -> configuration.getProperty("IIAM_JWKS_URI", "https://localhost:4105/.well-known/jwks.json"));
        if (consumer == null || !url.equals(endpoint)) {
            var decoder = NimbusJwtDecoder.withJwkSetUri(url).jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
            decoder.setJwtValidator(new IiamTokenValidator(configuration.getProperty("IIAM_ISSUER", "imarui-iam-service"),
                    configuration.getProperty("IIAM_AUDIENCE", "imarui-iam"), Clock.systemUTC()));
            consumer = decoder; endpoint = url;
        }
        return consumer;
    }
}
