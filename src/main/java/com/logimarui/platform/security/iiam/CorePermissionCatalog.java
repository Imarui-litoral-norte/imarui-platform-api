package com.logimarui.platform.security.iiam;

import java.util.List;

/** Technical ownership stays in Core; grants, roles and DENY belong exclusively to IIAM. */
public final class CorePermissionCatalog {
    public static final String OWNER = "logimarui-core";
    public static final String SERVICES_READ = OWNER + ".services.read";
    public static final String SERVICES_MANAGE = OWNER + ".services.manage";
    public static final String SERVICES_USE = OWNER + ".services.use";
    public static final String MODULES_USE = OWNER + ".modules.use";
    public static final String RECEIVABLES_READ = OWNER + ".receivables.read";
    public record Entry(String code, String description, String module, boolean deprecated) {}
    public record Manifest(String owner, String namespace, long version, List<Entry> permissions) {}
    public static Manifest manifest() {
        return new Manifest(OWNER, OWNER, 1, List.of(
                new Entry(SERVICES_READ, "Consultar supervisor e disponibilidade dos serviços", "gateway", false),
                new Entry(SERVICES_MANAGE, "Iniciar, parar e reiniciar serviços do servidor", "gateway", false),
                new Entry(SERVICES_USE, "Utilizar serviço protegido publicado pelo gateway", "gateway", false),
                new Entry(MODULES_USE, "Utilizar módulo de negócio ainda hospedado no Core", "core", false),
                new Entry(RECEIVABLES_READ, "Consultar carteira e pagamentos de recebíveis", "receivables", false)));
    }
    public static boolean owns(String permission) {
        return manifest().permissions().stream().anyMatch(entry -> entry.code().equals(permission));
    }
    private CorePermissionCatalog() {}
}
