package com.anvicorp.api.auth;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> SKIP_PATHS = Set.of(
            "/health",
            "/auth/register",
            "/auth/login",
            "/auth/forgot-password",
            "/auth/reset-password",
            "/auth/refresh"
    );

    /**
     * Paths reachable by an authenticated but email-unverified user.
     * Anything NOT on this list plus its prefix-list twin gets a 403
     * {@code EMAIL_UNVERIFIED} short-circuit. Load-bearing: dropping
     * {@code /auth/me} here breaks the frontend's ability to detect the
     * unverified state on load; dropping {@code /auth/verify-email} or
     * {@code /auth/resend-verification} locks users out of ever
     * finishing signup. Keep this list tight and audit before extending.
     *
     * <p>Endpoints in {@link #SKIP_PATHS} above bypass the filter
     * entirely — they never trigger the check — so
     * {@code /auth/register}, {@code /auth/login}, {@code /auth/refresh},
     * and the password-reset pair are implicitly allowed without needing
     * an entry here.</p>
     */
    private static final Set<String> UNVERIFIED_ALLOW_PATHS = Set.of(
            "/auth/me",
            "/auth/verify-email",
            "/auth/resend-verification",
            "/error"
    );

    /**
     * Path prefixes reachable while unverified. Currently only the staff
     * activation flow — {@code /auth/activate} and
     * {@code /auth/activate/validate} — since an invited staff user
     * redeeming their activation link may not yet have any prior
     * verification state on their new row.
     */
    private static final List<String> UNVERIFIED_ALLOW_PREFIXES = List.of(
            "/auth/activate"
    );

    /** Request attribute the SessionController reads to flag is_current rows. */
    public static final String CURRENT_SESSION_ID_ATTR = "skyzen.currentSessionId";

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SKIP_PATHS.contains(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parseToken(token);
                UUID userId = jwtUtil.extractUserId(claims);
                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent() && !Boolean.FALSE.equals(userOpt.get().getActive())) {
                    // Skip authentication for deactivated users — their old JWTs
                    // still parse but should not grant access. Downstream code
                    // sees no SecurityContext, so @PreAuthorize rejects with 401/403.
                    User user = userOpt.get();

                    // Central email-verification gate. Registration issues a
                    // valid JWT immediately (before verification); without
                    // this check an unverified user could reach any of the
                    // ~95 INTERN-role endpoints simply by presenting their
                    // token. Historically only ApplicationService.apply and
                    // JobPostingService.listOpenForCandidate re-checked the
                    // flag. Enforcing centrally collapses that gap.
                    if (!Boolean.TRUE.equals(user.getEmailVerified())
                            && !isUnverifiedAllowed(request.getServletPath())) {
                        writeEmailUnverifiedResponse(response);
                        return;
                    }

                    List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                            .toList();
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(user, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // Surface the caller's session id (claim is null for
                    // legacy pre-session JWTs — the attribute then stays
                    // absent, and the SessionController treats every row as
                    // non-current).
                    UUID sessionId = jwtUtil.extractSessionId(claims);
                    if (sessionId != null) {
                        request.setAttribute(CURRENT_SESSION_ID_ATTR, sessionId);
                    }
                }
            } catch (Exception ex) {
                log.debug("JWT validation failed: {}", ex.getMessage());
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isUnverifiedAllowed(String path) {
        if (path == null) return true;
        if (UNVERIFIED_ALLOW_PATHS.contains(path)) return true;
        for (String prefix : UNVERIFIED_ALLOW_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Serialise a 403 {@code EMAIL_UNVERIFIED} response in the same shape
     * {@code GlobalExceptionHandler.handleEmailUnverified} uses so the
     * frontend's existing {@code code === 'EMAIL_UNVERIFIED'} branches
     * ({@code ApplyCtaCard}, {@code OpeningsSplit}, {@code apply/page.tsx})
     * pick this up without any client change.
     */
    private static void writeEmailUnverifiedResponse(HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"status\":403,"
                + "\"error\":\"Verify your email to continue\","
                + "\"message\":\"Verify your email to continue\","
                + "\"code\":\"EMAIL_UNVERIFIED\"}";
        response.getWriter().write(body);
    }
}
