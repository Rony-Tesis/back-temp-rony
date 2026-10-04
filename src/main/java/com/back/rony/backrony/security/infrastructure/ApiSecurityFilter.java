package com.back.rony.backrony.security.infrastructure;

import com.back.rony.backrony.security.application.AccessPolicy;
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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Same-origin writes, optional token authentication, trusted hosts and no-cache headers. */
@Component
public final class ApiSecurityFilter extends OncePerRequestFilter {
    private final AccessPolicy access;
    private final RonyProperties properties;
    private final ObjectMapper mapper;

    public ApiSecurityFilter(AccessPolicy access, RonyProperties properties, ObjectMapper mapper) {
        this.access = access;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store");
        boolean docs =
                request.getRequestURI().startsWith("/swagger-ui")
                        || request.getRequestURI().startsWith("/api/docs");
        response.setHeader(
                "Content-Security-Policy",
                docs
                        ? "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; object-src 'none'"
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
                if (!java.util.Objects.equals(actual.getScheme(), expected.getScheme())
                        || !java.util.Objects.equals(
                                actual.getRawAuthority(), expected.getRawAuthority())) {
                    reject(response, 403, "Origen no permitido.");
                    return;
                }
            } catch (IllegalArgumentException e) {
                reject(response, 403, "Origen no permitido.");
                return;
            }
        }
        String path = request.getRequestURI();
        boolean protectedPath = path.startsWith("/api/") || path.startsWith("/swagger-ui");
        if (protectedPath && !path.equals("/api/v1/session") && access.required()) {
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

    private void reject(HttpServletResponse response, int status, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(java.util.Map.of("detail", detail)));
    }
}
