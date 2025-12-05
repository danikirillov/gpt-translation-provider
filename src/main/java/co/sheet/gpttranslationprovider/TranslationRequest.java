package co.sheet.gpttranslationprovider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TranslationRequest(@NotNull Long orderId,
                                 @NotNull Long masterCopyKeyId,
                                 @NotBlank String value,
                                 @NotBlank String sourceLocale,
                                 @NotBlank String targetLocale,
                                 @NotBlank String userId) {

}
