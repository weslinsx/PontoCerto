package com.example.pontocerto.service.accessibility

import android.content.Context
import android.location.LocationManager
import android.view.accessibility.AccessibilityManager

/**
 * Fonte única de verdade sobre TalkBack e GPS. O app nunca fala nada quando os
 * dois já estão ligados (verificação silenciosa no onResume).
 */
class AccessibilityStatus(context: Context) {

    private val appContext = context.applicationContext
    private val accessibilityManager =
        appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    fun talkBackAtivo(): Boolean = accessibilityManager?.isTouchExplorationEnabled == true

    fun gpsLigado(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val rede = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return gps || rede
    }
}
