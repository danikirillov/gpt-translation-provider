package co.sheet.gpttranslationprovider.your_notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.client.api.YourServiceApi;
import org.openapitools.client.model.TranslationUpdate;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

@Profile("!local")
@Slf4j
@Service
@RequiredArgsConstructor
class YourNotificationService {

    final YourServiceApi yourApiClient;

    @ApplicationModuleListener
    void updateTranslationInYourApi(TranslationReadyEvent event) {
        var translationRequest = event.translationRequest();
        var translationUpdate = new TranslationUpdate()
            .orderId(translationRequest.orderId())
            .masterCopyKeyId(translationRequest.masterCopyKeyId())
            .targetLocale(translationRequest.targetLocale())
            .translationResult(event.translationResult());

        var response = yourApiClient.updateTranslationForKey(translationUpdate);

        var errors = response.getErrorList();
        if (errors != null && !errors.isEmpty()) {
            throw new IllegalStateException(
                "Update translation request failed for " + translationUpdate + " \nErrors returned: " + errors);
        }
        log.info("Update translation request sent to Your api successfully. {}", translationRequest);
    }
}

