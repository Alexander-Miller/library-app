package library.service.api.books

import brave.Tracer
import brave.Tracing
import io.mockk.every
import io.mockk.mockk
import library.service.business.books.BookCollection
import library.service.business.books.BookDataStore
import library.service.business.books.BookIdGenerator
import library.service.business.books.domain.BookRecord
import library.service.business.books.domain.composites.Book
import library.service.business.books.domain.types.Author
import library.service.business.books.domain.types.BookId
import library.service.business.books.domain.types.Borrower
import library.service.security.SecurityConfiguration
import library.service.security.UserContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.hateoas.MediaTypes.HAL_JSON
import org.springframework.http.HttpStatus.CONFLICT
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.config.EnableWebFlux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import utils.Books
import utils.MutableClock
import utils.ResetMocksAfterEachTest
import utils.classification.IntegrationTest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.*

@IntegrationTest
@ResetMocksAfterEachTest
@WebFluxTest(BooksController::class)
class BooksControllerIntTest(
    @Autowired val bookDataStore: BookDataStore,
    @Autowired val bookIdGenerator: BookIdGenerator,
    @Autowired val testClient: WebTestClient,
    @Autowired val clock: MutableClock,
    @Autowired val userContext: UserContext
) {

    @TestConfiguration
    @Import(SecurityConfiguration::class)
    class AdditionalBeans {
        @Bean
        fun tracer(): Tracer = Tracing.newBuilder().build().tracer()

        @Bean
        fun userContext() = mockk<UserContext>()

        @Bean
        fun bookResourceAssembler(userContext: UserContext) = BookResourceAssembler(userContext)

        @Bean
        fun bookCollection(clock: Clock) = BookCollection(
            clock = clock,
            dataStore = bookDataStore(),
            idGenerator = bookIdGenerator(),
            eventDispatcher = mockk(relaxed = true)
        )

        @Bean
        fun bookDataStore(): BookDataStore = mockk()

        @Bean
        fun bookIdGenerator(): BookIdGenerator = mockk()
    }

    val correlationId = UUID.randomUUID().toString()

    @BeforeEach
    fun setTime() {
        clock.setFixedTime("2017-08-20T12:34:56.789Z")
    }

    @BeforeEach
    fun initMocks() {
        every { bookDataStore.findById(any()) } returns Mono.empty()
        every { bookDataStore.createOrUpdate(any()) } answers { Mono.just(firstArg()) }
        every { userContext.isCurator() } returns Mono.just(true)
    }

    @DisplayName("/api/books")
    @Nested
    inner class BooksEndpoint {

        @DisplayName("GET")
        @Nested
        inner class GetMethod {

            @Test
            fun `when there are no books, the response only contains a self link`() {
                every { bookDataStore.findAll() } returns Flux.empty()
                val expectedResponse = """
                    {
                      "_links": {
                          "self": { "href": "/api/books" }
                      }
                    }
                """
                testClient.get()
                    .uri("/api/books")
                    .accept(HAL_JSON)
                    .exchange()
                    .expectStatus().isOk
                    .expectHeader().contentType(HAL_JSON)
                    .expectBody()
                    .json(expectedResponse)
            }

            @Test
            fun `when there are books, the response contains them with all relevant links`() {
                val availableBook = availableBook(
                    id = BookId.from("883a2931-325b-4482-8972-8cb6f7d33816"),
                    book = Books.CLEAN_CODE
                )
                val borrowedBook = borrowedBook(
                    id = BookId.from("53397dc0-932d-4198-801a-3e00b2742ba7"),
                    book = Books.CLEAN_CODER,
                    borrowedBy = "Uncle Bob",
                    borrowedOn = "2017-08-20T12:34:56.789Z"
                )
                every { bookDataStore.findAll() } returns Flux.fromIterable(listOf(availableBook, borrowedBook))

                val expectedResponse = """
                    {
                      "_embedded": {
                        "books": [
                          {
                            "isbn": "${Books.CLEAN_CODE.isbn}",
                            "title": "${Books.CLEAN_CODE.title}",
                            "authors": ${Books.CLEAN_CODE.authors.toJson()},
                            "numberOfPages": ${Books.CLEAN_CODE.numberOfPages},
                            "_links": {
                              "self": {
                                "href": "/api/books/883a2931-325b-4482-8972-8cb6f7d33816"
                              },
                              "delete": {
                                "href": "/api/books/883a2931-325b-4482-8972-8cb6f7d33816"
                              },
                              "borrow": {
                                "href": "/api/books/883a2931-325b-4482-8972-8cb6f7d33816/borrow"
                              }
                            }
                          },
                          {
                            "isbn": "${Books.CLEAN_CODER.isbn}",
                            "title": "${Books.CLEAN_CODER.title}",
                            "authors": ${Books.CLEAN_CODER.authors.toJson()},
                            "numberOfPages": ${Books.CLEAN_CODER.numberOfPages},
                            "borrowed": {
                              "by": "Uncle Bob",
                              "on": "2017-08-20T12:34:56.789Z"
                            },
                            "_links": {
                              "self": {
                                "href": "/api/books/53397dc0-932d-4198-801a-3e00b2742ba7"
                              },
                              "delete": {
                                "href": "/api/books/53397dc0-932d-4198-801a-3e00b2742ba7"
                              },
                              "return": {
                                "href": "/api/books/53397dc0-932d-4198-801a-3e00b2742ba7/return"
                              }
                            }
                          }
                        ]
                      },
                      "_links": {
                        "self": {
                          "href": "/api/books"
                        }
                      }
                    }
                """
                testClient.get()
                    .uri("/api/books")
                    .accept(HAL_JSON)
                    .exchange()
                    .expectStatus().isOk
                    .expectHeader().contentType(HAL_JSON)
                    .expectBody()
                    .json(expectedResponse)
            }

        }

        @DisplayName("POST")
        @Nested
        inner class PostMethod {

            @Test
            fun `creates a book and responds with its resource representation`() {
                val bookId = BookId.generate()
                every { bookIdGenerator.generate() } returns Mono.just(bookId)

                val requestBody = """
                    {
                      "isbn": "9780132350884",
                      "title": "Clean Code: A Handbook of Agile Software Craftsmanship"
                    }
                """

                val expectedResponse = """
                    {
                      "isbn": "9780132350884",
                      "title": "Clean Code: A Handbook of Agile Software Craftsmanship",
                      "authors": [],
                      "_links": {
                        "self": {
                          "href": "/api/books/$bookId"
                        },
                        "delete": {
                          "href": "/api/books/$bookId"
                        },
                        "borrow": {
                          "href": "/api/books/$bookId/borrow"
                        }
                      }
                    }
                """

                testClient.post()
                    .uri("/api/books")
                    .header("Content-Type", APPLICATION_JSON.toString())
                    .body(Mono.just(requestBody), String::class.java)
                    .exchange()
                    .expectStatus().isCreated
                    .expectHeader().contentType(HAL_JSON)
                    .expectBody()
                    .json(expectedResponse)
            }

            @Test
            fun `400 BAD REQUEST for invalid ISBN`() {
                val requestBody = """
                    {
                      "isbn": "abcdefghij",
                      "title": "Clean Code: A Handbook of Agile Software Craftsmanship"
                    }
                """

                val expectedResponse = """
                    {
                      "path": "/api/books",
                      "status": 400,
                      "error": "Bad Request",
                      "timestamp": "2017-08-20T12:34:56.789Z",
                      "correlationId": "$correlationId",
                      "message": "The request's body is invalid. See details...",
                      "details": ["The field 'isbn' must match \"(\\d{3}-?)?\\d{10}\"."]
                    }
                """

                testClient.post()
                    .uri("/api/books")
                    .header("Content-Type", APPLICATION_JSON.toString())
                    .header("X-B3-TraceId", correlationId)
                    .body(Mono.just(requestBody), String::class.java)
                    .exchange()
                    .expectStatus().isBadRequest
                    .expectHeader().contentType(APPLICATION_JSON)
                    .expectBody()
                    .json(expectedResponse)
            }

            @Test
            fun `400 BAD REQUEST for missing required properties`() {
                val expectedResponse = """
                    {
                      "path": "/api/books",
                      "status": 400,
                      "error": "Bad Request",
                      "timestamp": "2017-08-20T12:34:56.789Z",
                      "correlationId": "$correlationId",
                      "message": "The request's body is invalid. See details...",
                      "details": [
                        "The field 'isbn' must not be blank.",
                        "The field 'title' must not be blank."
                      ]
                    }
                """

                testClient.post()
                    .uri("/api/books")
                    .header("Content-Type", APPLICATION_JSON.toString())
                    .header("X-B3-TraceId", correlationId)
                    .body(Mono.just("{ }"), String::class.java)
                    .exchange()
                    .expectStatus().isBadRequest
                    .expectHeader().contentType(APPLICATION_JSON)
                    .expectBody()
                    .json(expectedResponse)
            }

            @Test
            fun `400 BAD REQUEST for malformed request`() {
                val expectedResponse = """
                    {
                      "path": "/api/books",
                      "status": 400,
                      "error": "Bad Request",
                      "timestamp": "2017-08-20T12:34:56.789Z",
                      "correlationId": "$correlationId",
                      "message": "The request's body could not be read. It is either empty or malformed."
                    }
                """

                testClient.post()
                    .uri("/api/books")
                    .header("X-B3-TraceId", correlationId)
                    .exchange()
                    .expectStatus().isBadRequest
                    .expectHeader().contentType(APPLICATION_JSON)
                    .expectBody()
                    .json(expectedResponse)
            }

        }

        @DisplayName("/api/books/{id}")
        @Nested
        inner class BookByIdEndpoint {

            val id = BookId.generate()
            val book = Books.CLEAN_CODE
            val availableBookRecord = availableBook(id, book)
            val borrowedBookRecord = borrowedBook(id, book, "Uncle Bob", "2017-08-20T12:34:56.789Z")

            @DisplayName("GET")
            @Nested
            inner class GetMethod {

                @Test
                fun `responds with book's resource representation for existing available book`() {
                    every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                    val expectedResponse = """
                        {
                          "isbn": "${Books.CLEAN_CODE.isbn}",
                          "title": "${Books.CLEAN_CODE.title}",
                          "authors": ${Books.CLEAN_CODE.authors.toJson()},
                          "numberOfPages": ${Books.CLEAN_CODE.numberOfPages},
                          "_links": {
                            "self": {
                              "href": "/api/books/$id"
                            },
                            "delete": {
                              "href": "/api/books/$id"
                            },
                            "borrow": {
                              "href": "/api/books/$id/borrow"
                            }
                          }
                        }
                    """
                    testClient.get()
                        .uri("/api/books/$id")
                        .exchange()
                        .expectStatus().isOk
                        .expectHeader().contentType(HAL_JSON)
                        .expectBody()
                        .json(expectedResponse)
                }

                @Test
                fun `responds with book's resource representation for existing borrowed book`() {
                    every { bookDataStore.findById(id) } returns Mono.just(borrowedBookRecord)

                    val expectedResponse = """
                        {
                          "isbn": "${Books.CLEAN_CODE.isbn}",
                          "title": "${Books.CLEAN_CODE.title}",
                          "authors": ${Books.CLEAN_CODE.authors.toJson()},
                          "numberOfPages": ${Books.CLEAN_CODE.numberOfPages},
                          "borrowed": {
                            "by": "Uncle Bob",
                            "on": "2017-08-20T12:34:56.789Z"
                          },
                          "_links": {
                            "self": {
                              "href": "/api/books/$id"
                            },
                            "delete": {
                              "href": "/api/books/$id"
                            },
                            "return": {
                              "href": "/api/books/$id/return"
                            }
                          }
                        }
                    """
                    testClient.get()
                        .uri("/api/books/$id")
                        .exchange()
                        .expectStatus().isOk
                        .expectHeader().contentType(HAL_JSON)
                        .expectBody()
                        .json(expectedResponse)
                }

                @Test
                fun `404 NOT FOUND for non-existing book`() {
                    val expectedResponse = """
                        {
                          "path": "/api/books/$id",
                          "status": 404,
                          "error": "Not Found",
                          "timestamp": "2017-08-20T12:34:56.789Z",
                          "correlationId": "$correlationId",
                          "message": "The book with ID: $id does not exist!"
                        }
                    """

                    testClient.get()
                        .uri("/api/books/$id")
                        .header("X-B3-TraceId", correlationId)
                        .exchange()
                        .expectStatus().isNotFound
                        .expectHeader().contentType(APPLICATION_JSON)
                        .expectBody()
                        .json(expectedResponse)
                }

                @Test
                fun `400 BAD REQUEST for malformed ID`() {
                    val expectedResponse = """
                        {
                          "path": "/api/books/malformed-id",
                          "status": 400,
                          "error": "Bad Request",
                          "timestamp": "2017-08-20T12:34:56.789Z",
                          "correlationId": "$correlationId",
                          "message": "The parameter 'malformed-id' is malformed."
                        }
                    """

                    testClient.get()
                        .uri("/api/books/malformed-id")
                        .header("X-B3-TraceId", correlationId)
                        .exchange()
                        .expectStatus().isBadRequest
                        .expectHeader().contentType(APPLICATION_JSON)
                        .expectBody()
                        .json(expectedResponse)
                }

            }

            @DisplayName("DELETE")
            @Nested
            inner class DeleteMethod {

                @Test
                fun `existing book is deleted and response is empty 204 NO CONTENT`() {
                    every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)
                    every { bookDataStore.delete(availableBookRecord) } returns Mono.empty()

                    testClient.delete()
                        .uri("/api/books/$id")
                        .exchange()
                        .expectStatus().isNoContent
                }

                @Test
                fun `404 NOT FOUND for non-existing book`() {
                    val expectedResponse = """
                        {
                          "path": "/api/books/$id",
                          "status": 404,
                          "error": "Not Found",
                          "timestamp": "2017-08-20T12:34:56.789Z",
                          "correlationId": "$correlationId",
                          "message": "The book with ID: $id does not exist!"
                        }
                    """

                    testClient.delete()
                        .uri("/api/books/$id")
                        .header("X-B3-TraceId", correlationId)
                        .exchange()
                        .expectStatus().isNotFound
                        .expectHeader().contentType(APPLICATION_JSON)
                        .expectBody()
                        .json(expectedResponse)
                }

                @Test
                fun `400 BAD REQUEST for malformed ID`() {
                    val expectedResponse = """
                        {
                          "path": "/api/books/malformed-id",
                          "status": 400,
                          "error": "Bad Request",
                          "timestamp": "2017-08-20T12:34:56.789Z",
                          "correlationId": "$correlationId",
                          "message": "The parameter 'malformed-id' is malformed."
                        }
                    """

                    testClient.delete()
                        .uri("/api/books/malformed-id")
                        .header("X-B3-TraceId", correlationId)
                        .exchange()
                        .expectStatus().isBadRequest
                        .expectHeader().contentType(APPLICATION_JSON)
                        .expectBody()
                        .json(expectedResponse)
                }

            }

            @DisplayName("/api/books/{id}/authors")
            @Nested
            inner class BookByIdAuthorsEndpoint {

                @DisplayName("PUT")
                @Nested
                inner class PutMethod {

                    @Test
                    fun `replaces authors of book and responds with its resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                        val expectedResponse = """
                            {
                              "isbn": "${book.isbn}",
                              "title": "${book.title}",
                              "authors": ["Foo", "Bar"],
                              "numberOfPages": ${book.numberOfPages},
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "borrow": {
                                  "href": "/api/books/$id/borrow"
                                }
                              }
                            }
                        """

                        val body = """ { "authors": ["Foo", "Bar"] } """

                        testClient.put()
                            .uri("/api/books/$id/authors")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .body(Mono.just(body), String::class.java)
                            .exchange()
                            .expectStatus().isOk
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/authors",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """

                        val body = """ { "authors": ["Foo", "Bar"] } """

                        testClient.put()
                            .uri("/api/books/$id/authors")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .header("X-B3-TraceId", correlationId)
                            .body(Mono.just(body), String::class.java)
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for missing required properties`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/authors",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The request's body is invalid. See details...",
                              "details": [ "The field 'authors' must not be empty." ]
                            }
                        """

                        testClient.put()
                            .uri("/api/books/$id/authors")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .header("X-B3-TraceId", correlationId)
                            .body(Mono.just(" { } "), String::class.java)
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

                @DisplayName("DELETE")
                @Nested
                inner class DeleteMethod {

                    @Test
                    fun `removes authors from book and responds with its resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                        val expectedResponse = """
                            {
                              "isbn": "${book.isbn}",
                              "title": "${book.title}",
                              "authors": [],
                              "numberOfPages": ${book.numberOfPages},
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "borrow": {
                                  "href": "/api/books/$id/borrow"
                                }
                              }
                            }
                        """
                        testClient.delete()
                            .uri("/api/books/$id/authors")
                            .exchange()
                            .expectStatus().isOk
                            .expectHeader().contentType("application/hal+json")
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/authors",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """
                        testClient.delete()
                            .uri("/api/books/$id/authors")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

            }

            @DisplayName("/api/books/{id}/borrow")
            @Nested
            inner class BookByIdBorrowEndpoint {

                @DisplayName("POST")
                @Nested
                inner class PostMethod {

                    @Test
                    fun `borrows book and responds with its updated resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)
                        val expectedResponse = """
                            {
                              "isbn": "${Books.CLEAN_CODE.isbn}",
                              "title": "${Books.CLEAN_CODE.title}",
                              "authors": ${Books.CLEAN_CODE.authors.toJson()},
                              "numberOfPages": ${Books.CLEAN_CODE.numberOfPages},
                              "borrowed": {
                                "by": "Uncle Bob",
                                "on": "2017-08-20T12:34:56.789Z"
                              },
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "return": {
                                  "href": "/api/books/$id/return"
                                }
                              }
                            }
                        """

                        val body = """ { "borrower": "Uncle Bob" } """

                        testClient.post()
                            .uri("/api/books/$id/borrow")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .body(Mono.just(body), String::class.java)
                            .exchange()
                            .expectStatus().isOk
                            .expectHeader().contentType("application/hal+json")
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `409 CONFLICT for already borrowed book`() {
                        every { bookDataStore.findById(id) } returns Mono.just(borrowedBookRecord)

                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/borrow",
                              "status": 409,
                              "error": "Conflict",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id is already borrowed!"
                            }
                        """

                        val body = """ { "borrower": "Uncle Bob" } """

                        testClient.post()
                            .uri("/api/books/$id/borrow")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .header("X-B3-TraceId", correlationId)
                            .body(Mono.just(body), String::class.java)
                            .exchange()
                            .expectStatus().isEqualTo(CONFLICT)
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/borrow",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """
                        val body = """ { "borrower": "Uncle Bob" } """

                        testClient.post()
                            .uri("/api/books/$id/borrow")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .header("X-B3-TraceId", correlationId)
                            .body(Mono.just(body), String::class.java)
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for missing required properties`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/borrow",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The request's body is invalid. See details...",
                              "details": [ "The field 'borrower' must not be null." ]
                            }
                        """
                        testClient.post()
                            .uri("/api/books/$id/borrow")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .header("X-B3-TraceId", correlationId)
                            .body(Mono.just(" { } "), String::class.java)
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for malformed request`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/borrow",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The request's body could not be read. It is either empty or malformed."
                            }
                        """
                        testClient.post()
                            .uri("/api/books/$id/borrow")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for malformed ID`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/malformed-id/borrow",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The parameter 'malformed-id' is malformed."
                            }
                        """

                        testClient.post()
                            .uri("/api/books/malformed-id/borrow")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

            }

            @DisplayName("/api/books/{id}/numberOfPages")
            @Nested
            inner class BookByIdNumberOfPagesEndpoint {

                @DisplayName("PUT")
                @Nested
                inner class PutMethod {

                    @Test
                    fun `replaces number of pages of book and responds with its resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                        val expectedResponse = """
                            {
                              "isbn": "${book.isbn}",
                              "title": "${book.title}",
                              "authors": ${book.authors.toJson()},
                              "numberOfPages": 128,
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "borrow": {
                                  "href": "/api/books/$id/borrow"
                                }
                              }
                            }
                        """

                        testClient.put()
                            .uri("/api/books/$id/numberOfPages")
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .body(Mono.just(""" { "numberOfPages": 128 } """), String::class.java)
                            .exchange()
                            .expectStatus().isOk
                            .expectHeader().contentType("application/hal+json")
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/numberOfPages",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """

                        testClient.put()
                            .uri("/api/books/$id/numberOfPages")
                            .body(Mono.just(""" { "numberOfPages": 128 } """), String::class.java)
                            .header("X-B3-TraceId", correlationId)
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for missing required properties`() {
                        val idValue = BookId.generate().toString()
                        val expectedResponse = """
                            {
                              "path": "/api/books/$idValue/numberOfPages",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The request's body is invalid. See details...",
                              "details": [ "The field 'numberOfPages' must not be null." ]
                            }
                        """

                        testClient.put()
                            .uri("/api/books/$idValue/numberOfPages")
                            .body(Mono.just(" { } "), String::class.java)
                            .header("X-B3-TraceId", correlationId)
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

                @DisplayName("DELETE")
                @Nested
                inner class DeleteMethod {

                    @Test
                    fun `removes number of pages from book and responds with its resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                        val expectedResponse = """
                            {
                              "isbn": "${book.isbn}",
                              "title": "${book.title}",
                              "authors": ${book.authors.toJson()},
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "borrow": {
                                  "href": "/api/books/$id/borrow"
                                }
                              }
                            }
                        """

                        testClient.delete()
                            .uri("/api/books/$id/numberOfPages")
                            .exchange()
                            .expectStatus().isOk
                            .expectHeader().contentType("application/hal+json")
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/numberOfPages",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """

                        testClient.delete()
                            .uri("/api/books/$id/numberOfPages")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

            }

            @DisplayName("/api/books/{id}/return")
            @Nested
            inner class BookByIdReturnEndpoint {

                @DisplayName("POST")
                @Nested
                inner class PostMethod {

                    @Test
                    fun `returns book and responds with its updated resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(borrowedBookRecord)

                        val expectedResponse = """
                            {
                              "isbn": "${Books.CLEAN_CODE.isbn}",
                              "title": "${Books.CLEAN_CODE.title}",
                              "authors": ${Books.CLEAN_CODE.authors.toJson()},
                              "numberOfPages": ${Books.CLEAN_CODE.numberOfPages},
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "borrow": {
                                  "href": "/api/books/$id/borrow"
                                }
                              }
                            }
                        """
                        testClient.post()
                            .uri("/api/books/$id/return")
                            .exchange()
                            .expectStatus().isOk
                            .expectHeader().contentType("application/hal+json")
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `409 CONFLICT for already returned book`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/return",
                              "status": 409,
                              "error": "Conflict",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id was already returned!"
                            }
                        """

                        testClient.post()
                            .uri("/api/books/$id/return")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isEqualTo(CONFLICT)
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/return",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """
                        testClient.post()
                            .uri("/api/books/$id/return")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for malformed ID`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/malformed-id/return",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The parameter 'malformed-id' is malformed."
                            }
                        """
                        testClient.post()
                            .uri("/api/books/malformed-id/return")
                            .header("X-B3-TraceId", correlationId)
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

            }

            @DisplayName("/api/books/{id}/title")
            @Nested
            inner class BookByIdTitleEndpoint {

                @DisplayName("PUT")
                @Nested
                inner class PutMethod {

                    @Test
                    fun `replaces title of book and responds with its resource representation`() {
                        every { bookDataStore.findById(id) } returns Mono.just(availableBookRecord)

                        val expectedResponse = """
                            {
                              "isbn": "${book.isbn}",
                              "title": "New Title",
                              "authors": ${book.authors.toJson()},
                              "numberOfPages": ${book.numberOfPages},
                              "_links": {
                                "self": {
                                  "href": "/api/books/$id"
                                },
                                "delete": {
                                  "href": "/api/books/$id"
                                },
                                "borrow": {
                                  "href": "/api/books/$id/borrow"
                                }
                              }
                            }
                        """
                        testClient.put()
                            .uri("/api/books/$id/title")
                            .header("X-B3-TraceId", correlationId)
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .body(Mono.just(""" { "title": "New Title" } """), String::class.java)
                            .exchange()
                            .expectStatus().isOk
                            .expectHeader().contentType("application/hal+json")
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `404 NOT FOUND for non-existing book`() {
                        val expectedResponse = """
                            {
                              "path": "/api/books/$id/title",
                              "status": 404,
                              "error": "Not Found",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The book with ID: $id does not exist!"
                            }
                        """
                        testClient.put()
                            .uri("/api/books/$id/title")
                            .header("X-B3-TraceId", correlationId)
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .body(Mono.just(""" { "title": "New Title" } """), String::class.java)
                            .exchange()
                            .expectStatus().isNotFound
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                    @Test
                    fun `400 BAD REQUEST for missing required properties`() {
                        val idValue = BookId.generate().toString()
                        val expectedResponse = """
                            {
                              "path": "/api/books/$idValue/title",
                              "status": 400,
                              "error": "Bad Request",
                              "timestamp": "2017-08-20T12:34:56.789Z",
                              "correlationId": "$correlationId",
                              "message": "The request's body is invalid. See details...",
                              "details": [ "The field 'title' must not be blank." ]
                            }
                        """

                        testClient.put()
                            .uri("/api/books/$idValue/title")
                            .header("X-B3-TraceId", correlationId)
                            .header("Content-Type", APPLICATION_JSON.toString())
                            .body(Mono.just(" { } "), String::class.java)
                            .exchange()
                            .expectStatus().isBadRequest
                            .expectHeader().contentType(APPLICATION_JSON)
                            .expectBody()
                            .json(expectedResponse)
                    }

                }

            }
        }

    }

    private fun availableBook(id: BookId, book: Book) = BookRecord(id, book)

    private fun borrowedBook(
        id: BookId,
        book: Book,
        borrowedBy: String,
        borrowedOn: String
    ) = availableBook(id, book)
        .borrow(Borrower(borrowedBy), OffsetDateTime.parse(borrowedOn))

    private fun List<Author>.toJson() = joinToString(separator = "\", \"", prefix = "[\"", postfix = "\"]")
}
