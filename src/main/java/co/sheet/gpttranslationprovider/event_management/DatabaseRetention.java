package co.sheet.gpttranslationprovider.event_management;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class DatabaseRetention {

    final CleanupService cleanupService;

    /**
     * Clean up old completed events every day at 17:30.
     */
    @Scheduled(cron = "0 30 17 * * *")
    void cleanOldEvents() {
        cleanupService.cleanupOldEvents();
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class CleanupService {

    static final String LOCK_NAME = "event_cleanup";

    final CompletedEventPublications completeEvents;
    final MultiInstanceLockRepository lockRepository;

    @Transactional
    void cleanupOldEvents() {
        var lastExecution = lockRepository.findLastExecutionWithLock(LOCK_NAME);
        final var now = Instant.now();

        if (lastExecution == null || lastExecution.isAfter(now.minus(Duration.ofHours(23)))) {
            log.info("Cleanup executed recently in another instance. Last execution: {}", lastExecution);
            return;
        }

        log.info("Starting cleanup of old events. Last execution: {}", lastExecution);
        lockRepository.updateLastExecution(LOCK_NAME, now);

        try {
            completeEvents.deletePublicationsOlderThan(Duration.ofDays(1));
            log.info("Cleanup completed successfully");
        } catch (Exception e) {
            log.error("Failed to cleanup old events, will retry tomorrow", e);
        }

    }
}
