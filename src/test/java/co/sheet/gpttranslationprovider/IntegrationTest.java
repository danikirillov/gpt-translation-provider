package co.sheet.gpttranslationprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.sheet.gpttranslationprovider.your_notification.TranslationReadyEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openapitools.client.api.YourServiceApi;
import org.openapitools.client.model.TranslationUpdate;
import org.openapitools.client.model.YourResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnableScenarios
class IntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbcClient;

    @MockitoBean
    OpenAiChatModel openAiChatModel;

    @MockitoBean
    YourServiceApi yourServiceApi;


    @Test
    void translateAll_shouldValidateRequest_processTranslations_andNotifyYourApi(Scenario scenario) throws Exception {
        // [Arrange] Multiple valid translation requests
        var request1 = new TranslationRequest(
            1001L,
            2001L,
            "View larger image: A front-facing orange blouse is featuring a ruffled V-neckline and long sleeves with ruffled cuffs, and is tucked into a dark, high-waisted skirt. The skirt is detailed with a wide waistband, grommets, visible vertical stitching, and two hanging drawstrings.",
            "en-GB",
            "ru-RU",
            "user123"
        );
        var response1 = """
            {
                "translatedText": "Увеличенное изображение: Оранжевая блузка с V-образным вырезом и длинными рукавами с оборками на манжетах, заправленная в темную юбку с завышенной талией. Юбка дополнена широким поясом, люверсами, видимой вертикальной строчкой и двумя свисающими завязками.",
                "sourceLanguage": "en-GB",
                "targetLanguage": "ru-RU",
                "confidence": "high"
            }
            """;
        var request2 = new TranslationRequest(
            1002L,
            2002L,
            "View larger image: A brown fleece jacket is being presented from a three-quarter front view, featuring a textured, shaggy fleece material. It is presenting a collarless round neckline, an open front revealing a white top with lace details underneath, and long, relaxed sleeves with dark brown binding along its edges.",
            "en-GB",
            "sv-SE",
            "user123"
        );
        var response2 = """
            {
                "translatedText": "Visa större bild: En brun fleecejacka presenteras från en trekvartsvy framifrån, med ett texturerat, raggigt fleece-material. Den har en kraglös rund hals, en öppen framsida som avslöjar en vit topp med spetsdetaljer under, och långa, avslappnade ärmar med mörkbrun kantning längs kanterna.",
                "sourceLanguage": "en-GB",
                "targetLanguage": "sv-SE",
                "confidence": "high"
            }
            """;
        var requests = objectMapper.writeValueAsString(List.of(request1, request2));

        // Mock OpenAI responses
        when(openAiChatModel.call(any(Prompt.class)))
            .thenReturn(createChatResponse(response1))
            .thenReturn(createChatResponse(response2));
        // Mock Your API client - returns successful YourResponse with no errors
        var mockResponse = new YourResponse(null, null, null, "Success", null);
        when(yourServiceApi.updateTranslationForKey(any(TranslationUpdate.class)))
            .thenReturn(mockResponse);

        // [Act] POST to /translateAll endpoint and wait for async events to complete
        scenario.stimulate(() ->
                    {
                        try {
                            return mockMvc.perform(post("/api/v1/translateAll")
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content(requests))
                                          // [Assert] Should return ACCEPTED (controller doesn't wait for async processing)
                                          .andExpect(status().isAccepted());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                )
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .toArrive();

        // Verify all async processing completed correctly
        verify(openAiChatModel, times(2)).call(any(Prompt.class));

        verify(yourServiceApi, times(2))
            .updateTranslationForKey(any(TranslationUpdate.class));

        verify(yourServiceApi, times(1)).updateTranslationForKey(
            argThat(update ->
                update.getOrderId().equals(1001L) &&
                    update.getMasterCopyKeyId() != null && update.getMasterCopyKeyId().equals(2001L) &&
                    update.getTargetLocale().equals("ru-RU")
            )
        );

        verify(yourServiceApi, times(1)).updateTranslationForKey(
            argThat(update ->
                update.getOrderId().equals(1002L) &&
                    update.getMasterCopyKeyId() != null && update.getMasterCopyKeyId().equals(2002L) &&
                    update.getTargetLocale().equals("sv-SE")
            )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        """
            [{
                "orderId": null,
                "masterCopyKeyId": 2001,
                "value": "Test value",
                "sourceLocale": "en",
                "targetLocale": "es",
                "userId": "user123"
            }]
            """,
        """
            [{
                "orderId": 1001,
                "masterCopyKeyId": 2001,
                "value": "",
                "sourceLocale": "en",
                "targetLocale": "es",
                "userId": "user123"
            }]
            """,
        """
            [{
                "orderId": 1001,
                "masterCopyKeyId": 2001,
                "value": "Test value",
                "targetLocale": "es",
                "userId": "user123"
            }]
            """
    })
    void translateAll_shouldRejectInvalidRequests(String invalidRequestJson) throws Exception {
        // Should return BAD_REQUEST
        mockMvc.perform(post("/api/v1/translateAll")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(invalidRequestJson))
               .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        // Verify no services were called
        verifyNoInteractions(openAiChatModel);
        verifyNoInteractions(yourServiceApi);
    }

    @Test
    void translateAll_shouldRejectNullRequestBody() throws Exception {
        // When/Then: Should return BAD_REQUEST for null body
        mockMvc.perform(post("/api/v1/translateAll")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content("null"))
               .andExpect(status().isBadRequest());

        // Verify no services were called
        verifyNoInteractions(openAiChatModel);
        verifyNoInteractions(yourServiceApi);
    }

    @Test
    void translateAll_shouldProcessEmptyList() throws Exception {
        // Given: Empty list of requests
        var emptyList = List.of();

        // When: POST empty list
        mockMvc.perform(post("/api/v1/translateAll")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(objectMapper.writeValueAsString(emptyList)))
               // Then: Should still return ACCEPTED (no-op)
               .andExpect(status().isAccepted());

        // Verify no services were called
        verifyNoInteractions(openAiChatModel);
        verifyNoInteractions(yourServiceApi);
    }

    @Test
    void translateAll_shouldHandleYourApiErrors(Scenario scenario) throws Exception {
        // [Arrange] Valid translation request
        var request = new TranslationRequest(
            1001L,
            2001L,
            "Test value",
            "en-GB",
            "es-ES",
            "user123"
        );
        var response = """
            {
                "translatedText": "Ver imagen más grande: Una blusa naranja de frente presenta un escote en V con volantes y mangas largas con puños con volantes, y está metida en una falda oscura de talle alto. La falda está detallada con una cintura ancha, ojales, costura vertical visible y dos cordones colgantes.",
                "sourceLanguage": "en-GB",
                "targetLanguage": "es-ES",
                "confidence": "low"
            }
            """;
        var requests = objectMapper.writeValueAsString(List.of(request));

        when(openAiChatModel.call(any(Prompt.class)))
            .thenReturn(createChatResponse(response));
        var mockResponse = new YourResponse(
            null,
            List.of("Error 1: Invalid locale", "Error 2: Order not found"),
            null,
            null,
            null
        );
        when(yourServiceApi.updateTranslationForKey(any(TranslationUpdate.class)))
            .thenReturn(mockResponse);

        // [Act] POST to /translateAll endpoint and wait for async processing
        scenario.stimulate(() ->
                    {
                        try {
                            return mockMvc.perform(post("/api/v1/translateAll")
                                              .contentType(MediaType.APPLICATION_JSON)
                                              .content(requests))
                                          .andExpect(status().isAccepted());
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                )
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .matching(event -> event.translationRequest().orderId().equals(1001L))
                .toArrive();

        verify(openAiChatModel, times(1)).call(any(Prompt.class));
        verify(yourServiceApi, times(1))
            .updateTranslationForKey(any(TranslationUpdate.class));

        // Verify that the event remains in incomplete state (resendable) after Your API error
        var incompleteEventCount =
            jdbcClient.sql("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL")
                      .query(Long.class)
                      .single();

        assertThat(incompleteEventCount)
            .as("Should have at least one incomplete event publication for retry after Your API error")
            .isGreaterThan(0L);

        // Verify specifically that TranslationReadyEvent is incomplete
        var incompleteTranslationReadyEventCount =
            jdbcClient.sql(
                          """
                              SELECT COUNT(*) FROM event_publication
                              WHERE completion_date IS NULL
                              AND event_type LIKE '%TranslationReadyEvent%'
                              """
                      )
                      .query(Long.class)
                      .single();

        assertThat(incompleteTranslationReadyEventCount)
            .as("TranslationReadyEvent should be incomplete and resendable after Your API error")
            .isEqualTo(1L);

        // Note: The IllegalStateException is thrown in the async event listener,
        // which doesn't affect the HTTP response (already returned 202 ACCEPTED).
        // Spring Modulith keeps the event in incomplete state for retry.
    }

    private ChatResponse createChatResponse(String content) {
        var generation = new Generation(new AssistantMessage(content));
        return new ChatResponse(List.of(generation));
    }
}
