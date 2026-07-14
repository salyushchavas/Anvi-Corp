package com.anvicorp.api.mail.service;

import com.anvicorp.api.mail.sse.MailDeliveredEvent;
import com.anvicorp.api.mail.sse.MailSseEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-memory, per-account SSE emitter registry for real-time new-mail events (A8).
 *
 * <p>Emitters are held in a {@code ConcurrentHashMap<accountId,
 * CopyOnWriteArraySet<SseEmitter>>}; register/remove mutate the per-account set
 * atomically via {@code compute}/{@code computeIfPresent} so a concurrent
 * connect/disconnect can't orphan or drop an emitter. A self-contained daemon
 * {@link ScheduledExecutorService} heartbeats a {@code ": ping"} comment every
 * ~25s (keeps idle proxies from closing the stream) and prunes any emitter whose
 * write fails; it is shut down in {@link #stop()}. Dead emitters are also pruned
 * on completion/timeout/error and on any {@code send} failure.
 *
 * <p><b>Best-effort + walled.</b> {@link #publishNewMail} fans an event out ONLY
 * to the given account's own streams and never throws out of the delivery path.
 * Delivery publishes a {@link MailDeliveredEvent} inside its transaction;
 * {@link #onMailDelivered} fans it out only AFTER the row commits (visible), so a
 * real-time failure can never break or roll back a send.
 *
 * <p><b>Single-instance limitation:</b> emitters live in THIS JVM only. With more
 * than one backend node a recipient connected to node B won't receive a push for
 * mail delivered on node A; clients resync counts on every (re)connect, so it is
 * eventually consistent, but true multi-node fan-out needs a shared pub/sub
 * (Redis/DB LISTEN-NOTIFY) — out of scope here.
 */
@Service
public class MailSseService {

    private static final Logger log = LoggerFactory.getLogger(MailSseService.class);

    private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mail-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @Value("${app.webmail.sse.timeout-ms:1800000}")
    private long timeoutMs; // 30 min — client reconnects + resyncs counts on timeout
    @Value("${app.webmail.sse.heartbeat-ms:25000}")
    private long heartbeatMs;

    @PostConstruct
    void start() {
        heartbeat.scheduleAtFixedRate(this::beat, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
    }

    /** Register a new stream for the caller's account and return its emitter. */
    public SseEmitter register(UUID accountId) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitters.compute(accountId, (k, set) -> {
            if (set == null) {
                set = new CopyOnWriteArraySet<>();
            }
            set.add(emitter);
            return set;
        });
        emitter.onCompletion(() -> remove(accountId, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(accountId, emitter);
        });
        emitter.onError(e -> remove(accountId, emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            remove(accountId, emitter);
        }
        return emitter;
    }

    /**
     * Fan an event out to the account's OWN live streams (walled). Best-effort —
     * a failed write prunes that emitter and is swallowed. Returns the number of
     * streams that received it (0 if the account has none connected).
     */
    public int publishNewMail(UUID accountId, MailSseEvent event) {
        Set<SseEmitter> set = emitters.get(accountId);
        if (set == null || set.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("mail").data(event, MediaType.APPLICATION_JSON));
                sent++;
            } catch (Exception e) {
                remove(accountId, emitter); // dead client → prune, keep going
            }
        }
        return sent;
    }

    /** Live stream count for an account (test/observability aid). */
    public int connectionCount(UUID accountId) {
        Set<SseEmitter> set = emitters.get(accountId);
        return set == null ? 0 : set.size();
    }

    private void beat() {
        for (Map.Entry<UUID, Set<SseEmitter>> entry : emitters.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void remove(UUID accountId, SseEmitter emitter) {
        // Atomic: drop the emitter and, if the set is now empty, the mapping too —
        // so a concurrent register()'s compute() and this never race an orphan.
        emitters.computeIfPresent(accountId, (k, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
    }

    @PreDestroy
    void stop() {
        heartbeat.shutdownNow();
        for (Set<SseEmitter> set : emitters.values()) {
            for (SseEmitter emitter : set) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // shutting down — nothing to do
                }
            }
        }
        emitters.clear();
    }

    /** Post-commit fan-out of a delivered entry to the recipient's own streams (best-effort). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMailDelivered(MailDeliveredEvent ev) {
        try {
            publishNewMail(ev.recipientId(),
                    MailSseEvent.newMail(ev.folder(), ev.customFolderId(), ev.messageId()));
        } catch (Exception e) {
            log.debug("SSE publish failed (best-effort) for account {}", ev.recipientId(), e);
        }
    }
}
