package com.anvicorp.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anvicorp.api.dto.nav.CandidateNavResponse;
import com.anvicorp.api.dto.nav.NavBadgeResponse;
import com.anvicorp.api.dto.nav.NavItemResponse;
import com.anvicorp.api.entity.Candidate;
import com.anvicorp.api.entity.Engagement;
import com.anvicorp.api.entity.Interview;
import com.anvicorp.api.entity.Offer;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.EngagementStatus;
import com.anvicorp.api.enums.InterviewStatus;
import com.anvicorp.api.enums.OfferStatus;
import com.anvicorp.api.enums.OnboardingTaskStatus;
import com.anvicorp.api.enums.WorkAuthTrack;
import com.anvicorp.api.repository.CandidateRepository;
import com.anvicorp.api.repository.EngagementRepository;
import com.anvicorp.api.repository.InterviewRepository;
import com.anvicorp.api.repository.OfferRepository;
import com.anvicorp.api.repository.OnboardingTaskRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Backend-driven sidebar for the APPLICANT / INTERN face.
 *
 * <h2>Principle</h2>
 * Items only <i>unlock</i>. Once an item appears in a user's nav, it stays
 * visible — past interviews, decided offers, and stale applications all hold
 * history the user can want to reference later. This service computes which
 * items are visible right now; the frontend renders them in order.
 *
 * <h2>Baseline (always visible)</h2>
 * Dashboard, Open Internships, My Applications, Profile, Help.
 *
 * <h2>State-unlocked</h2>
 * <ul>
 *   <li>Interviews — when the user has ≥1 interview row, any status.</li>
 *   <li>Offers — when the user has ≥1 offer row, any status.</li>
 *   <li>Onboarding + I-9 (and Training Plan when STEM OPT) — when the user
 *       has an engagement, any status.</li>
 * </ul>
 *
 * <h2>Intern face</h2>
 * When the user has an ACTIVE engagement: Weekly Materials, Weekly Reports,
 * Timesheets ("My Work"), Projects, Evaluations.
 *
 * <h2>Grouping</h2>
 * INTERN: two groups — PRIMARY (intern items) and HISTORY (Open Internships,
 * My Applications, Interviews, Offers, all still accessible).
 * APPLICANT: single ungrouped list.
 *
 * <h2>Counts</h2>
 * Only when meaningful: an upcoming SCHEDULED interview, an undecided (SENT)
 * offer, a PENDING onboarding task. Items without a meaningful count carry
 * no badge.
 *
 * <h2>"New" badge</h2>
 * State-unlocked items show a "new" badge until the user opens them. The seen
 * list lives on {@code User.seenNavItemsJson} (JSON-encoded set of keys).
 * Baseline items never get a "new" badge.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CandidateNavService {

    /** Keys NEVER eligible for the "new" badge — they're baseline. */
    private static final Set<String> BASELINE_KEYS = Set.of(
            "dashboard", "openings", "applications", "profile", "help");

    /** Keys grouped under "Hiring history" once the user is an INTERN. */
    private static final Set<String> HISTORY_KEYS = Set.of(
            "openings", "applications", "interviews", "offers");

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final UserRepository userRepository;
    private final CandidateRepository candidateRepository;
    private final InterviewRepository interviewRepository;
    private final OfferRepository offerRepository;
    private final EngagementRepository engagementRepository;
    private final OnboardingTaskRepository onboardingTaskRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public CandidateNavResponse build(User caller) {
        if (caller == null) {
            return CandidateNavResponse.builder()
                    .items(Collections.emptyList())
                    .intern(false)
                    .build();
        }

        Candidate candidate = candidateRepository.findByUserId(caller.getId()).orElse(null);
        Set<String> seen = parseSeen(caller.getSeenNavItemsJson());

        // ── State signals ───────────────────────────────────────────────────
        List<Interview> interviews = candidate != null
                ? interviewRepository.findAllForCandidateUser(caller.getId())
                : Collections.emptyList();
        List<Offer> offers = offerRepository.findByCandidateUserIdWithGraph(caller.getId());
        List<Engagement> engagements = candidate != null
                ? engagementRepository.findByCandidateId(candidate.getId())
                : Collections.emptyList();
        boolean hasEngagement = !engagements.isEmpty();
        // An engagement that has passed the activation gate (READY_TO_START
        // or ACTIVE) flips the candidate's nav into the intern face — same
        // signal the candidate-dashboard journey bar uses for the "Hired"
        // stage (see CandidateDashboardService.resolveCurrentStageKey).
        boolean intern = hasEngagement && engagements.stream()
                .anyMatch(e -> e.getStatus() == EngagementStatus.ACTIVE
                        || e.getStatus() == EngagementStatus.READY_TO_START);

        // STEM OPT — engagement.track wins (canonical); fall back to
        // candidate.expectedTrack for pre-engagement candidates who declared
        // STEM_OPT during registration.
        boolean isStemOpt = engagements.stream()
                .anyMatch(e -> e.getTrack() == WorkAuthTrack.STEM_OPT)
                || (candidate != null && candidate.getExpectedTrack() == WorkAuthTrack.STEM_OPT);

        // ── Counts (only when meaningful) ──────────────────────────────────
        Instant now = Instant.now();
        int upcomingInterviewCount = (int) interviews.stream()
                .filter(i -> i.getStatus() == InterviewStatus.SCHEDULED
                        && i.getScheduledAt() != null
                        && !i.getScheduledAt().isBefore(now))
                .count();
        int undecidedOfferCount = (int) offers.stream()
                .filter(o -> o.getStatus() == OfferStatus.SENT)
                .count();
        long pendingTaskCount = candidate != null
                ? onboardingTaskRepository.countByCandidateIdAndStatus(
                        candidate.getId(), OnboardingTaskStatus.PENDING)
                : 0L;

        // ── Build the ordered list ─────────────────────────────────────────
        List<NavItemResponse> items = new ArrayList<>();

        // Primary group — appears before history.
        items.add(item("dashboard", "Dashboard",
                "/careers/intern", group(intern, "primary"), null, seen));
        items.add(item("openings", "Open Internships",
                "/careers/openings", group(intern, "history"), null, seen));
        items.add(item("applications", "My Applications",
                "/careers/intern/applications", group(intern, "history"), null, seen));

        if (!interviews.isEmpty()) {
            items.add(item("interviews", "Interviews",
                    "/careers/intern/interviews", group(intern, "history"),
                    countBadge(upcomingInterviewCount), seen));
        }
        if (!offers.isEmpty()) {
            items.add(item("offers", "Offers",
                    "/careers/intern/offers", group(intern, "history"),
                    countBadge(undecidedOfferCount), seen));
        }

        // Engagement-gated cluster — onboarding + compliance.
        if (hasEngagement) {
            items.add(item("onboarding", "Onboarding",
                    "/careers/intern/documents", group(intern, "primary"),
                    countBadge((int) pendingTaskCount), seen));
            items.add(item("i9", "I-9 Form",
                    "/careers/intern/i9", group(intern, "primary"), null, seen));
            if (isStemOpt) {
                items.add(item("training-plan", "Training Plan",
                        "/careers/intern/training-plans",
                        group(intern, "primary"), null, seen));
            }
        }

        // INTERN-only cluster — appears only with ACTIVE engagement.
        if (intern) {
            items.add(item("weekly-materials", "Weekly Materials",
                    "/careers/intern/weekly-materials", "primary", null, seen));
            items.add(item("weekly-reports", "Weekly Reports",
                    "/careers/intern/weekly-reports", "primary", null, seen));
            items.add(item("timesheets", "My Work",
                    "/careers/intern/work", "primary", null, seen));
            items.add(item("projects", "Projects",
                    "/careers/intern/projects", "primary", null, seen));
            items.add(item("playground", "Playground",
                    "/careers/playground", "primary", null, seen));
            items.add(item("evaluations", "Evaluations",
                    "/careers/intern/evaluations", "primary", null, seen));
        }

        // Tail — Profile + Sessions + Help always at the bottom.
        items.add(item("profile", "Profile",
                "/careers/intern/profile", group(intern, "primary"), null, seen));
        items.add(item("sessions", "Active sessions",
                "/careers/sessions", group(intern, "primary"), null, seen));
        items.add(item("help", "Help & Support",
                "/careers/help", group(intern, "primary"), null, seen));

        // INTERN face groups items; ensure HISTORY group rows render after
        // PRIMARY group rows (stable order within each group is preserved).
        if (intern) {
            items.sort((a, b) -> groupRank(a.getGroup()) - groupRank(b.getGroup()));
        }

        return CandidateNavResponse.builder()
                .items(items)
                .intern(intern)
                .build();
    }

    /**
     * Records that the user has opened the route for a given nav-item key.
     * Adds the key to {@code User.seenNavItemsJson} so the next nav read
     * suppresses the "new" badge. Unknown keys are accepted (the frontend may
     * call seen on a route it doesn't actually have in nav — harmless).
     */
    @Transactional
    public void markSeen(User caller, String key) {
        if (caller == null || key == null || key.isBlank()) return;
        // Load the persistent row so the update lands in the session even if
        // caller is a detached principal-snapshot.
        User user = userRepository.findById(caller.getId()).orElse(null);
        if (user == null) return;
        Set<String> seen = parseSeen(user.getSeenNavItemsJson());
        if (seen.contains(key)) return;
        seen.add(key);
        try {
            user.setSeenNavItemsJson(objectMapper.writeValueAsString(seen));
            userRepository.save(user);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise seen_nav_items for user {} (non-fatal): {}",
                    user.getId(), e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static NavItemResponse item(String key, String label, String route,
                                        String group, NavBadgeResponse explicit,
                                        Set<String> seen) {
        NavBadgeResponse badge = explicit;
        if (badge == null && !BASELINE_KEYS.contains(key) && !seen.contains(key)) {
            badge = NavBadgeResponse.builder().type("new").build();
        }
        return NavItemResponse.builder()
                .key(key)
                .label(label)
                .route(route)
                .group(group)
                .badge(badge)
                .build();
    }

    /** Count badge only when meaningful (>0). Zero-count items get no badge. */
    private static NavBadgeResponse countBadge(int count) {
        if (count <= 0) return null;
        return NavBadgeResponse.builder().type("count").value(count).build();
    }

    /**
     * Group placement. For APPLICANT (intern=false) all items are flat (group=null).
     * For INTERN: keys in {@link #HISTORY_KEYS} get "history", everything else
     * gets "primary".
     */
    private static String group(boolean intern, String preferred) {
        if (!intern) return null;
        // The caller passes the suggested group; HISTORY_KEYS membership is
        // enforced via the sort step in build().
        return preferred;
    }

    private static int groupRank(String group) {
        if ("history".equals(group)) return 1;
        return 0; // primary or null
    }

    private Set<String> parseSeen(String json) {
        if (json == null || json.isBlank()) return new LinkedHashSet<>();
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST_TYPE);
            return new LinkedHashSet<>(list);
        } catch (JsonProcessingException e) {
            log.warn("Malformed seen_nav_items JSON — treating as empty: {}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }
}
