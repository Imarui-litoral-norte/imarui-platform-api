package com.logimarui.platform.security.iiam;

import com.logimarui.gateway.core.port.ManagedServiceProvider;
import com.logimarui.gateway.infra.runtime.ManagedServiceIds;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Technical routing to trusted resource bindings, never a local RBAC/DENY evaluator. */
@Component
@RequiredArgsConstructor
public class IiamRequestPolicy {
    public record Decision(String permission, String resourceType, String resourceId) {}
    private final ManagedServiceProvider services;
    private static final Map<String,String> MODULES = Map.ofEntries(
            Map.entry("/api/v1/customers", "customerlabel"), Map.entry("/api/v1/journey", "journey"),
            Map.entry("/api/v1/orders", "occurrence"), Map.entry("/api/v1/return-alerts", "occurrence"),
            Map.entry("/api/v1/invoices", "occurrence"),
            Map.entry("/api/v2/occurrences", "occurrence"), Map.entry("/api/v1/replenishments", "replenishment"),
            Map.entry("/api/v1/memberships", "memberships"), Map.entry("/api/v1/vehicles", "vehicles"),
            Map.entry("/api/v1/operationalload", "operationalload"));

    public Decision resolve(HttpServletRequest request) {
        String path = request.getRequestURI(), method = request.getMethod();
        if (method.equals("OPTIONS") || matches(path,"/api/iam")) return null;
        // Existing standalone upload and n8n inbound callbacks have deliberately independent contracts.
        if (method.equals("PUT") && path.equals("/critica-pedidos/api/mock")) return null;
        for (String callback : List.of("/webhook", "/webhook-test", "/form", "/form-test")) if (matches(path,callback)) return null;
        if (matches(path,"/api/v1/admin/services")) return new Decision(
                Set.of("GET","HEAD").contains(method) ? CorePermissionCatalog.SERVICES_READ : CorePermissionCatalog.SERVICES_MANAGE,
                "GATEWAY_SERVER", "local");
        if (matches(path,"/api/v1/receivables")) return new Decision(
                Set.of("GET","HEAD").contains(method) ? CorePermissionCatalog.RECEIVABLES_READ : "unsupported",
                "CORE_MODULE", "receivables");
        if (path.matches("/api/v1/customers/[^/]+/(orders(?:/.*)?|invoices/.*)"))
            return new Decision(CorePermissionCatalog.MODULES_USE,"CORE_MODULE","occurrence");
        for (var module : MODULES.entrySet()) if (matches(path,module.getKey()))
            return new Decision(CorePermissionCatalog.MODULES_USE,"CORE_MODULE",module.getValue());
        String alias = null;
        if (matches(path,"/gerenciador-database/monitoring")) alias=ManagedServiceIds.DATABASE_MONITORING;
        else if (matches(path,"/gerenciador-database/backup")) alias=ManagedServiceIds.DATABASE_BACKUP;
        else if (path.equals("/gerenciador-database") || path.equals("/gerenciador-database/")) alias=ManagedServiceIds.DATABASE_MONITORING;
        else if (matches(path,"/evolution") || matches(path,"/evolution-api")) alias=ManagedServiceIds.EVOLUTION;
        else if (matches(path,"/n8n") || matches(path,"/rest")) alias=ManagedServiceIds.N8N;
        final String aliasId=alias;
        return services.findAll().stream().filter(service -> !service.getId().equals(ManagedServiceIds.FRONTEND)
                        && !service.getId().equals(ManagedServiceIds.IIAM) && service.isRequiresAuthentication()
                        && (service.getId().equals(aliasId) || matches(path,service.getPathPrefix())))
                .max(Comparator.comparingInt(service -> service.getPathPrefix().length()))
                .map(service -> new Decision(CorePermissionCatalog.SERVICES_USE,"GATEWAY_SERVICE",service.getId()))
                .orElseGet(() -> matches(path,"/api/v1") || matches(path,"/api/v2")
                        ? new Decision("unsupported","GATEWAY_SERVER","local") : null);
    }
    private static boolean matches(String path,String prefix) { return path.equals(prefix) || path.startsWith(prefix+"/"); }
}
