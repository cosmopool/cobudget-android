package com.kaiodelphino.margem

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowInputMethodManager

/**
 * Robolectric's keyboard is never active, so the field never sends it the cursor's position on screen;
 * a real keyboard is active. Tests that type with [typeComposing] use it: `@Config(shadows = [ActiveKeyboard::class])`.
 */
@Implements(InputMethodManager::class)
class ActiveKeyboard : ShadowInputMethodManager() {
    @Implementation
    override fun isActive(view: View?) = true

    @Implementation
    override fun isActive() = true
}

/**
 * Types [keys] into the field [tag] like FlorisBoard: it follows the cursor's position on screen, marks
 * the number before the cursor as its composing word and, on each key, replaces that word with
 * word + key; the screen recomposes between keys. A field that drops the composition gets the word
 * inserted again instead of replaced.
 */
fun ComposeContentTestRule.typeComposing(tag: String, keys: String) {
    onNodeWithTag(tag).performClick()
    waitForIdle()
    val view = (onNodeWithTag(tag).fetchSemanticsNode().root as ViewRootForTest).view
    val ime = runOnIdle { view.onCreateInputConnection(EditorInfo()) }
    runOnIdle { ime.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR) }
    var word = runOnIdle { ime.getTextBeforeCursor(64, 0).toString() }
    runOnIdle { ime.setComposingRegion(0, word.length) }
    waitForIdle()
    for (k in keys) {
        word += k
        runOnIdle { ime.setComposingText(word, 1) }
        waitForIdle()
    }
}
