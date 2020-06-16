package library.service.api.books

import library.service.business.books.domain.BookRecord
import library.service.business.books.domain.states.Available
import library.service.business.books.domain.states.Borrowed
import library.service.security.UserContext
import org.springframework.hateoas.CollectionModel
import org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.linkTo
import org.springframework.hateoas.server.reactive.WebFluxLinkBuilder.methodOn
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * Component responsible for converting a [BookRecord] into a [BookResource].
 *
 * This includes transforming the data from one class to another and adding the
 * correct links depending on the [BookRecord] state.
 */
@Component
class BookResourceAssembler(
    private val currentUser: UserContext
) {

    private val booksController = BooksController::class.java

    fun toModel(bookRecord: BookRecord): Mono<BookResource> {
        val ctl = methodOn(BooksController::class.java)
        val bookId = bookRecord.id.toUuid()

        val selfLink = linkTo(ctl.getBook(bookId)).withSelfRel().toMono()

        val borrowLink = when (bookRecord.state) {
            is Available -> linkTo(ctl).slash("borrow").withRel("borrow")
            is Borrowed -> linkTo(ctl).slash("return").withRel("return")
        }.toMono()

        val deleteLink = currentUser.isCurator().flatMap {
            when (it) {
                true -> linkTo(ctl.deleteBook(bookId)).withRel("delete").toMono()
                false -> Mono.empty()
            }
        }
        val allLinks = Flux.concat(selfLink, borrowLink, deleteLink)

        return allLinks.collectList().map { links ->
            instantiateModel(bookRecord).apply { add(links) }
        }
    }

    fun toCollectionModel(entities: Flux<out BookRecord>): Mono<CollectionModel<BookResource>> {
        return linkTo(methodOn(booksController).getBooks()).withSelfRel().toMono()
            .flatMap { selfLink ->
                entities.flatMap { toModel(it) }
                    .collectList()
                    .map { CollectionModel.of(it, selfLink) }
            }
    }

    fun instantiateModel(bookRecord: BookRecord): BookResource {
        val bookState = bookRecord.state
        return BookResource(
            isbn = bookRecord.book.isbn.toString(),
            title = bookRecord.book.title.toString(),
            authors = bookRecord.book.authors.map { it.toString() },
            numberOfPages = bookRecord.book.numberOfPages,
            borrowed = when (bookState) {
                is Available -> null
                is Borrowed -> Borrowed(by = "${bookState.by}", on = "${bookState.on}")
            }
        )
    }
}
