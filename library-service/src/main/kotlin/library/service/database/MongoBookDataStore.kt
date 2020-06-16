package library.service.database

import library.service.business.books.BookDataStore
import library.service.business.books.domain.BookRecord
import library.service.business.books.domain.types.BookId
import library.service.logging.LogMethodEntryAndExit
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Service
@LogMethodEntryAndExit
class MongoBookDataStore(
        private val repository: BookRepository,
        private val bookRecordToDocumentMapper: Mapper<BookRecord, BookDocument>,
        private val bookDocumentToRecordMapper: Mapper<BookDocument, BookRecord>
) : BookDataStore {

    override fun createOrUpdate(bookRecord: BookRecord): Mono<BookRecord> {
        val document = bookRecordToDocumentMapper.map(bookRecord)
        val updatedDocument = repository.save(document)
        return updatedDocument.map { bookDocumentToRecordMapper.map(it) }
    }

    override fun delete(bookRecord: BookRecord): Mono<Void> {
        return repository.deleteById(bookRecord.id.toUuid())
    }

    override fun findById(id: BookId): Mono<BookRecord> {
        return repository.findById(id.toUuid())
                .map(bookDocumentToRecordMapper::map)
    }

    override fun findAll(): Flux<BookRecord> {
        return repository.findAll().map(bookDocumentToRecordMapper::map)
    }

    override fun existsById(bookId: BookId): Mono<Boolean> {
        return repository.existsById(bookId.toUuid())
    }

}