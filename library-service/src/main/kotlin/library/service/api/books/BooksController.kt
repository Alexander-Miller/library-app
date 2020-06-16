package library.service.api.books

import library.service.api.books.payload.*
import library.service.business.books.BookCollection
import library.service.business.books.domain.composites.Book
import library.service.business.books.domain.types.*
import library.service.logging.LogMethodEntryAndExit
import org.springframework.hateoas.CollectionModel
import org.springframework.http.HttpStatus.*
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.util.*
import javax.validation.Valid

@Validated
@RestController
@CrossOrigin
@RequestMapping("/api/books")
@LogMethodEntryAndExit
class BooksController(
    private val collection: BookCollection,
    private val assembler: BookResourceAssembler
) {

    @GetMapping
    @ResponseStatus(OK)
    fun getBooks(): Mono<CollectionModel<BookResource>> {
        val allBookRecords = collection.getAllBooks()
        return assembler.toCollectionModel(allBookRecords)
    }

    @PostMapping
    @ResponseStatus(CREATED)
    fun postBook(@Valid @RequestBody body: CreateBookRequest): Mono<BookResource> {
        val book = Book(
            isbn = Isbn13.parse(body.isbn!!),
            title = Title(body.title!!),
            authors = emptyList(),
            numberOfPages = null
        )
        val bookRecord = collection.addBook(book)
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @PutMapping("/{id}/title")
    @ResponseStatus(OK)
    fun putBookTitle(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateTitleRequest
    ): Mono<BookResource> {
        val bookRecord = collection.updateBook(BookId(id)) {
            it.changeTitle(Title(body.title!!))
        }
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @PutMapping("/{id}/authors")
    @ResponseStatus(OK)
    fun putBookAuthors(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateAuthorsRequest
    ): Mono<BookResource> {
        val bookRecord = collection.updateBook(BookId(id)) { record ->
            record.changeAuthors(body.authors!!.map { Author(it) })
        }
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @DeleteMapping("/{id}/authors")
    @ResponseStatus(OK)
    fun deleteBookAuthors(@PathVariable id: UUID): Mono<BookResource> {
        val bookRecord = collection.updateBook(BookId(id)) { record ->
            record.changeAuthors(emptyList())
        }
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @PutMapping("/{id}/numberOfPages")
    @ResponseStatus(OK)
    fun putBookNumberOfPages(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UpdateNumberOfPagesRequest
    ): Mono<BookResource> {
        val bookRecord = collection.updateBook(BookId(id)) {
            it.changeNumberOfPages(body.numberOfPages)
        }
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @DeleteMapping("/{id}/numberOfPages")
    @ResponseStatus(OK)
    fun deleteBookNumberOfPages(@PathVariable id: UUID): Mono<BookResource> {
        val bookRecord = collection.updateBook(BookId(id)) {
            it.changeNumberOfPages(null)
        }
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @GetMapping("/{id}")
    @ResponseStatus(OK)
    fun getBook(@PathVariable id: UUID): Mono<BookResource> {
        val bookRecord = collection.getBook(BookId(id))
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    fun deleteBook(@PathVariable id: UUID): Mono<Void> {
        return collection.removeBook(BookId(id))
    }

    @PostMapping("/{id}/borrow")
    @ResponseStatus(OK)
    fun postBorrowBook(
        @PathVariable id: UUID,
        @Valid @RequestBody body: BorrowBookRequest
    ): Mono<BookResource> {
        val bookRecord = collection.borrowBook(BookId(id), Borrower(body.borrower!!))
        return bookRecord.flatMap { assembler.toModel(it) }
    }

    @PostMapping("/{id}/return")
    @ResponseStatus(OK)
    fun postReturnBook(@PathVariable id: UUID): Mono<BookResource> {
        val bookRecord = collection.returnBook(BookId(id))
        return bookRecord.flatMap { assembler.toModel(it) }
    }

}