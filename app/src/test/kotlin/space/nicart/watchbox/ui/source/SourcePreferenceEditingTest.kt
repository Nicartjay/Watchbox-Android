package space.nicart.watchbox.ui.source

import android.text.InputType
import androidx.compose.ui.text.input.KeyboardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourcePreferenceEditingTest {
    @Test
    fun `a password is submitted only on Enter with its whitespace preserved and then cleared`() {
        val submissions = mutableListOf<String>()
        val password = "  s3cret pass  "
        val editor = SourcePreferenceTextEditor("") {
            submissions += it
            ""
        }

        password.indices.forEach { editor.edit(password.take(it + 1)) }
        assertTrue(submissions.isEmpty())

        editor.submit()

        assertEquals(listOf(password), submissions)
        assertEquals("", editor.value)
    }

    @Test
    fun `all Android password variations select a password keyboard`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ).forEach { variation ->
            val inputType = InputType.TYPE_CLASS_TEXT or variation or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            assertEquals(KeyboardType.Password, sourcePreferenceKeyboardType(inputType))
        }
        assertEquals(
            KeyboardType.NumberPassword,
            sourcePreferenceKeyboardType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        )
    }

    @Test
    fun `email and OTP fields keep their keyboard types without being mistaken for passwords`() {
        assertEquals(
            KeyboardType.Email,
            sourcePreferenceKeyboardType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
        assertEquals(
            KeyboardType.Email,
            sourcePreferenceKeyboardType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
        )
        assertEquals(KeyboardType.Number, sourcePreferenceKeyboardType(InputType.TYPE_CLASS_NUMBER))
        assertEquals(
            KeyboardType.Decimal,
            sourcePreferenceKeyboardType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
        )
        assertEquals(KeyboardType.Phone, sourcePreferenceKeyboardType(InputType.TYPE_CLASS_PHONE))
        assertEquals(KeyboardType.Text, sourcePreferenceKeyboardType(InputType.TYPE_CLASS_TEXT))
    }

    @Test
    fun `typing all six digits does not submit a sign-in code`() {
        val submissions = mutableListOf<String>()
        val editor = SourcePreferenceTextEditor("") {
            submissions += it
            ""
        }

        (1..6).forEach { length -> editor.edit("123456".take(length)) }

        assertEquals("123456", editor.value)
        assertTrue(submissions.isEmpty())

        editor.submit()

        assertEquals(listOf("123456"), submissions)
        assertEquals("", editor.value, "A transient code must be cleared, not saved.")
    }

    @Test
    fun `editing and clearing an email only change the draft until submitted`() {
        var saved = "old@example.com"
        val submissions = mutableListOf<String>()
        val editor = SourcePreferenceTextEditor(saved) {
            submissions += it
            saved = it.trim()
            saved
        }

        editor.edit("")
        editor.edit("  new@example.com  ")
        assertEquals("old@example.com", saved)
        assertTrue(submissions.isEmpty())

        editor.submit()

        assertEquals(listOf("  new@example.com  "), submissions)
        assertEquals("new@example.com", saved)
        assertEquals(saved, editor.value)
    }

    @Test
    fun `a vetoed submission uses the extension's actual value`() {
        val editor = SourcePreferenceTextEditor("keep") { "keep" }
        editor.edit("rejected")
        editor.submit()
        assertEquals("keep", editor.value)
    }

    @Test
    fun `discarding an unsubmitted editor does not call the extension`() {
        var calls = 0
        SourcePreferenceTextEditor("") {
            calls++
            it
        }.edit("123")
        assertEquals(0, calls)
    }

    @Test
    fun `keyless account actions get distinct stable UI identities`() {
        val send = sourcePreferenceUiKey(null, 2)
        val status = sourcePreferenceUiKey(null, 4)
        val signOut = sourcePreferenceUiKey(null, 5)

        assertEquals(send, sourcePreferenceUiKey(null, 2))
        assertEquals(3, setOf(send, status, signOut).size)
        assertNotEquals("", send)
        assertEquals(sourcePreferenceUiKey(null, 2), sourcePreferenceUiKey("", 2))
        assertEquals("account_email", sourcePreferenceUiKey("account_email", 0))
    }
}
