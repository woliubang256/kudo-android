package com.kudo.android

import com.kudo.android.data.DiscourseParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolutionParserTest {
    @Test fun parsesTopicAcceptedAnswerSummary() {
        val detail = DiscourseParser.detail(JSONObject(topicJson(
            "\"accepted_answer\":{\"post_number\":2,\"username\":\"helper\",\"name\":\"热心用户\",\"excerpt\":\"<p>先清理缓存</p>\"}"
        )))

        assertEquals(listOf(2), detail.solutions.map { it.postNumber })
        assertEquals("helper", detail.solutions.single().username)
        assertEquals("热心用户", detail.solutions.single().name)
        assertEquals("先清理缓存", detail.solutions.single().excerpt)
        assertTrue(detail.posts.single { it.number == 2 }.isSolution)
    }

    @Test fun parsesMultipleAcceptedAnswersAndPostFlagsWithoutDuplicates() {
        val detail = DiscourseParser.detail(JSONObject(topicJson(
            "\"accepted_answer\":[{\"post_number\":2,\"username\":\"one\"},{\"post_number\":3,\"username\":\"two\"}]"
        , solutionPostNumbers = setOf(2, 3))))

        assertEquals(listOf(2, 3), detail.solutions.map { it.postNumber })
        assertEquals(setOf(2, 3), detail.posts.filter { it.isSolution }.map { it.number }.toSet())
    }

    @Test fun ignoresInvalidSolutionEntriesButKeepsValidPostFlag() {
        val detail = DiscourseParser.detail(JSONObject(topicJson(
            "\"accepted_answer\":[{\"post_number\":0},{\"post_number\":4,\"username\":\"missing\"}]"
        , solutionPostNumbers = setOf(3))))

        assertEquals(listOf(3, 4), detail.solutions.map { it.postNumber })
        assertEquals(setOf(3, 4), detail.posts.filter { it.isSolution }.map { it.number }.toSet())
    }

    private fun topicJson(extra: String, solutionPostNumbers: Set<Int> = emptySet()): String {
        val posts = (1..4).joinToString(",") { number ->
            val flag = if (number in solutionPostNumbers) ",\"accepted_answer\":true" else ""
            "{\"id\":$number,\"post_number\":$number,\"user_id\":$number,\"username\":\"user$number\",\"name\":\"用户$number\",\"cooked\":\"<p>正文$number</p>\"$flag}"
        }
        return "{\"id\":5,\"title\":\"主题\",$extra,\"post_stream\":{\"stream\":[1,2,3,4],\"posts\":[$posts]}}"
    }
}
