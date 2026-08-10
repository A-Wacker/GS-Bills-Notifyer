package com.awacker.billsnotifier

import android.app.Application

/**
 * Stand-in Application for Robolectric tests.
 *
 * [BillsApp.onCreate] builds the whole object graph and arms WorkManager, none of which
 * these tests are about — using it would make every test depend on app startup succeeding.
 */
class TestApp : Application()
