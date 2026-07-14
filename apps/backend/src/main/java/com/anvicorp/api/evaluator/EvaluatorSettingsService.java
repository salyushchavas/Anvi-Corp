package com.anvicorp.api.evaluator;

import com.anvicorp.api.entity.User;
import com.anvicorp.api.exception.BadRequestException;
import com.anvicorp.api.exception.ResourceNotFoundException;
import com.anvicorp.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** Evaluator Phase 4 — per-Evaluator preferences. Storage is on the
 *  {@code users} table via the {@code prefs_evaluator_*} columns added in
 *  this phase; falls back to safe defaults for legacy rows. */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvaluatorSettingsService {

    private static final Set<String> ALLOWED_FREQ = Set.of("DAILY", "WEEKLY", "NEVER");

    private final UserRepository userRepo;

    @Transactional(readOnly = true)
    public EvaluatorPhase4Dtos.EvaluatorSettings get(User caller) {
        User u = userRepo.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new EvaluatorPhase4Dtos.EvaluatorSettings(
                u.getPrefsEvaluatorDefaultDuration(),
                u.getPrefsEvaluatorReminderFrequency(),
                u.getPrefsEvaluatorNotifyAcknowledged(),
                u.getPrefsEvaluatorNotifyDsoWindow(),
                u.getPrefsReminders(),
                u.getPrefsEngagementUpdates(),
                u.getFullName(),
                u.getEmail(),
                u.getZoomEmail());
    }

    @Transactional
    public EvaluatorPhase4Dtos.EvaluatorSettings update(
            EvaluatorPhase4Dtos.SettingsUpdateRequest req, User caller) {
        if (req == null) throw new BadRequestException("request body is required");
        User u = userRepo.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (req.defaultDurationMinutes() != null) {
            short d = req.defaultDurationMinutes();
            if (d < 15 || d > 180) {
                throw new BadRequestException("defaultDurationMinutes must be 15-180");
            }
            u.setPrefsEvaluatorDefaultDuration(d);
        }
        if (req.reminderFrequency() != null) {
            String f = req.reminderFrequency();
            if (!ALLOWED_FREQ.contains(f)) {
                throw new BadRequestException("reminderFrequency must be DAILY | WEEKLY | NEVER");
            }
            u.setPrefsEvaluatorReminderFrequency(f);
        }
        if (req.notifyAcknowledged() != null) {
            u.setPrefsEvaluatorNotifyAcknowledged(req.notifyAcknowledged());
        }
        if (req.notifyDsoWindow() != null) {
            u.setPrefsEvaluatorNotifyDsoWindow(req.notifyDsoWindow());
        }
        if (req.prefsRemindersEmail() != null) {
            u.setPrefsReminders(req.prefsRemindersEmail());
        }
        if (req.prefsEngagementUpdatesEmail() != null) {
            u.setPrefsEngagementUpdates(req.prefsEngagementUpdatesEmail());
        }
        userRepo.save(u);
        return get(caller);
    }
}
