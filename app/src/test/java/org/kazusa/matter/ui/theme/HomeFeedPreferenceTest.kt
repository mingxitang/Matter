package org.kazusa.matter.ui.theme

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HomeFeedPreferenceTest {
    @Test
    fun newPreferenceInstanceReadsTheLastSelectedFeed() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val preferences = ThemePreferences(context)

        assertFalse(preferences.homeShowsHotFeed.first())
        preferences.toggleHomeFeed()
        assertTrue(ThemePreferences(context).homeShowsHotFeed.first())

        preferences.toggleHomeFeed()
        assertFalse(ThemePreferences(context).homeShowsHotFeed.first())
    }
}
