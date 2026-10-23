package com.logimarui.platform.security.iiam;

import com.logimarui.platform.security.principal.IiamPrincipal;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Protect removal of the legacy authority while the business modules remain in this application. */
class CoreIiamBoundaryTest {
    @Test void legacyPackagesAndTheirCrossModuleImportsStayRemoved() throws Exception {
        Path root=Path.of("src/main/java/com/logimarui");
        for(String legacy:List.of("authentication","authorization","iam","platform/startup/security"))
            assertFalse(Files.exists(root.resolve(legacy)), "Unexpected legacy source package: "+legacy);
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source=Files.readString(file);
                for(String legacy:List.of("authentication","authorization","iam"))
                    assertFalse(source.contains("import com.logimarui."+legacy+"."),"Legacy import in "+file);
            }
        }
    }
    @Test void businessModulesAndUuidPrincipalArePreserved() {
        Path root=Path.of("src/main/java/com/logimarui");
        for(String module:List.of("customerlabel","journey","memberships","occurrence","operationalread","receivables","replenishment","vehicles"))
            assertTrue(Files.isDirectory(root.resolve(module)),"Missing retained business module "+module);
        var fields=IiamPrincipal.class.getRecordComponents();
        assertEquals(UUID.class,fields[0].getType());assertEquals(UUID.class,fields[1].getType());assertEquals(UUID.class,fields[2].getType());
    }
}
