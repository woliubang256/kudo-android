package com.kudo.android

import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.objects
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in read-only probe of post attribution fields, without recording credentials or bodies. */
class PostMetadataLiveProbeTest {
    @Test fun inspectPostReplyAndDeviceFields() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("postMetadataLive") == "true")
        val app = (instrumentation.targetContext.applicationContext as KudoApplication).container
        val records = JSONArray()
        for (topicId in listOf(3117423, 3057096)) {
            val entry = JSONObject().put("topicId", topicId)
            try {
                val raw = withTimeout(35_000) { app.client.get("/t/$topicId.json") }
                val posts = raw.getJSONObject("post_stream").getJSONArray("posts").objects()
                entry.put("posts", JSONArray().apply {
                    posts.forEach { post -> put(JSONObject().apply {
                        put("post_number", post.opt("post_number"))
                        put("keys", JSONArray(post.keys().asSequence().toList()))
                        post.keys().asSequence().filter { key ->
                            key.startsWith("reply_to_") || key.contains("device", true) ||
                                key.contains("mobile", true) || key.contains("client", true) || key == "custom_fields"
                        }.forEach { key -> put(key, post.opt(key)) }
                    }) }
                })
            } catch (failure: Exception) {
                entry.put("error", failure.javaClass.simpleName + ": " + failure.message)
            }
            records.put(entry)
        }
        val output = records.toString(2)
        File(instrumentation.targetContext.getExternalFilesDir(null), "post-metadata-contract.json").writeText(output, Charsets.UTF_8)
        println(output)
    }
}
