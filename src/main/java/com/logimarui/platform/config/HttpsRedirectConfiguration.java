package com.logimarui.platform.config;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keep the existing intranet HTTP entry point while requiring HTTPS for authentication. */
@Configuration
@ConditionalOnProperty(name = "app.server.http-redirect-enabled", havingValue = "true")
public class HttpsRedirectConfiguration {
    @Bean
    TomcatServletWebServerFactory httpsServer(
            @Value("${app.server.http-redirect-port:80}") int httpPort,
            @Value("${server.port}") int httpsPort,
            @Value("${server.ssl.enabled:false}") boolean sslEnabled) {
        if (!sslEnabled || httpPort == httpsPort) {
            throw new IllegalStateException("HTTP redirect requires a distinct HTTPS listener");
        }
        var factory = new TomcatServletWebServerFactory();
        var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(httpPort);
        connector.setRedirectPort(httpsPort);
        factory.addAdditionalTomcatConnectors(connector);
        factory.addContextCustomizers(context -> {
            var constraint = new SecurityConstraint();
            constraint.setUserConstraint("CONFIDENTIAL");
            var collection = new SecurityCollection();
            collection.addPattern("/*");
            constraint.addCollection(collection);
            context.addConstraint(constraint);
        });
        return factory;
    }
}
