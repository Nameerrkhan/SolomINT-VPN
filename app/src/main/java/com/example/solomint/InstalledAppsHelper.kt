package com.example.solomint

import android.content.Context
import android.content.pm.PackageManager

data class InstalledApp(val packageName: String, val appName: String)

object InstalledAppsHelper {
    fun getInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != context.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.appName.lowercase() }
    }
}