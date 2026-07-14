package com.anvicorp.api.controller;

import com.anvicorp.api.entity.SupportTicket;
import com.anvicorp.api.entity.SupportTicketReply;
import com.anvicorp.api.entity.User;
import com.anvicorp.api.enums.UserRole;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ForbiddenException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.notification.UserNotificationDispatcher;
import com.anvicorp.api.repository.SupportTicketReplyRepository;
import com.anvicorp.api.repository.SupportTicketRepository;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 7 support tickets. Any authenticated user can open a ticket;
 * ERM / SUPER_ADMIN can triage, reply (with optional internal-only
 * notes), and change status. Internal-only replies are stripped for the
 * opener at the controller layer.
 */
@RestController
@RequestMapping("/api/v1/support")
@RequiredArgsConstructor
public class SupportTicketController {

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "TECHNICAL", "ACCOUNT", "ONBOARDING", "PAYROLL", "OTHER");
    private static final Set<String> VALID_PRIORITIES = Set.of(
            "LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> VALID_STATUSES = Set.of(
            "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketReplyRepository replyRepository;
    private final UserRepository userRepository;
    private final UserNotificationDispatcher notificationDispatcher;

    @PostMapping("/tickets")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> openTicket(@RequestBody Map<String, String> body,
                                           @AuthenticationPrincipal User caller) {
        String subject = req(body, "subject");
        String content = req(body, "body");
        String category = req(body, "category");
        if (subject.length() < 5 || subject.length() > 200) {
            throw new BadRequestException("subject must be 5-200 characters");
        }
        if (content.length() < 30 || content.length() > 5000) {
            throw new BadRequestException("body must be 30-5000 characters");
        }
        if (!VALID_CATEGORIES.contains(category)) {
            throw new BadRequestException("category must be one of " + VALID_CATEGORIES);
        }
        String priority = body.getOrDefault("priority", "NORMAL");
        if (!VALID_PRIORITIES.contains(priority)) priority = "NORMAL";

        SupportTicket ticket = SupportTicket.builder()
                .openerUserId(caller.getId())
                .subject(subject.trim())
                .body(content.trim())
                .category(category)
                .priority(priority)
                .status("OPEN")
                .build();
        ticket = ticketRepository.save(ticket);

        // Notify every ERM (broadcast) + SUPER_ADMIN. Phase 7 fallback when
        // no ERM is specifically assigned to the opener.
        notifyStaffOfNewTicket(ticket, caller);

        return ticketToDto(ticket, true);
    }

    @GetMapping("/tickets/mine")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> mine(@AuthenticationPrincipal User caller) {
        return ticketRepository.findByOpenerUserIdOrderByUpdatedAtDesc(caller.getId())
                .stream()
                .map(t -> ticketToDto(t, false))
                .toList();
    }

    @GetMapping("/tickets/{id}")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> getOne(@PathVariable UUID id,
                                       @AuthenticationPrincipal User caller) {
        SupportTicket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        boolean staff = isStaff(caller);
        boolean opener = caller.getId().equals(ticket.getOpenerUserId());
        if (!opener && !staff) {
            throw new ForbiddenException("Not allowed to view this ticket");
        }
        List<SupportTicketReply> replies = replyRepository
                .findByTicketIdOrderByCreatedAtAsc(ticket.getId());
        List<Map<String, Object>> repliesDto = replies.stream()
                // Strip internal-only replies for the opener (non-staff).
                .filter(r -> staff || !Boolean.TRUE.equals(r.getInternalOnly()))
                .map(r -> replyToDto(r, staff))
                .toList();
        Map<String, Object> resp = ticketToDto(ticket, staff);
        resp.put("replies", repliesDto);
        return resp;
    }

    @PostMapping("/tickets/{id}/reply")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> reply(@PathVariable UUID id,
                                      @RequestBody Map<String, Object> body,
                                      @AuthenticationPrincipal User caller) {
        SupportTicket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        boolean staff = isStaff(caller);
        boolean opener = caller.getId().equals(ticket.getOpenerUserId());
        if (!opener && !staff) {
            throw new ForbiddenException("Not allowed to reply to this ticket");
        }
        String content = body == null ? null : (String) body.get("body");
        if (content == null || content.trim().length() < 5 || content.length() > 5000) {
            throw new BadRequestException("body must be 5-5000 characters");
        }
        // internal_only=true is staff-only; opener attempts are silently forced false.
        boolean internalOnly = staff && Boolean.TRUE.equals(body.get("internalOnly"));
        SupportTicketReply r = SupportTicketReply.builder()
                .ticketId(ticket.getId())
                .authorUserId(caller.getId())
                .body(content.trim())
                .internalOnly(internalOnly)
                .build();
        r = replyRepository.save(r);
        ticket.setUpdatedAt(Instant.now());
        ticketRepository.save(ticket);

        // Notify the other side (skip for internal-only).
        if (!internalOnly) {
            UUID notifyRecipient = staff ? ticket.getOpenerUserId() : null;
            if (notifyRecipient != null) {
                notificationDispatcher.dispatch(
                        notifyRecipient,
                        "SUPPORT_TICKET_RESPONDED",
                        ticket.getOpenerUserId(),
                        "Support replied to your ticket",
                        "Your ticket \"" + ticket.getSubject() + "\" has a new reply.",
                        "/careers/intern/help/tickets/" + ticket.getId(),
                        false);
            } else {
                notifyStaffOfNewReply(ticket, caller);
            }
        }
        return replyToDto(r, staff);
    }

    @PostMapping("/tickets/{id}/status")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> status(@PathVariable UUID id,
                                       @RequestBody Map<String, String> body,
                                       @AuthenticationPrincipal User actor) {
        SupportTicket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + id));
        String status = req(body, "status");
        if (!VALID_STATUSES.contains(status)) {
            throw new BadRequestException("status must be one of " + VALID_STATUSES);
        }
        ticket.setStatus(status);
        if ("RESOLVED".equals(status) || "CLOSED".equals(status)) {
            if (ticket.getResolvedAt() == null) ticket.setResolvedAt(Instant.now());
        }
        if (actor != null && ticket.getAssignedToId() == null) {
            ticket.setAssignedToId(actor.getId());
        }
        return ticketToDto(ticketRepository.save(ticket), true);
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAnyRole('ERM', 'SUPER_ADMIN')")
    public Map<String, Object> queue(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "25") int size) {
        Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<SupportTicket> p = ticketRepository.findByStatusInOrderByCreatedAtDesc(
                List.of("OPEN", "IN_PROGRESS"), pageable);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("content", p.getContent().stream()
                .map(t -> ticketToDto(t, true)).toList());
        resp.put("totalElements", p.getTotalElements());
        resp.put("totalPages", p.getTotalPages());
        resp.put("page", p.getNumber());
        resp.put("size", p.getSize());
        return resp;
    }

    private boolean isStaff(User caller) {
        return caller != null && (
                caller.getRoles().contains(UserRole.ERM)
                || caller.getRoles().contains(UserRole.SUPER_ADMIN));
    }

    private void notifyStaffOfNewTicket(SupportTicket ticket, User opener) {
        // Fan out to all ERMs + SUPER_ADMINs.
        List<User> staff = userRepository.findByRole(UserRole.ERM);
        for (User u : staff) {
            notificationDispatcher.dispatch(u.getId(), "SUPPORT_TICKET_OPENED",
                    opener.getId(),
                    "New support ticket: " + ticket.getSubject(),
                    "Opened by " + (opener.getFullName() != null ? opener.getFullName() : opener.getEmail())
                            + " (" + ticket.getCategory() + ", " + ticket.getPriority() + ")",
                    "/careers/admin/support",
                    false);
        }
    }

    private void notifyStaffOfNewReply(SupportTicket ticket, User opener) {
        UUID assignee = ticket.getAssignedToId();
        if (assignee != null) {
            notificationDispatcher.dispatch(assignee, "SUPPORT_TICKET_RESPONDED",
                    opener.getId(),
                    "New message on ticket: " + ticket.getSubject(),
                    "The opener has replied to ticket #" + ticket.getId().toString().substring(0, 8),
                    "/careers/admin/support",
                    false);
        }
    }

    private Map<String, Object> ticketToDto(SupportTicket t, boolean staffView) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("openerUserId", t.getOpenerUserId());
        m.put("subject", t.getSubject());
        m.put("body", t.getBody());
        m.put("category", t.getCategory());
        m.put("priority", t.getPriority());
        m.put("status", t.getStatus());
        if (staffView) {
            m.put("assignedToId", t.getAssignedToId());
        }
        m.put("resolvedAt", t.getResolvedAt());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    private Map<String, Object> replyToDto(SupportTicketReply r, boolean staffView) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("authorUserId", r.getAuthorUserId());
        m.put("body", r.getBody());
        if (staffView) m.put("internalOnly", r.getInternalOnly());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private static String req(Map<String, String> body, String key) {
        String v = body == null ? null : body.get(key);
        if (v == null || v.isBlank()) {
            throw new BadRequestException(key + " is required");
        }
        return v;
    }
}
