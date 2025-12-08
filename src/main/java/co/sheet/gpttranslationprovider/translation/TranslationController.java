package co.sheet.gpttranslationprovider.translation;

import co.sheet.gpttranslationprovider.TranslationRequest;
import co.sheet.gpttranslationprovider.event_management.RetryEvent;
import co.sheet.gpttranslationprovider.open_ai.ReadyToTranslateEvent;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "GptTranslationProvider", description = "Operations to translate a text into a target language using GPT")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
class TranslationController {

    final ApplicationEventPublisher publisher;

    @PostMapping("/translate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    void translate(@RequestBody @Valid TranslationRequest request) {
        publisher.publishEvent(new ReadyToTranslateEvent(request));
    }

    @PostMapping("/translateAll")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    void translateAll(@RequestBody @Valid @NotNull List<TranslationRequest> requests) {
        for (var request : requests) {
            publisher.publishEvent(new ReadyToTranslateEvent(request));
        }
    }

    @PostMapping("/refetchTranslations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    void refetchTranslations(@RequestBody @Valid RefetchTranslationsRequest refetchRequest) {
        var orderToRefetch = refetchRequest.orderId();
        publisher.publishEvent(new RetryEvent(orderToRefetch));
    }
}

record RefetchTranslationsRequest(@NotNull Long orderId) {

}
