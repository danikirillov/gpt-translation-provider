package co.sheet.gpttranslationprovider.open_ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import co.sheet.gpttranslationprovider.TranslationRequest;
import co.sheet.gpttranslationprovider.your_notification.TranslationReadyEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;

/**
 * These test suite is for manual local testing chat gpt models and prompts customization. To reduce pipeline run time and execution costs
 * these tests are disabled by default. Add Srping profile "openai-test" to your run configuration to enable them.
 */
@Disabled

@Slf4j
@ActiveProfiles("test")
@ApplicationModuleTest
class NaiveOpenAiGenerationTest {


    @Test
    void testTranslationForImageDescription(Scenario scenario) {
        var testOrderId = 1L;
        var testTranslationRequest = new TranslationRequest(
            testOrderId,
            2002L,
            "View larger image: A brown fleece jacket is being presented from a three-quarter front view, featuring a textured, shaggy fleece material. It is presenting a collarless round neckline, an open front revealing a white top with lace details underneath, and long, relaxed sleeves with dark brown binding along its edges.",
            "en-GB",
            "sv-SE",
            "user123"
        );
        var testEvent = new ReadyToTranslateEvent(testTranslationRequest);
        var expectedResult = "Visa större bild: En brun fleecejacka presenteras från en trekvartsvy framifrån, med ett texturerat, raggigt fleece-material. Den har en kraglös rund hals, en öppen framsida som avslöjar en vit topp med spetsdetaljer under, och långa, avslappnade ärmar med mörkbrun kantning längs kanterna.";

        scenario.publish(testEvent)
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .matching(event -> event.translationRequest().orderId().equals(testOrderId))
                .toArriveAndVerify(event -> {
                    var actualResult = event.translationResult();

                    var eps = 20;
                    var aprxSize = Math.abs(actualResult.length() - expectedResult.length());
                    assertTrue(aprxSize < eps);
                });
    }
}
