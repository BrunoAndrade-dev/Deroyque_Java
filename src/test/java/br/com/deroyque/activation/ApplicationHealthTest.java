package br.com.deroyque.activation;

import br.com.deroyque.activation.support.PostgresTestConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ApplicationHealthTest {

    @Autowired
    private TestRestTemplate client;

    @ParameterizedTest
    @ValueSource(strings = {"liveness", "readiness"})
    void shouldExposeHealthyApplicationProbe(String probe) {
        var response = client.getForEntity("/actuator/health/" + probe, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }
}
