package co.sheet.gpttranslationprovider.open_ai;

import co.sheet.gpttranslationprovider.TranslationRequest;
import co.sheet.gpttranslationprovider.your_notification.TranslationReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

public record ReadyToTranslateEvent(TranslationRequest translationRequest) {

}

@Slf4j
@Component
@RequiredArgsConstructor
class TranslationEventPublisher {

    final ApplicationEventPublisher publisher;

    @Transactional
    void publishTranslationReady(TranslationRequest request, String translationResult) {
        log.debug("Publishing TranslationReadyEvent in transaction for orderId={}", request.orderId());
        publisher.publishEvent(new TranslationReadyEvent(request, translationResult));
    }
}
