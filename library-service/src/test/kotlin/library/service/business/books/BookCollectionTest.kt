package library.service.business.books

import io.mockk.*
import library.service.business.books.domain.BookRecord
import library.service.business.books.domain.events.*
import library.service.business.books.domain.states.Available
import library.service.business.books.domain.states.Borrowed
import library.service.business.books.domain.types.BookId
import library.service.business.books.domain.types.Borrower
import library.service.business.books.exceptions.BookAlreadyBorrowedException
import library.service.business.books.exceptions.BookAlreadyReturnedException
import library.service.business.books.exceptions.BookNotFoundException
import library.service.business.events.EventDispatcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import utils.Books
import utils.assertThrows
import utils.classification.UnitTest
import utils.clockWithFixedTime
import java.time.OffsetDateTime

@UnitTest
internal class BookCollectionTest {

    val fixedTimestamp = "2017-09-23T12:34:56.789Z"
    val fixedClock = clockWithFixedTime(fixedTimestamp)

    val dataStore: BookDataStore = mockk {
        every { createOrUpdate(any()) } answers { Mono.just(firstArg()) }
    }
    val idGenerator = BookIdGenerator(dataStore)
    val eventDispatcher: EventDispatcher<BookEvent> = mockk()

    val cut = BookCollection(fixedClock, dataStore, idGenerator, eventDispatcher)

    @BeforeEach
    fun setupMocks() {
        every { dataStore.existsById(any()) } returns Mono.just(false)
        every { dataStore.delete(any()) } returns Mono.empty()
        every { eventDispatcher.dispatch(any()) } just Runs
    }

    @Nested
    inner class `adding a book` {

        @Test
        fun `generates a new book ID`() {
            with(cut.addBook(Books.THE_MARTIAN).block()!!) {
                assertThat(id).isNotNull
            }
        }

        @Test
        fun `sets the initial state to available`() {
            with(cut.addBook(Books.THE_MARTIAN).block()!!) {
                assertThat(state).isEqualTo(Available)
            }
        }

        @Test
        fun `stores the book's data`() {
            with(cut.addBook(Books.THE_MARTIAN).block()!!) {
                assertThat(book).isEqualTo(Books.THE_MARTIAN)
            }
        }

        @Test
        fun `dispatches a BookAdded event`() {
            val eventSlot = slot<BookAdded>()
            every { eventDispatcher.dispatch(capture(eventSlot)) } just Runs

            val bookRecord = cut.addBook(Books.THE_MARTIAN).block()!!

            with(eventSlot.captured) {
                assertThat(bookId).isEqualTo("${bookRecord.id}")
                assertThat(timestamp).isEqualTo(fixedTimestamp)
            }
        }

        @Test
        fun `does not dispatch any events in case of an exception`() {
            every { dataStore.createOrUpdate(any()) } returns Mono.error(RuntimeException())
            assertThrows(RuntimeException::class) {
                cut.addBook(Books.THE_MARTIAN).block()
            }
            confirmVerified(eventDispatcher)
        }

    }

    @Nested
    inner class `updating a book` {

        val id = BookId.generate()
        val bookRecord = BookRecord(id, Books.THE_DARK_TOWER_VII)
        val updatedBookRecord = bookRecord.changeNumberOfPages(42)

        @Test
        fun `updates the record in the database`() {
            every { dataStore.findById(id) } returns Mono.just(bookRecord)

            val updatedBook = cut.updateBook(id) { updatedBookRecord }.block()!!

            assertThat(updatedBook).isEqualTo(updatedBook)
            verify { dataStore.createOrUpdate(updatedBook) }
        }

        @Test
        fun `dispatches a BookUpdated event`() {
            val eventSlot = slot<BookUpdated>()
            every { eventDispatcher.dispatch(capture(eventSlot)) } just Runs
            every { dataStore.findById(id) } returns Mono.just(bookRecord)

            cut.updateBook(id) { updatedBookRecord }.block()

            val event = eventSlot.captured
            assertThat(event.bookId).isEqualTo("$id")
            assertThat(event.timestamp).isEqualTo(fixedTimestamp)
        }

        @Test
        fun `throws exception if it was not found in data store`() {
            every { dataStore.findById(id) } returns Mono.empty()
            assertThrows(BookNotFoundException::class) {
                cut.updateBook(id) { updatedBookRecord }.block()
            }
        }

        @Test
        fun `does not dispatch any events in case of an exception`() {
            every { dataStore.createOrUpdate(any()) } returns Mono.error(RuntimeException())
            assertThrows(RuntimeException::class) {
                cut.updateBook(id) { updatedBookRecord }.block()
            }
            confirmVerified(eventDispatcher)
        }

    }

    @Nested
    inner class `getting a book` {

        val id = BookId.generate()
        val bookRecord = BookRecord(id, Books.THE_DARK_TOWER_I)

        @Test
        fun `returns it if it was found in data store`() {
            every { dataStore.findById(id) } returns Mono.just(bookRecord)
            val gotBook = cut.getBook(id).block()!!
            assertThat(gotBook).isEqualTo(bookRecord)
        }

        @Test
        fun `throws exception if it was not found in data store`() {
            every { dataStore.findById(id) } returns Mono.empty()
            assertThrows(BookNotFoundException::class) {
                cut.getBook(id).block()!!
            }
        }

    }

