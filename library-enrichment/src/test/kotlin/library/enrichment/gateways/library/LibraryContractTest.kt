package library.enrichment.gateways.library

import au.com.dius.pact.model.RequestResponsePact
import library.enrichment.correlation.CorrelationIdRequestInterceptor
import library.enrichment.gateways.library.LibraryContractTest.CustomConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.test.context.ContextConfiguration
import utils.classification.IntegrationTest
import utils.execute
import utils.extensions.EnableSpringExtension
import utils.pactWith
import utils.setPactContractFolder


@SpringBootTest
@IntegrationTest
@EnableSpringExtension
@ContextConfiguration(classes = [CustomConfiguration::class])
internal class LibraryContractTest {

    @ComponentScan(basePackageClasses = [LibraryAccessor::class, CorrelationIdRequestInterceptor::class])
    class CustomConfiguration

    companion object {
        val provider = "library-service"

        val bookId = "3c15641e-2598-41f5-9097-b37e2d768be5"
        val authors = listOf("J.R.R. Tolkien", "Jim Butcher")
        val numberOfPages = 256
    }

    @Autowired lateinit var settings: LibrarySettings
    @Autowired lateinit var accessor: LibraryAccessor
    @Autowired lateinit var client: LibraryClient

    @BeforeEach fun setUp() {
        setPactContractFolder("../library-service/src/test/pacts/http")
    }

    @Test fun `pinging the library service`() = pactWith(provider) {
        uponReceiving("service ping")
                .method("GET")
                .path("/api")
                .willRespondWith()
                .status(200)
    } verifyWith {
        client.ping()
    }

    @Test fun `updating the authors of a book`() = pactWith(provider) {
        given("A book with the ID {bookId} exists", mapOf("bookId" to "3c15641e-2598-41f5-9097-b37e2d768be5"))
                .uponReceiving("update authors of a book")
                .method("PUT")
                .path("/api/books/$bookId/authors")
                .body("""{ "authors": ["J.R.R. Tolkien", "Jim Butcher"] }""")
                .willRespondWith()
                .status(200)
    } verifyWith {
        accessor.updateAuthors(bookId, authors)
    }

    @Test fun `updating the number of pages of a book`() = pactWith(provider) {
        given("A book with the ID {bookId} exists", mapOf("bookId" to "3c15641e-2598-41f5-9097-b37e2d768be5"))
                .uponReceiving("update number of pages of a book")
                .method("PUT")
                .path("/api/books/$bookId/numberOfPages")
                .body("""{ "numberOfPages": 256 }""")
                .willRespondWith()
                .status(200)
    } verifyWith {
        accessor.updateNumberOfPages(bookId, numberOfPages)
    }

    infix fun RequestResponsePact.verifyWith(test: () -> Unit) = execute { server ->
        settings.url = server.getUrl()
        test()
    }

}