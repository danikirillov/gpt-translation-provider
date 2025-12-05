package co.sheet.gpttranslationprovider;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@EnableJdbcRepositories
@EnableScheduling
@OpenAPIDefinition(
    info = @Info(
        title = "GPT Translation Provider API",
        version = "1.0.0",
        contact = @Contact(
            name = "Daniil",
            email = "danilokirillov@icloud.com",
            url = "https://github.com/danikirillov")
    )
)
public class GptTranslationProvider {

    public static void main(String[] args) {
        SpringApplication.run(GptTranslationProvider.class, args);
    }

}
