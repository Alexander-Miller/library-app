package library.service.business.books

import library.service.business.books.domain.BookRecord
import library.service.business.books.domain.composites.Book
import library.service.business.books.domain.events.*
import library.service.business.books.domain.types.BookId
import library.service.business.books.domain.types.Borrower
import library.service.business.books.exceptions.BookAlreadyBorrowedException
import library.service.business.books.exceptions.BookAlreadyReturnedException
import library.service.business.books.exceptions.BookNotFoundException
import library.service.business.events.EventDispatcher
import library.service.logging.LogMethodEntryAndExit
import library.service.security.annotations.CanBeExecutedByAnyUser
import library.service.security.annotations.CanOnlyBeExecutedByCurators
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.OffsetDateTime

/**
 * This represents the book collection of this library application instance.
 *
 * It offers functions for common actions taken with a collection of books:
 *
 * - adding books
 * - finding books
 * - deleting books
 * - borrowing & returning books
 */
@Service
@LogMethodEntryAndExit
class BookCollection(
    private val clock: Clock,
    private val dataStore: BookDataStore,
    private val idGenerator: BookIdGenerator,
    private val eventDispatcher: EventDispatcher<BookEvent>
) {

    /**
     * Adds the given [Book] to the collection.
     *
     * The [Book] is stored in the collection's [BookDataStore] for future
     * usage.
     *
     * Dispatches a [BookAdded] domain event.
     *
     * @param book the book to add to the collection
     * @return the [BookRecord] for the created book data
     */
    @CanOnlyBeExecutedByCurators
    fun addBook(book: Book): Mono<BookRecord> {
        return idGenerator.generate().flatMap { bookId ->
            dataStore.createOrUpdate(BookRecord(bookId, book))
        }.doOnNext { bookRecord ->
            dispatch(bookAddedEvent(bookRecord))
        }
    }

    private fun bookAddedEvent(bookRecord: BookRecord) = BookAdded(timestamp = now(), bookRecord = bookRecord)

    /**
     * Updates a [BookRecord] from the collection by applying the given
     * update function to it. The [BookRecord] is identified by its unique ID.
     *
     * The [BookRecord] is looked up in the collection's [BookDataStore]. If
     * the [BookDataStore] does not contain a book for that ID, an exception
     * is thrown.
     *
     * @param id the unique ID to look up
     * @param updateFunction the function used for updating the book record
     * @throws BookNotFoundException in case there is no book for the given ID
     */
    @CanOnlyBeExecutedByCurators
    fun updateBook(id: BookId, updateFunction: (BookRecord) -> BookRecord): Mono<BookRecord> {
        return getBook(id).map { bookRecord ->
            updateFunction(bookRecord)
        }.flatMap { updatedRecord ->
            dataStore.createOrUpdate(updatedRecord)
        }.doOnNext { savedAndUpdatedRecord ->
            dispatch(bookUpdatedEvent(savedAndUpdatedRecord))
        }
    }

    private fun bookUpdatedEvent(bookRecord: BookRecord) = BookUpdated(timestamp = now(), bookRecord = bookRecord)

    /**
     * Gets a [BookRecord] from the collection by its unique ID.
     *
     * The [BookRecord] is looked up in the collection's [BookDataStore]. If
     * the [BookDataStore] does not contain a book for that ID, an exception
     * is thrown.
     *
     * @param id the unique ID to look up
     * @return the [BookRecord] for the given ID
     * @throws BookNotFoundException in case there is no book for the given ID
     */
    @CanBeExecutedByAnyUser
    fun getBook(id: BookId): Mono<BookRecord> {
        return dataStore.findById(id).switchIfEmpty(
            Mono.error { BookNotFoundException(id) }
        )
    }

    /**
     * Gets a list of all [BookRecord] currently part of this collection.
     *
     * The books are looked up in the collection's [BookDataStore]. If there
     * are no books in the data store, an empty list is returned.
     *
     * @return a list of all [BookRecord]
     */
    @CanBeExecutedByAnyUser
    fun getAllBooks(): Flux<BookRecord> {
        return dataStore.findAll()
    }

    /**
     * Removes a [BookRecord] from the collection by its unique ID.
     *
     * The book needs to exist in the collection's [BookDataStore] in order to
     * remove it. If there is no book for the given ID an exception is thrown.
     *
     * Dispatches a [BookRemoved] domain event.
     *
     * @param id the unique ID of the book to delete
     * @throws BookNotFoundException in case there is no book for the given ID
     */
    @CanOnlyBeExecutedByCurators
    fun removeBook(id: BookId): Mono<Void> {
        return getBook(id).doOnNext { bookRecord ->
            dispatch(bookRemovedEvent(bookRecord))
        }.flatMap { bookRecord ->
            dataStore.delete(bookRecord)
        }
    }

    private fun bookRemovedEvent(bookRecord: BookRecord) = BookRemoved(timestamp = now(), bookRecord = bookRecord)

    /**
     * Tries to borrow a [BookRecord] with the given unique ID for the given
     * [Borrower].
     *
     * The book needs to exist in the collection's [BookDataStore] in order to
     * borrow it. It also needs to be _available_ for borrowing. If either of
     * those conditions is not met, an exception is thrown.
     *
     * Dispatches a [BookBorrowed] domain event.
     *
     * @param id the unique ID of the book to borrow
     * @param borrower the [Borrower] who is trying to borrow the book
     * @return the borrowed and updated [BookRecord] instance
     * @throws BookNotFoundException in case there is no book for the given ID
     * @throws BookAlreadyBorrowedException in case the book is already borrowed
     */
    @CanBeExecutedByAnyUser
    fun borrowBook(id: BookId, borrower: Borrower): Mono<BookRecord> {
        return getBook(id).map {
            it.borrow(borrower, now())
        }.flatMap {
            dataStore.createOrUpdate(it)
        }.doOnNext { updatedRecord ->
            dispatch(bookBorrowedEvent(updatedRecord))
        }.onErrorResume { e ->
            Mono.error(e)
        }
    }

    private fun bookBorrowedEvent(bookRecord: BookRecord) = BookBorrowed(timestamp = now(), bookRecord = bookRecord)

    /**
     * Tries to return a [BookRecord] with the given unique ID.
     *
     * The book needs to exist in the collection's [BookDataStore] in order to
     * return it. It also needs to be currently _borrowed_. If either of those
     * conditions is not met, an exception is thrown.
     *
     * Dispatches a [BookReturned] domain event.
     *
     * @param id the unique ID of the book to borrow
     * @return the returned and updated [BookRecord] instance
     * @throws BookNotFoundException in case there is no book for the given ID
     * @throws BookAlreadyReturnedException in case the book is already returned
     */
    @CanBeExecutedByAnyUser
    fun returnBook(id: BookId): Mono<BookRecord> {
        return getBook(id).map { bookRecord ->
            bookRecord.`return`()
        }.flatMap { returnedRecord ->
            dataStore.createOrUpdate(returnedRecord)
        }.doOnNext { updatedRecord ->
            dispatch(bookReturnedEvent(updatedRecord))
        }
    }

    private fun bookReturnedEvent(bookRecord: BookRecord) = BookReturned(timestamp = now(), bookRecord = bookRecord)

    private fun dispatch(event: BookEvent) = eventDispatcher.dispatch(event)
    private fun now() = OffsetDateTime.now(clock)

}