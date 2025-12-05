package co.sheet.gpttranslationprovider.open_ai;

import co.sheet.gpttranslationprovider.TranslationRequest;

public record ReadyToTranslateEvent(TranslationRequest translationRequest) {

}
