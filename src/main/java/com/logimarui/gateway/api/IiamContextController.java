package com.logimarui.gateway.api;

import com.logimarui.gateway.core.port.ManagedServiceProvider;
import com.logimarui.platform.security.iiam.*;
import com.logimarui.platform.security.principal.IiamPrincipal;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
public class IiamContextController {
    private final IiamPdpClient pdp;
    private final ManagedServiceProvider services;
    private final IiamBrowserAccess browser;
    public record Capability(String permission,String resourceType,String resourceId,boolean allowed) {}

    @GetMapping("/me")
    public ResponseEntity<Map<String,Object>> me(@AuthenticationPrincipal IiamPrincipal actor,HttpServletRequest request) {
        String token=token(request);
        var identity=pdp.currentIdentity(token,actor);
        List<Capability> capabilities=new ArrayList<>();
        for (String permission:List.of(CorePermissionCatalog.SERVICES_READ,CorePermissionCatalog.SERVICES_MANAGE))
            capabilities.add(capability(token,actor,permission,"GATEWAY_SERVER","local"));
        capabilities.add(capability(token,actor,CorePermissionCatalog.RECEIVABLES_READ,"CORE_MODULE","receivables"));
        for (String module:List.of("customerlabel","journey","memberships","occurrence","operationalload","replenishment","vehicles"))
            capabilities.add(capability(token,actor,CorePermissionCatalog.MODULES_USE,"CORE_MODULE",module));
        services.findAll().stream().filter(s -> s.isEnabled() && s.isRequiresAuthentication() && IiamBrowserAccess.PATHS.containsKey(s.getId()))
                .forEach(service -> capabilities.add(new Capability(CorePermissionCatalog.SERVICES_USE,"GATEWAY_SERVICE",service.getId(),true)));
        var authorities=capabilities.stream().filter(Capability::allowed).map(Capability::permission).distinct().toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "userId",actor.identityId(),"identityId",actor.identityId(),"sessionId",actor.sessionId(),
                "userName",identity.path("nickname").asText(),"identity",identity,"authorities",authorities,
                "capabilities",capabilities,"sessionActive",true,
                "expiresInSeconds",Math.max(0,Duration.between(Instant.now(),actor.expiresAt()).getSeconds())));
    }
    @PostMapping("/browser-context")
    public ResponseEntity<Void> establish(@AuthenticationPrincipal IiamPrincipal actor,HttpServletRequest request,HttpServletResponse response) {
        if (!request.isSecure()) return ResponseEntity.status(403).build();
        String token=token(request);pdp.currentIdentity(token,actor);
        Set<String> allowed=new HashSet<>();
        services.findAll().stream().filter(s -> s.isEnabled() && s.isRequiresAuthentication() && IiamBrowserAccess.PATHS.containsKey(s.getId()))
                .forEach(service -> allowed.add(service.getId()));
        browser.write(response,token,actor.expiresAt(),allowed);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @DeleteMapping("/browser-context")
    public ResponseEntity<Void> clear(HttpServletResponse response) {
        browser.clear(response);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private Capability capability(String token,IiamPrincipal actor,String permission,String type,String resource) {
        return new Capability(permission,type,resource,pdp.decide(token,actor,permission,type,resource));
    }
    private String token(HttpServletRequest request) {
        String header=request.getHeader(HttpHeaders.AUTHORIZATION);
        if(header==null || !header.startsWith("Bearer "))throw new IiamPdpClient.ActorInvalid();
        return header.substring(7);
    }
    @ExceptionHandler(IiamPdpClient.ActorInvalid.class)
    ResponseEntity<Map<String,Object>> invalid() {return ResponseEntity.status(401).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore()).body(Map.of("type","about:blank","status",401,"code","AUTHENTICATION_REQUIRED"));}
    @ExceptionHandler(IiamPdpClient.Unavailable.class)
    ResponseEntity<Map<String,Object>> unavailable() {return ResponseEntity.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON).cacheControl(CacheControl.noStore()).body(Map.of("type","about:blank","status",503,"code","IIAM_UNAVAILABLE"));}
}
