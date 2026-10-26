package com.logimarui.platform.security.iiam;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Temporary Core access mode; authentication and live IIAM sessions are always required. */
@Component
public class CoreAccessPolicy {
    private final boolean permissionsRequired;

    public CoreAccessPolicy(@Value("${app.security.permissions-required:true}") boolean permissionsRequired) {
        this.permissionsRequired = permissionsRequired;
    }

    public boolean permissionsRequired() {
        return permissionsRequired;
    }
}