    @Nested
    inner class `getting all books` {

        @Test
        fun `delegates directly to data store`() {
            val bookRecord1 = BookRecord(BookId.generate(), Books.THE_DARK_TOWER_II)
            val bookRecord2 = BookRecord(BookId.generate(), Books.THE_DARK_TOWER_III)
            every { dataStore.findAll() } returns Flux.fromIterable(listOf(bookRecord1, bookRecord2))

            val allBooks = cut.getAllBooks().collectList().block()!!

            assertThat(allBooks).containsExactly(bookRecord1, bookRecord2)
        }

    }

    @Nested
    inner class `removing a book` {

        val id = BookId.generate()
        val bookRecord = BookRecord(id, Books.THE_DARK_TOWER_IV)

        @Test
        fun `deletes it from the data store if found`() {
            every { dataStore.findById(id) } returns Mono.just(bookRecord)
            cut.removeBook(id).block()
            verify { dataStore.delete(bookRecord) }
        }

        @Test
        fun `dispatches a BookRemoved event`() {
            val eventSlot = slot<BookRemoved>()
            every { eventDispatcher.dispatch(capture(eventSlot)) } just Runs
            every { dataStore.findById(id) } returns Mono.just(bookRecord)

            cut.removeBook(id).block()

            val event = eventSlot.captured
            assertThat(event.bookId).isEqualTo("$id")
            assertThat(event.timestamp).isEqualTo(fixedTimestamp)
        }

        @Test
        fun `throws exception if it was not found in data store`() {
            every { dataStore.findById(id) } returns Mono.empty()
            assertThrows(BookNotFoundException::class) {
                cut.removeBook(id).block()
            }
        }

        @Test
        fun `does not dispatch any events in case of an exception`() {
            every { dataStore.findById(id) } returns Mono.error(RuntimeException())
            assertThrows(RuntimeException::class) {
                cut.removeBook(id).block()
            }
            confirmVerified(eventDispatcher)
        }

    }

    @Nested
    inner class `borrowing a book` {

        val id = BookId.generate()
        val availableBookRecord = BookRecord(id, Books.THE_DARK_TOWER_V)
        val borrowedBookRecord = availableBookRecord.borrow(Borrower("Someone"), OffsetDateTime.now())

        @Test
        fun `changes its state and updates it in the data store`() {
            every { dataStore.findById(id) } returns Mono.just(availableBookRecord)

            val borrowedBook = cut.borrowBook(id, Borrower("Someone")).block()!!

            assertThat(borrowedBook.state).isInstanceOf(Borrowed::class.java)
            assertThat(borrowedBook).isEqualTo(borrowedBook)
        }

        @Test
        fun `dispatches a BookBorrowed event`() {
            val eventSlot = slot<BookBorrowed>()
            every { eventDispatcher.dispatch(capture(eventSlot)) } just Runs
            every { dataStore.findById(id) } returns Mono.just(availableBookRecord)

            cut.borrowBook(id, Borrower("Someone")).block()

            val event = eventSlot.captured
            assertThat(event.bookId).isEqualTo("$id")
            assertThat(event.timestamp).isEqualTo(fixedTimestamp)
        }

        @Test
        fun `throws exception if it was not found in data store`() {
            every { dataStore.findById(id) } returns Mono.empty()
            assertThrows(BookNotFoundException::class) {
                cut.borrowBook(id, Borrower("Someone")).block()
            }
        }

        @Test
        fun `throws exception if it is already 'borrowed'`() {
            every { dataStore.findById(id) } returns Mono.just(borrowedBookRecord)
            assertThrows(BookAlreadyBorrowedException::class) {
                cut.borrowBook(id, Borrower("Someone Else")).block()
            }
        }

        @Test
        fun `does not dispatch any events in case of an exception`() {
            every { dataStore.findById(id) } returns Mono.error(RuntimeException())
            assertThrows(RuntimeException::class) {
                cut.borrowBook(id, Borrower("Someone Else")).block()
            }
            confirmVerified(eventDispatcher)
        }

    }

    @Nested
    inner class `returning a book` {

        val id = BookId.generate()
        val availableBookRecord = BookRecord(id, Books.THE_DARK_TOWER_VI)
        val borrowedBookRecord = availableBookRecord.borrow(Borrower("Someone"), OffsetDateTime.now())

        @Test
        fun `changes its state and updates it in the data store`() {
            every { dataStore.findById(id) } returns Mono.just(borrowedBookRecord)

            val result = cut.returnBook(id).block()!!

            assertThat(result.state).isEqualTo(Available)
            assertThat(result).isEqualTo(availableBookRecord)
        }

        @Test
        fun `dispatches a BookReturned event`() {
            val eventSlot = slot<BookReturned>()
            every { eventDispatcher.dispatch(capture(eventSlot)) } just Runs
            every { dataStore.findById(id) } returns Mono.just(borrowedBookRecord)

            cut.returnBook(id).block()

            val event = eventSlot.captured
            assertThat(event.bookId).isEqualTo("$id")
            assertThat(event.timestamp).isEqualTo(fixedTimestamp)
        }

        @Test
        fun `throws exception if it was not found in data store`() {
            every { dataStore.findById(id) } returns Mono.empty()
            assertThrows(BookNotFoundException::class) {
                cut.returnBook(id).block()
            }
        }

        @Test
        fun `throws exception if it is already 'returned'`() {
            every { dataStore.findById(id) } returns Mono.just(availableBookRecord)
            assertThrows(BookAlreadyReturnedException::class) {
                cut.returnBook(id).block()
            }
        }

        @Test
        fun `does not dispatch any events in case of an exception`() {
            every { dataStore.findById(id) } returns Mono.error(RuntimeException())
            assertThrows(RuntimeException::class) {
                cut.returnBook(id).block()
            }
            confirmVerified(eventDispatcher)
        }

    }

}
