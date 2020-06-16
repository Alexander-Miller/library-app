package library.service.security

import brave.Tracer
import brave.Tracing
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.hateoas.MediaTypes.HAL_JSON
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpHeaders.encodeBasicAuth
import org.springframework.http.HttpStatus.UNAUTHORIZED
import org.springframework.test.web.reactive.server.WebTestClient
import utils.classification.SecuredIntegrationTest
import java.nio.charset.StandardCharsets.UTF_8


@SecuredIntegrationTest
@WebFluxTest(UserInfoController::class)
internal class UserInfoControllerIntTest {

    @TestConfiguration
    @Import(SecurityConfiguration::class)
    class AdditionalBeans {
        @Bean
        fun tracer(): Tracer = Tracing.newBuilder().build().tracer()
    }

    @Autowired
    lateinit var testClient: WebTestClient

    @Test
    fun `GET returns user info for authenticated user`() {
        val expectedResponse = """
                {
                  "username": "curator",
                  "authorities": ["ROLE_CURATOR", "ROLE_USER"]
                }
            """

        testClient.get()
            .uri("/userinfo")
            .header(AUTHORIZATION, basicAuthHeader("curator", "curator"))
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith { System.err.println(String(it.responseBody!!)) }
            .json(expectedResponse)
    }

    @Test
    fun `GET responds with 401 for wrong user credentials`() {
        testClient.get()
            .uri("/userinfo")
            .header(AUTHORIZATION, basicAuthHeader("curator", "wrong"))
            .exchange()
            .expectStatus().isEqualTo(UNAUTHORIZED)
    }

    private fun basicAuthHeader(username: String, pw: String) =
        "Basic ${encodeBasicAuth(username, pw, UTF_8)}"
}