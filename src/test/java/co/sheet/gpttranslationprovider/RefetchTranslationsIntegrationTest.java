package co.sheet.gpttranslationprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.sheet.gpttranslationprovider.your_notification.TranslationReadyEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.client.api.YourServiceApi;
import org.openapitools.client.model.YourResponse;
import org.openapitools.client.model.TranslationUpdate;
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
class RefetchTranslationsIntegrationTest {

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

    @BeforeEach
    void cleanupEventPublications() {
        jdbcClient.sql("DELETE FROM event_publication").update();
    }

    @Test
    void refetchTranslations_shouldResubmitIncompleteEventsForGivenOrderId(Scenario scenario) throws Exception {
        // [Arrange] Create multiple translation requests with different orderIds
        var request1 = new TranslationRequest(
            1001L,
            2001L,
            "Test value 1",
            "en-GB",
            "ru-RU",
            "user123"
        );
        var request2 = new TranslationRequest(
            1001L, // Same orderId as request1
            2002L,
            "Test value 2",
            "en-GB",
            "ru-RU",
            "user123"
        );
        var request3 = new TranslationRequest(
            9999L, // Different orderId
            2003L,
            "Test value 3",
            "en-GB",
            "es-ES",
            "user456"
        );

        var response1 = """
            {
                "translatedText": "Тестовое значение 1",
                "sourceLanguage": "en-GB",
                "targetLanguage": "ru-RU",
                "confidence": "high"
            }
            """;

        // Mock OpenAI responses - only 3 calls for initial translations
        // Refetch doesn't call OpenAI again, it resubmits existing TranslationReadyEvents
        // Since events are async and unordered, we use thenAnswer to handle any call
        when(openAiChatModel.call(any(Prompt.class)))
            .thenAnswer(invocation -> createChatResponse(response1));

        // Mock Your API client using stateful logic
        // Track calls per orderId to fail first attempts and succeed on refetch
        var callCountPerOrderId = new ConcurrentHashMap<Long, AtomicInteger>();
        var errorResponse = new YourResponse(
            null,
            List.of("Error: Order temporarily unavailable"),
            null,
            null,
            null
        );
        var successResponse = new YourResponse(null, null, null, "Success", null);

        when(yourServiceApi.updateTranslationForKey(any(TranslationUpdate.class)))
            .thenAnswer(invocation -> {
                TranslationUpdate update = invocation.getArgument(0);
                var orderId = update.getOrderId();

                // Track how many times we've been called for this orderId
                var counter = callCountPerOrderId.computeIfAbsent(orderId, k -> new AtomicInteger(0));
                int callNumber = counter.incrementAndGet();

                // For orderId 1001: first calls fail, subsequent (refetch) succeed
                // For orderId 9999: always succeed
                if (orderId.equals(1001L) && callNumber <= 2) {
                    return errorResponse; // First 2 calls for orderId 1001 fail
                }
                return successResponse; // All others succeed
            });

        // [Act] First, create the translation requests to generate incomplete events
        var requests = objectMapper.writeValueAsString(List.of(request1, request2, request3));

        scenario.stimulate(() -> {
                    try {
                        return mockMvc.perform(post("/api/v1/translateAll")
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content(requests))
                                      .andExpect(status().isAccepted());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .matching(event -> event.translationRequest().orderId().equals(9999L))
                .toArrive();

        // Verify we have incomplete events for orderId 1001
        var incompleteEventsForOrder1001Before = jdbcClient.sql(
                                                               """
                                                                   SELECT COUNT(*) FROM event_publication
                                                                   WHERE completion_date IS NULL
                                                                   AND event_type LIKE '%TranslationReadyEvent%'
                                                                   AND serialized_event LIKE '%"orderId":1001%'
                                                                   """
                                                           )
                                                           .query(Long.class)
                                                           .single();

        assertThat(incompleteEventsForOrder1001Before)
            .as("Should have 2 incomplete events for orderId 1001 before refetch")
            .isEqualTo(2L);

        // [Act] Refetch translations for orderId 1001
        var refetchRequest = """
            {
                "orderId": 1001
            }
            """;

        scenario.stimulate(() -> {
                    try {
                        return mockMvc.perform(post("/api/v1/refetchTranslations")
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content(refetchRequest))
                                      .andExpect(status().isAccepted());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .matching(event -> event.translationRequest().orderId().equals(1001L))
                .toArrive();

        // [Assert] Verify that incomplete events for orderId 1001 are now completed
        var incompleteEventsForOrder1001After = jdbcClient.sql(
                                                              """
                                                                  SELECT COUNT(*) FROM event_publication
                                                                  WHERE completion_date IS NULL
                                                                  AND event_type LIKE '%TranslationReadyEvent%'
                                                                  AND serialized_event LIKE '%"orderId":1001%'
                                                                  """
                                                          )
                                                          .query(Long.class)
                                                          .single();

        assertThat(incompleteEventsForOrder1001After)
            .as("Should have 0 incomplete events for orderId 1001 after successful refetch")
            .isZero();

        // Verify that events for orderId 9999 were NOT resubmitted (still complete)
        var incompleteEventsForOrder9999 = jdbcClient.sql(
                                                         """
                                                             SELECT COUNT(*) FROM event_publication
                                                             WHERE completion_date IS NULL
                                                             AND event_type LIKE '%TranslationReadyEvent%'
                                                             AND serialized_event LIKE '%"orderId":9999%'
                                                             """
                                                     )
                                                     .query(Long.class)
                                                     .single();

        assertThat(incompleteEventsForOrder9999)
            .as("Should have 0 incomplete events for orderId 9999 (was successful initially)")
            .isZero();

        // Verify Your API was called correct number of times:
        // - 3 initial calls (request1, request2, request3)
        // - 2 refetch calls (request1, request2 for orderId 1001)
        // Total: 5 calls
        verify(yourServiceApi, times(5))
            .updateTranslationForKey(any(TranslationUpdate.class));
    }

    @Test
    void refetchTranslations_shouldRejectInvalidRequest() throws Exception {
        // [Arrange] Invalid request with null orderId
        var invalidRequest = """
            {
                "orderId": null
            }
            """;

        // [Act & Assert] Should return BAD_REQUEST
        mockMvc.perform(post("/api/v1/refetchTranslations")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(invalidRequest))
               .andExpect(status().isBadRequest());

        // Verify no services were called
        verifyNoInteractions(openAiChatModel);
        verifyNoInteractions(yourServiceApi);
    }

    @Test
    void refetchTranslations_shouldRejectNullRequestBody() throws Exception {
        // [Act & Assert] Should return BAD_REQUEST for null body
        mockMvc.perform(post("/api/v1/refetchTranslations")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content("null"))
               .andExpect(status().isBadRequest());

        // Verify no services were called
        verifyNoInteractions(openAiChatModel);
        verifyNoInteractions(yourServiceApi);
    }

    @Test
    void refetchTranslations_shouldHandleOrderIdWithNoIncompleteEvents() throws Exception {
        // [Arrange] Request for an orderId that has no incomplete events
        var refetchRequest = """
            {
                "orderId": 99999
            }
            """;

        // [Act] Should still return ACCEPTED even if no events to refetch
        mockMvc.perform(post("/api/v1/refetchTranslations")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(refetchRequest))
               .andExpect(status().isAccepted());

        // Verify no Your API calls were made (no events to resubmit)
        verifyNoInteractions(yourServiceApi);
    }

    @Test
    void refetchTranslations_shouldResubmitBothReadyToTranslateAndTranslationReadyEvents(Scenario scenario) throws Exception {
        // [Arrange] Create a translation request that will fail at Your notification stage
        var request = new TranslationRequest(
            2001L,
            3001L,
            "Test value for double event",
            "en-GB",
            "fr-FR",
            "user789"
        );

        var response = """
            {
                "translatedText": "Valeur de test pour double événement",
                "sourceLanguage": "en-GB",
                "targetLanguage": "fr-FR",
                "confidence": "high"
            }
            """;

        // Mock OpenAI response
        when(openAiChatModel.call(any(Prompt.class)))
            .thenReturn(createChatResponse(response));

        // Mock Your API client - fails initially
        var errorResponse = new YourResponse(
            null,
            List.of("Error: Service unavailable"),
            null,
            null,
            null
        );
        when(yourServiceApi.updateTranslationForKey(any(TranslationUpdate.class)))
            .thenReturn(errorResponse);

        // [Act] Create translation request
        var requests = objectMapper.writeValueAsString(List.of(request));

        scenario.stimulate(() -> {
                    try {
                        return mockMvc.perform(post("/api/v1/translateAll")
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content(requests))
                                      .andExpect(status().isAccepted());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .matching(event -> event.translationRequest().orderId().equals(2001L))
                .toArrive();

        // Verify we have incomplete TranslationReadyEvent
        var incompleteEventsBefore = jdbcClient.sql(
                                                   """
                                                       SELECT COUNT(*) FROM event_publication
                                                       WHERE completion_date IS NULL
                                                       AND serialized_event LIKE '%"orderId":2001%'
                                                       """
                                               )
                                               .query(Long.class)
                                               .single();

        assertThat(incompleteEventsBefore)
            .as("Should have at least 1 incomplete event for orderId 2001")
            .isGreaterThan(0L);

        // Mock successful response for refetch
        var successResponse = new YourResponse(null, null, null, "Success", null);
        when(yourServiceApi.updateTranslationForKey(any(TranslationUpdate.class)))
            .thenReturn(successResponse);

        // [Act] Refetch translations for orderId 2001
        var refetchRequest = """
            {
                "orderId": 2001
            }
            """;

        scenario.stimulate(() -> {
                    try {
                        return mockMvc.perform(post("/api/v1/refetchTranslations")
                                          .contentType(MediaType.APPLICATION_JSON)
                                          .content(refetchRequest))
                                      .andExpect(status().isAccepted());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitForEventOfType(TranslationReadyEvent.class)
                .matching(event -> event.translationRequest().orderId().equals(2001L))
                .toArrive();

        // [Assert] Verify incomplete events are now completed
        var incompleteEventsAfter = jdbcClient.sql(
                                                  """
                                                      SELECT COUNT(*) FROM event_publication
                                                      WHERE completion_date IS NULL
                                                      AND serialized_event LIKE '%"orderId":2001%'
                                                      """
                                              )
                                              .query(Long.class)
                                              .single();

        assertThat(incompleteEventsAfter)
            .as("All events for orderId 2001 should be completed after successful refetch")
            .isZero();
    }

    private ChatResponse createChatResponse(String content) {
        var generation = new Generation(new AssistantMessage(content));
        return new ChatResponse(List.of(generation));
    }
}
