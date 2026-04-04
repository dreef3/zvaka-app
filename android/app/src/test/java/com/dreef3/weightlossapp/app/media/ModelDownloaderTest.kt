package com.dreef3.weightlossapp.app.media

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelDownloaderTest {
    @Test
    fun downloadFromWritesModelFile() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", 5)
                .setBody("abcde"),
        )
        server.start()

        val storage = ModelStorage(modelDirectoryOverride = createTempDirectory().toFile())
        val downloader = ModelDownloader(storage)

        val result = downloader.downloadFrom(
            url = server.url("/model.litertlm").toString(),
            expectedTotalBytes = 5,
        )

        assertTrue(result.isSuccess)
        assertEquals("abcde", storage.defaultModelFile.readText())

        server.shutdown()
    }

    @Test
    fun downloadFromResumesPartialFileUsingRange() = runTest {
        val server = MockWebServer()
        val body = "abcdefghij".toByteArray()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range == "bytes=4-") {
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 4-9/10")
                        .setBody(okio.Buffer().write(body.copyOfRange(4, body.size)))
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Length", 10)
                        .setBody(okio.Buffer().write(body))
                }
            }
        }
        server.start()

        val storage = ModelStorage(modelDirectoryOverride = createTempDirectory().toFile())
        val downloader = ModelDownloader(storage)
        val partial = File(storage.modelDirectory, "${ModelStorage.DEFAULT_MODEL_FILE_NAME}.part")
        partial.parentFile?.mkdirs()
        partial.writeBytes(body.copyOfRange(0, 4))

        val result = downloader.downloadFrom(
            url = server.url("/model.litertlm").toString(),
            expectedTotalBytes = 10,
        )

        assertTrue(result.isSuccess)
        assertArrayEquals(body, storage.defaultModelFile.readBytes())

        server.shutdown()
    }
}
