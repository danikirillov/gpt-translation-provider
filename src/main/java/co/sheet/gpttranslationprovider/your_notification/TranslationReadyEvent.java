package co.sheet.gpttranslationprovider.your_notification;

import co.sheet.gpttranslationprovider.TranslationRequest;

public record TranslationReadyEvent(TranslationRequest translationRequest, String translationResult) {

}
