package space.nicart.watchbox.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WbSearchFieldInputTest {
    @Test
    fun `passwords are masked by the transformation shared with the closed TV preview`() {
        val secret = "  s3cret pass  "
        listOf(KeyboardType.Password, KeyboardType.NumberPassword).forEach { type ->
            val transformation = textFieldVisualTransformation(type)
            val preview = transformation.filter(AnnotatedString(secret)).text.text

            assertEquals("\u2022".repeat(secret.length), preview)
            assertFalse(preview.contains("s3cret"))
            assertEquals("", transformation.filter(AnnotatedString("")).text.text)
        }
    }

    @Test
    fun `ordinary search email and OTP inputs are not masked`() {
        listOf(KeyboardType.Text, KeyboardType.Email, KeyboardType.Number).forEach { type ->
            assertEquals(
                "me@example.com",
                textFieldVisualTransformation(type).filter(AnnotatedString("me@example.com")).text.text,
            )
        }
    }
}
