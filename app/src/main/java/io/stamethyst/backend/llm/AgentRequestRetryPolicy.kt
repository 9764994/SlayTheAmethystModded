package io.stamethyst.backend.llm

import dev.langchain4j.exception.HttpException
import dev.langchain4j.exception.NonRetriableException
import dev.langchain4j.exception.RetriableException
import java.io.IOException
import java.util.concurrent.CancellationException

/** Retry transient transport/provider failures, never invalid requests or cancelled work. */
internal fun isRetryableAgentRequestFailure(error: Throwable): Boolean {
    val causes = ArrayList<Throwable>()
    var current: Throwable? = error
    while (current != null && current !in causes) {
        causes += current
        current = current.cause
    }
    if (causes.any { it is CancellationException || it is InterruptedException || it is NonRetriableException }) {
        return false
    }
    causes.filterIsInstance<HttpException>().firstOrNull()?.let {
        return it.statusCode() in setOf(408, 429) || it.statusCode() in 500..599
    }
    return causes.any { it is IOException || it is RetriableException }
}
