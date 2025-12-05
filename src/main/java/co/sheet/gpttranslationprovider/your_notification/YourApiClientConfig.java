package co.sheet.gpttranslationprovider.your_notification;

import lombok.RequiredArgsConstructor;
import org.openapitools.client.ApiClient;
import org.openapitools.client.api.YourServiceApi;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestTemplate;

@Profile("!local")
@Configuration
@EnableConfigurationProperties(YourApiConfig.class)
@RequiredArgsConstructor
class YourApiClientConfig {

    final YourApiConfig yourApiConfig;

    @Bean
    YourServiceApi keyTranslationServiceApi(RestTemplate restTemplate) {
        var apiClient = new ApiClient(restTemplate);

        apiClient.addDefaultHeader("api-key", yourApiConfig.apiKey());
        apiClient.setBasePath(yourApiConfig.baseUrl());

        return new YourServiceApi(apiClient);
    }

    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

@ConfigurationProperties(prefix = "your.api")
record YourApiConfig(
    String grantType,
    String tokenUri,
    String clientId,
    String clientSecret,
    String scope,
    String apiKey,
    String baseUrl) {

}
