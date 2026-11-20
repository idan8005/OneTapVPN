package com.example.onetapvpn

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun homeScreenShowsVpnGateAndRoutingOptions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.brand_title)).check(matches(withText(R.string.app_name)))
            onView(withId(R.id.connection_status)).check(matches(isDisplayed()))
            onView(withId(R.id.connect_button)).check(matches(isDisplayed()))
            onView(withId(R.id.refresh_servers_button)).check(matches(isDisplayed()))
            onView(withId(R.id.full_tunnel_button)).check(matches(isDisplayed()))
            onView(withId(R.id.selected_apps_mode_button)).check(matches(isDisplayed()))
            onView(withId(R.id.check_updates_button)).check(matches(isDisplayed()))
            onView(withId(R.id.choose_apps_button))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))

            onView(withId(R.id.selected_apps_mode_button)).perform(click())
            onView(withId(R.id.choose_apps_button)).check(matches(isDisplayed()))
        }
    }
}
