package com.back.rony.backrony.security.infrastructure;

import com.back.rony.backrony.security.application.AccessPolicy;
import com.back.rony.backrony.security.application.DeviceAccessPolicy;
import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Same-origin writes, optional token authentication, trusted hosts and no-cache headers. */
@Component
public final class ApiSecurityFilter extends OncePerRequestFilter {
    private final AccessPolicy access;
    private final RonyProperties properties;
    private final ObjectMapper mapper;
    private final DeviceAccessPolicy devices;
    private final URI frontendOrigin;

    public ApiSecurityFilter(
            AccessPolicy access,
            RonyProperties properties,
            ObjectMapper mapper,
            DeviceAccessPolicy devices,
            @Value("${rony.frontend-url:}") String frontendUrl) {
        this.access = access;
        this.properties = properties;
        this.mapper = mapper;
        this.devices = devices;
        this.frontendOrigin = frontendUrl.isBlank() ? null : URI.create(frontendUrl);
        if (frontendOrigin != null
                && (!"https".equals(frontendOrigin.getScheme())
                        || frontendOrigin.getHost() == null
                        || frontendOrigin.getUserInfo() != null
                        || frontendOrigin.getQuery() != null
                        || frontendOrigin.getFragment() != null
                        || !List.of("", "/").contains(frontendOrigin.getPath()))) {
            throw new IllegalArgumentException("La web pública requiere un origen HTTPS válido.");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI();
        boolean portal =
                List.of("/", "/index.html", "/dashboard", "/monitoring", "/classifications")
                        .contains(path);
        boolean docs =
                request.getRequestURI().startsWith("/swagger-ui")
                        || request.getRequestURI().startsWith("/api/docs");
        response.setHeader(
                "Content-Security-Policy",
                docs
                        ? "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; object-src 'none'"
                        : portal
                                ? "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'"
                                : "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'");
        String host = request.getServerName();
        if (!properties.allowedHosts().contains(host)) {
            reject(response, 400, "Host no permitido.");
            return;
        }
        boolean writing = !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        String origin = request.getHeader("Origin");
        if (writing && origin != null) {
            try {
                URI expected = URI.create(request.getScheme() + "://" + request.getHeader("Host"));
                URI actual = URI.create(origin);
                if (!sameOrigin(actual, expected) && !sameOrigin(actual, frontendOrigin)) {
                    reject(response, 403, "Origen no permitido.");
                    return;
                }
            } catch (IllegalArgumentException e) {
                reject(response, 403, "Origen no permitido.");
                return;
            }
        }
        boolean protectedPath = path.startsWith("/api/") || path.startsWith("/swagger-ui");
        boolean devicePath = path.startsWith("/api/v1/device/");
        if (devicePath
                && (origin != null || !devices.matches(request.getHeader("Authorization")))) {
            reject(response, 401, "Acceso del dispositivo no autorizado.");
            return;
        }
        if (!devicePath && protectedPath && !path.equals("/api/v1/session") && access.required()) {
            String credential =
                    request.getCookies() == null
                            ? ""
                            : Arrays.stream(request.getCookies())
                                    .filter(c -> "rony_session".equals(c.getName()))
                                    .map(Cookie::getValue)
                                    .findFirst()
                                    .orElse("");
            String bearer = request.getHeader("Authorization");
            if (bearer != null && bearer.startsWith("Bearer ")) credential = bearer.substring(7);
            if (!access.matches(credential)) {
                reject(response, 401, "Inicia sesión para acceder a la cámara.");
                return;
            }
        }
        if (writing
                && path.startsWith("/api/v1/")
                && !"1".equals(request.getHeader("X-Rony-Control"))) {
            reject(response, 403, "Falta la cabecera de control.");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean sameOrigin(URI actual, URI expected) {
        return expected != null
                && java.util.Objects.equals(actual.getScheme(), expected.getScheme())
                && java.util.Objects.equals(actual.getRawAuthority(), expected.getRawAuthority())
                && actual.getUserInfo() == null
                && actual.getQuery() == null
                && actual.getFragment() == null
                && List.of("", "/").contains(actual.getPath());
    }

    private void reject(HttpServletResponse response, int status, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(java.util.Map.of("detail", detail)));
    }
}
