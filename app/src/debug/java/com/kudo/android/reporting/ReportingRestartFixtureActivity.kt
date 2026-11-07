package com.kudo.android.reporting

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import java.io.File

/** Offline fixture: never reads or writes the installed user's reporting consent. */
class ReportingRestartFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("reporting_restart_fixture", MODE_PRIVATE)
        val first = intent.getBooleanExtra("first", false)
        val controller = ReportingController(PrivateReportingConsentStore(this, "reporting_restart_fixture")) {
            preferences.edit().putInt("initializations", preferences.getInt("initializations", 0) + 1).commit()
        }
        controller.start()
        if (first) {
            preferences.edit().putInt("before", Process.myPid()).putString("sentinel", "keep-local-data").commit()
            check(controller.saveForNextStart(true))
            check(AppRestart.request(this, ComponentName(this, ReportingRestartFixtureActivity::class.java)))
        } else {
            val report = "${preferences.getInt("before", -1)}\n${Process.myPid()}\n${controller.active}\n" +
                "${preferences.getInt("initializations", -1)}\n${preferences.getString("sentinel", "missing")}"
            File(cacheDir, "reporting-restart-result.txt").writeText(report, Charsets.UTF_8)
            finishAndRemoveTask()
            Process.killProcess(Process.myPid())
        }
    }
}
