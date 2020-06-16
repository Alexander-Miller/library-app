package utils

import org.springframework.restdocs.operation.preprocess.Preprocessors
import org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint
import org.springframework.restdocs.snippet.Snippet
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.document
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

fun document(identifier: String, vararg snippets: Snippet): Consumer<EntityExchangeResult<ByteArray>> {
    return document(
        identifier,
        Preprocessors.preprocessRequest(prettyPrint()),
        Preprocessors.preprocessResponse(prettyPrint()),
        *snippets
    )
}