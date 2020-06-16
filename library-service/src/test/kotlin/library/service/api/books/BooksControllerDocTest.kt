package library.service.api.books

import brave.Tracer
import brave.Tracing
import io.mockk.every
import io.mockk.mockk
import library.service.business.books.BookCollection
import library.service.business.books.domain.BookRecord
import library.service.business.books.domain.composites.Book
import library.service.business.books.domain.states.Borrowed
import library.service.business.books.domain.types.BookId
import library.service.business.books.domain.types.Borrower
import library.service.business.books.exceptions.BookAlreadyBorrowedException
import library.service.business.books.exceptions.BookAlreadyReturnedException
import library.service.business.books.exceptions.BookNotFoundException
import library.service.security.SecurityConfiguration
import library.service.security.UserContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.hateoas.MediaTypes.HAL_JSON
import org.springframework.http.HttpStatus.CONFLICT
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import utils.Books
import utils.ResetMocksAfterEachTest
import utils.classification.IntegrationTest
import utils.document
import java.time.OffsetDateTime


@IntegrationTest
@ResetMocksAfterEachTest
@WebFluxTest(BooksController::class)
@AutoConfigureRestDocs("build/generated-snippets/books")
internal class BooksControllerDocTest(
    @Autowired val bookCollection: BookCollection,
    @Autowired val testClient: WebTestClient,
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
        fun bookCollection(): BookCollection = mockk()
    }

    @BeforeEach
    fun setupMocks() {
        every { userContext.isCurator() } returns Mono.just(true)
    }

    // POST on /api/books

    @Test
    fun `post book - created`() {
        val createdBook = availableBook()
        every { bookCollection.addBook(any()) } returns Mono.just(createdBook)
        val body = """
                    {
                        "isbn": "${createdBook.book.isbn}",
                        "title": "${createdBook.book.title}"
                    }
                """

        testClient.post()
            .uri("/api/books")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just(body), String::class.java)
            .exchange()
            .expectStatus().isCreated
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("postBook-created"))
    }

    @Test
    fun `post book - bad request`() {
        testClient.post()
            .uri("/api/books")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just(""" { } """), String::class.java)
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader().contentType(APPLICATION_JSON_VALUE)
            .expectBody()
            .consumeWith(document("error-example"))
    }

    // PUT on /api/books/{bookId}/authors

    @Test
    fun `put book authors - ok`() {
        val book = Books.CLEAN_CODE
        val bookRecord = availableBook(book = book)

        every { bookCollection.updateBook(any(), any()) } returns Mono.just(bookRecord)

        val authorsValue = book.authors.joinToString(prefix = "\"", separator = "\", \"", postfix = "\"")

        testClient.put()
            .uri("/api/books/3c15641e-2598-41f5-9097-b37e2d768be5/authors")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just("""{ "authors": [$authorsValue] }"""), String::class.java)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("putBookAuthors-ok"))
    }

    // DELETE on /api/books/{bookId}/authors

    @Test
    fun `delete book authors - ok`() {
        val book = Books.CLEAN_CODE.copy(authors = emptyList())
        val bookRecord = availableBook(book = book)

        every { bookCollection.updateBook(any(), any()) } returns Mono.just(bookRecord)

        testClient.delete()
            .uri("/api/books/3c15641e-2598-41f5-9097-b37e2d768be5/authors")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("deleteBookAuthors-ok"))
    }

    // PUT on /api/books/{bookId}/numberOfPages

    @Test
    fun `put book number of pages - ok`() {
        val book = Books.CLEAN_CODE
        val bookRecord = availableBook(book = book)

        every { bookCollection.updateBook(any(), any()) } returns Mono.just(bookRecord)

        val numberOfPages = book.numberOfPages

        testClient.put()
            .uri("/api/books/3c15641e-2598-41f5-9097-b37e2d768be5/numberOfPages")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just("""{ "numberOfPages": $numberOfPages }"""), String::class.java)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("putBookNumberOfPages-ok"))
    }

    // DELETE on /api/books/{bookId}/numberOfPages

    @Test
    fun `delete book number of pages - ok`() {
        val book = Books.CLEAN_CODE.copy(numberOfPages = null)
        val bookRecord = availableBook(book = book)

        every { bookCollection.updateBook(any(), any()) } returns Mono.just(bookRecord)

        testClient.delete()
            .uri("/api/books/3c15641e-2598-41f5-9097-b37e2d768be5/numberOfPages")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("deleteBookNumberOfPages-ok"))
    }

    // PUT on /api/books/{bookId}/title

    @Test
    fun `put book title - ok`() {
        val book = Books.CLEAN_CODE
        val bookRecord = availableBook(book = book)

        every { bookCollection.updateBook(any(), any()) } returns Mono.just(bookRecord)

        val title = book.title

        testClient.put()
            .uri("/api/books/3c15641e-2598-41f5-9097-b37e2d768be5/title")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just("""{ "title": "$title" }"""), String::class.java)
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("putBookTitle-ok"))
    }

    // GET on /api/books

    @Test
    fun `getting all books - 0 books`() {
        every { bookCollection.getAllBooks() } returns Flux.empty()
        testClient.get()
            .uri("/api/books")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("getAllBooks-0Books"))
    }

    @Test
    fun `getting all books - 2 books`() {
        every { bookCollection.getAllBooks() } returns Flux.fromIterable(listOf(availableBook(), borrowedBook()))
        testClient.get()
            .uri("/api/books")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("getAllBooks-2Books"))
    }

    // GET on /api/books/{id}

    @Test
    fun `getting book by ID - found available`() {
        val book = availableBook()
        every { bookCollection.getBook(book.id) } returns Mono.just(book)
        testClient.get()
            .uri("/api/books/${book.id}")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("getBookById-foundAvailable"))
    }

    @Test
    fun `getting book by ID - found borrowed`() {
        val book = borrowedBook()
        every { bookCollection.getBook(book.id) } returns Mono.just(book)
        testClient.get()
            .uri("/api/books/${book.id}")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(HAL_JSON)
            .expectBody()
            .consumeWith(document("getBookById-foundBorrowed"))
    }

    @Test
    fun `getting book by ID - not found`() {
        val id = BookId.generate()
        every { bookCollection.getBook(id) } returns Mono.error { BookNotFoundException(id) }

        testClient.get()
            .uri("/api/books/$id")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(APPLICATION_JSON_VALUE)
            .expectBody()
            .consumeWith(document("getBookById-notFound"))
    }

    // DELETE on /api/books/{id}

    @Test
    fun `deleting book by ID - found`() {
        val id = BookId.generate()
        every { bookCollection.removeBook(id) } returns Mono.empty()
        testClient.delete()
            .uri("/api/books/$id")
            .exchange()
            .expectStatus().isNoContent
            .expectBody()
            .consumeWith(document("deleteBookById-found"))
    }

    @Test
    fun `deleting book by ID - not found`() {
        val id = BookId.generate()
        every { bookCollection.removeBook(id) } returns Mono.error(BookNotFoundException(id))
        testClient.delete()
            .uri("/api/books/$id")
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().contentType(APPLICATION_JSON_VALUE)
            .expectBody()
            .consumeWith(document("deleteBookById-notFound"))
    }

    // POST on /api/books/{id}/borrow

    @Test
    fun `borrowing book by ID - found available`() {
        val book = borrowedBook()
        val borrower = (book.state as Borrowed).by
        every { bookCollection.borrowBook(book.id, borrower) } returns Mono.just(book)

        testClient.post()
            .uri("/api/books/${book.id}/borrow")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just(""" { "borrower": "$borrower" } """), String::class.java)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .consumeWith(document("borrowBookById-foundAvailable"))
    }

    @Test
    fun `borrowing book by ID - found already borrowed`() {
        val id = BookId.generate()
        val borrower = borrower()
        every {
            bookCollection.borrowBook(id, borrower)
        } returns Mono.error(BookAlreadyBorrowedException(id))

        testClient.post()
            .uri("/api/books/$id/borrow")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just(""" { "borrower": "$borrower" } """), String::class.java)
            .exchange()
            .expectStatus().isEqualTo(CONFLICT)
            .expectBody()
            .consumeWith(document("borrowBookById-foundAlreadyBorrowed"))
    }

    @Test
    fun `borrowing book by ID - not found`() {
        val id = BookId.generate()
        val borrower = borrower()
        every {
            bookCollection.borrowBook(id, borrower)
        } returns Mono.error(BookNotFoundException(id))

        testClient.post()
            .uri("/api/books/$id/borrow")
            .header("Content-Type", APPLICATION_JSON_VALUE)
            .body(Mono.just(""" { "borrower": "$borrower" } """), String::class.java)
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .consumeWith(document("borrowBookById-notFound"))
    }

    // POST on /api/books/{id}/return

    @Test
    fun `returning book by ID - found borrowed`() {
        val book = availableBook()
        every { bookCollection.returnBook(book.id) } returns Mono.just(book)

        testClient.post()
            .uri("/api/books/${book.id}/return")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .consumeWith(document("returnBookById-foundBorrowed"))
    }

    @Test
    fun `returning book by ID - found already borrowed`() {
        val id = BookId.generate()
        every {
            bookCollection.returnBook(id)
        } returns Mono.error(BookAlreadyReturnedException(id))

        testClient.post()
            .uri("/api/books/$id/return")
            .exchange()
            .expectStatus().isEqualTo(CONFLICT)
            .expectBody()
            .consumeWith(document("returnBookById-foundAlreadyReturned"))
    }

    @Test
    fun `returning book by ID - not found`() {
        val id = BookId.generate()
        every {
            bookCollection.returnBook(id)
        } returns Mono.error(BookNotFoundException(id))

        testClient.post()
            .uri("/api/books/$id/return")
            .exchange()
            .expectStatus().isNotFound
            .expectBody()
            .consumeWith(document("returnBookById-notFound"))
    }

    // utility methods

    private fun borrowedBook(id: BookId = BookId.generate()): BookRecord {
        val borrowedBy = borrower()
        val borrowedOn = OffsetDateTime.parse("2017-08-21T12:34:56.789Z")
        return availableBook(id).borrow(borrowedBy, borrowedOn)
    }

    private fun borrower() = Borrower("slu")

    private fun availableBook(
        id: BookId = BookId.generate(),
        book: Book = Books.CLEAN_CODE
    ) = BookRecord(id, book)

}