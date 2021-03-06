package io.ktor.server.testing

import io.ktor.application.*
import io.ktor.content.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.server.engine.*
import kotlinx.coroutines.experimental.io.*
import java.io.*

class TestApplicationRequest(
        call: ApplicationCall,
        var method: HttpMethod = HttpMethod.Get,
        var uri: String = "/",
        var version: String = "HTTP/1.1"
) : BaseApplicationRequest(call) {

    var protocol: String = "http"

    override val local = object : RequestConnectionPoint {
        override val uri: String
            get() = this@TestApplicationRequest.uri

        override val method: HttpMethod
            get() = this@TestApplicationRequest.method

        override val scheme: String
            get() = protocol

        override val port: Int
            get() = header(HttpHeaders.Host)?.substringAfter(":", "80")?.toInt() ?: 80

        override val host: String
            get() = header(HttpHeaders.Host)?.substringBefore(":") ?: "localhost"

        override val remoteHost: String
            get() = "localhost"

        override val version: String
            get() = this@TestApplicationRequest.version
    }

    var bodyChannel: ByteReadChannel? = null
    var bodyBytes: ByteArray = ByteArray(0)
    var body: String
        get() = bodyBytes.toString(Charsets.UTF_8)
        set(newValue) {
            bodyBytes = newValue.toByteArray(Charsets.UTF_8)
        }

    var multiPartEntries: List<PartData> = emptyList()

    override val queryParameters by lazy(LazyThreadSafetyMode.NONE) { parseQueryString(queryString()) }

    private var headersMap: MutableMap<String, MutableList<String>>? = hashMapOf()
    fun addHeader(name: String, value: String) {
        val map = headersMap ?: throw Exception("Headers were already acquired for this request")
        map.getOrPut(name, { arrayListOf() }).add(value)
    }

    override val headers : Headers by lazy(LazyThreadSafetyMode.NONE) {
        val map = headersMap ?: throw Exception("Headers were already acquired for this request")
        headersMap = null
        Headers.build {
            map.forEach { (name, values) ->
                appendAll(name, values)
            }
        }
    }

    override val cookies = RequestCookies(this)

    override fun receiveContent() = TestIncomingContent(this)
    override fun receiveChannel(): ByteReadChannel {
        return bodyChannel ?: ByteReadChannel(bodyBytes)
    }

    class TestIncomingContent(private val request: TestApplicationRequest) : IncomingContent {
        override val headers: Headers = request.headers

        override fun readChannel() = ByteReadChannel(request.bodyBytes)
        override fun inputStream(): InputStream = ByteArrayInputStream(request.bodyBytes)

        override fun multiPartData(): MultiPartData = object : MultiPartData {
            private val items by lazy { request.multiPartEntries.iterator() }

            override val parts: Sequence<PartData>
                get() = when {
                    request.isMultipart() -> request.multiPartEntries.asSequence()
                    else -> throw IOException("The request content is not multipart encoded")
                }

            override suspend fun readPart() = when {
                !request.isMultipart() -> throw IOException("The request content is not multipart encoded")
                items.hasNext() -> items.next()
                else -> null
            }
        }
    }
}