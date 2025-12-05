package co.sheet.gpttranslationprovider.event_management;

import co.sheet.gpttranslationprovider.TranslationRequest;
import co.sheet.gpttranslationprovider.your_notification.TranslationReadyEvent;
import co.sheet.gpttranslationprovider.open_ai.ReadyToTranslateEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class FailedEventsResubmission {

    final FailedEventsService service;

    /**
     * Resubmit failed events every day at 18:30.
     */
    @Scheduled(cron = "0 30 18 * * *")
    void resubmitFailedEvents() {
        service.resubmitFailedEvents();
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class FailedEventsService {

    static final String LOCK_NAME = "event_resubmit";

    final IncompleteEventPublications incompleteEvents;
    final MultiInstanceLockRepository lockRepository;

    @Transactional
    void resubmitFailedEvents() {
        var lastExecution = lockRepository.findLastExecutionWithLock(LOCK_NAME);
        final var now = Instant.now();

        if (lastExecution == null || lastExecution.isAfter(now.minus(Duration.ofHours(23)))) {
            log.info("Resubmission for failed events executed recently in another instance. Last execution: {}", lastExecution);
            return;
        }

        log.info("Starting resubmission for failed events. Last execution: {}", lastExecution);
        lockRepository.updateLastExecution(LOCK_NAME, now);

        try {
            incompleteEvents.resubmitIncompletePublicationsOlderThan(Duration.ofHours(1));
            log.info("Resubmission completed successfully");
        } catch (Exception e) {
            log.error("Failed to resubmit failed events, will retry tomorrow", e);
        }
    }

    @ApplicationModuleListener
    void resubmitByOrderId(RetryEvent retryEvent) {
        var orderToRefetch = retryEvent.orderId();
        log.info("Resubmitting failed events for orderId: {}", orderToRefetch);

        Predicate<EventPublication> withOrderId = eventPublication -> {
            var event = eventPublication.getEvent();
            return switch (event) {
                case ReadyToTranslateEvent(TranslationRequest request) -> orderToRefetch.equals(request.orderId());
                case TranslationReadyEvent(TranslationRequest request, String r) -> orderToRefetch.equals(request.orderId());
                case RetryEvent e -> false; // no need in this case
                default -> throw new IllegalStateException("Unexpected value: " + event);
            };
        };

        incompleteEvents.resubmitIncompletePublications(event -> !event.isCompleted() && withOrderId.test(event));
    }
}