package com.logimarui.platform.web;

import com.logimarui.LogimaruiCoreApiApplication;
import com.logimarui.gateway.api.IiamProxyController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoreRestApiPathContractTest {

    private static final List<String> CORE_API_PREFIXES = List.of("/api/v1", "/api/v2");

    @Test
    void everyCoreRestControllerUsesTheVersionedApiRoot() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllerTypes = scanner
                .findCandidateComponents("com.logimarui")
                .stream()
                .map(BeanDefinition::getBeanClassName)
                .map(this::loadClass)
                .filter(type -> type.getProtectionDomain().getCodeSource().getLocation().equals(
                        LogimaruiCoreApiApplication.class.getProtectionDomain().getCodeSource().getLocation()))
                .toList();

        assertThat(controllerTypes).isNotEmpty();

        for (Class<?> controllerType : controllerTypes) {
            RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(
                    controllerType,
                    RequestMapping.class
            );

            assertThat(requestMapping)
                    .as("@RequestMapping ausente em %s", controllerType.getName())
                    .isNotNull();
            if (controllerType.equals(IiamProxyController.class)) {
                assertThat(requestMapping.value()).containsExactly("/api/iam");
                continue;
            }
            assertThat(requestMapping.value())
                    .as("raiz REST de %s", controllerType.getName())
                    .isNotEmpty()
                    .allMatch(path -> CORE_API_PREFIXES.stream().anyMatch(prefix ->
                            path.equals(prefix) || path.startsWith(prefix + "/")
                    ));
        }
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Controller nao encontrado: " + className, exception);
        }
    }
}
