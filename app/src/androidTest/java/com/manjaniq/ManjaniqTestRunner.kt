package com.manjaniq

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Supplies an offline policy through the same host interface as production. */
class ManjaniqTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestManjaniqApplication::class.java.name, context)
}
