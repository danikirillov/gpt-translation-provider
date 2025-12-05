package co.sheet.gpttranslationprovider.open_ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import co.sheet.gpttranslationprovider.your_notification.TranslationReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.openai.api.ResponseFormat.Type;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class OpenAiService {

    final OpenAiChatModel chatModel;
    final ApplicationEventPublisher publisher;
    final ResponseMapper responseMapper;

    static final String RESPONSE_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "translatedText": {
              "type": "string",
              "description": "The translated text"
            },
            "sourceLanguage": {
              "type": "string",
              "description": "Detected or provided source language code"
            },
            "targetLanguage": {
              "type": "string",
              "description": "Target language code"
            },
            "confidence": {
              "type": "string",
              "enum": ["high", "medium", "low"],
              "description": "Translation confidence level"
            }
          },
          "required": ["translatedText", "sourceLanguage", "targetLanguage", "confidence"],
          "additionalProperties": false
        }
        """;

    static final String DEFAULT_TRANSLATION_PROMPT = """
        You are a professional translator specializing in e-commerce photo descriptions.
        
        Translate the following text from {SOURCE_LANGUAGE} to {TARGET_LANGUAGE}.
        
        Original text: "{VALUE}"
        
        Requirements:
        - Maintain the tone and style appropriate for product photography descriptions
        - Preserve any technical terms or brand names
        - Keep the same level of formality
        - Ensure cultural appropriateness for the target locale
        """;

    @ApplicationModuleListener
    @Retryable(
        retryFor = {Exception.class},
        maxAttempts = 1,
        backoff = @Backoff(delay = 1000, multiplier = 2, maxDelay = 10000, random = true)
    )
    void translate(ReadyToTranslateEvent event) {
        var request = event.translationRequest();
        log.info("Translating '{}' for orderId={} to locale={}", request.value(), request.orderId(), request.targetLocale());
        var promptText = DEFAULT_TRANSLATION_PROMPT
            .replace("{VALUE}", request.value())
            .replace("{SOURCE_LANGUAGE}", request.sourceLocale())
            .replace("{TARGET_LANGUAGE}", request.targetLocale());

        var options = OpenAiChatOptions
            .builder()
            .model("gpt-5.1")
            .temperature(0.4)
            .responseFormat(new ResponseFormat(Type.JSON_SCHEMA, RESPONSE_SCHEMA))
            .build();

        var prompt = new Prompt(promptText, options);
        var chatResponse = chatModel.call(prompt);

        var translationResult = responseMapper.map(chatResponse);

        publisher.publishEvent(new TranslationReadyEvent(request, translationResult));
        log.info("Translation ready for orderId={}, result='{}'", request.orderId(), translationResult);
    }
}

@Slf4j
@Component
@RequiredArgsConstructor
class ResponseMapper {

    final ObjectMapper objectMapper;

    String map(ChatResponse translationResponse) {
        try {
            var translationResult = translationResponse.getResult().getOutput().getText();
            var jsonResponse = objectMapper.readTree(translationResult);
            var translatedText = jsonResponse.get("translatedText").asText();
            log.info("OpenAI confidence: {}", jsonResponse.get("confidence").asText());
            return translatedText;
        } catch (Exception e) {
            throw new IllegalArgumentException("Error occurred while parsing gpt response!", e);
        }
    }
}


