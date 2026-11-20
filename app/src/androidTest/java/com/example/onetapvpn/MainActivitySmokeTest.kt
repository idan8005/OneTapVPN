package com.example.onetapvpn

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun mainScreenShowsConnectionAndProfileActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.connection_status)).check(matches(isDisplayed()))
            onView(withId(R.id.connect_button)).check(matches(isDisplayed()))
            onView(withId(R.id.import_profile_button)).check(matches(withText(R.string.import_file)))
            onView(withId(R.id.paste_profile_button)).check(matches(withText(R.string.paste_clipboard)))
            onView(withId(R.id.export_profile_button)).check(matches(withText(R.string.export_profile)))
        }
    }
}
