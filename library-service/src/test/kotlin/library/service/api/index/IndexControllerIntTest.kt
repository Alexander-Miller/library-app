package library.service.api.index

import brave.Tracer
import brave.Tracing
import io.mockk.every
import io.mockk.mockk
import library.service.security.SecurityConfiguration
import library.service.security.UserContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.hateoas.MediaTypes.HAL_JSON
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import utils.classification.IntegrationTest
import utils.document


@IntegrationTest
@WebFluxTest(IndexController::class)
@AutoConfigureRestDocs("build/generated-snippets/index")
internal class IndexControllerIntTest(
    @Autowired val testClient: WebTestClient
) {

    @TestConfiguration
    @Import(SecurityConfiguration::class)
    class AdditionalBeans {
        @Bean
        fun userContext() = mockk<UserContext> {
            every { isCurator() } returns Mono.just(true)
        }

        @Bean
        fun tracer(): Tracer = Tracing.newBuilder().build().tracer()
    }

    @Test
    fun `get api index returns links to available endpoint actions`() {
        val expectedResponse = """
                {
                  "_links": {
                    "self": { "href": "http://localhost:8080/api" },
                    "getBooks": { "href": "http://localhost:8080/api/books" },
                    "addBook": { "href": "http://localhost:8080/api/books" }
                  }
                }
            """
        testClient.get()
            .uri("/api")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .json(expectedResponse)
            .consumeWith(document("getIndex"))
    }

}