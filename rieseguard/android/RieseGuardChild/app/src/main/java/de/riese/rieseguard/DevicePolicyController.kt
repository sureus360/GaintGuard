package de.riese.rieseguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

class DevicePolicyController(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, RieseDeviceAdminReceiver::class.java)

    fun isDeviceAdmin(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun isDeviceOwner(): Boolean {
        val packageName = context.packageName
        return dpm.isDeviceOwnerApp(packageName)
    }

    fun lockDevice(): Boolean {
        return try {
            if (isDeviceOwner() || isDeviceAdmin()) {
                dpm.lockNow()
                true
            } else {
                Log.w("DevicePolicyController", "Cannot lock device: Not Device Owner or Admin Active")
                false
            }
        } catch (e: Exception) {
            Log.e("DevicePolicyController", "Error locking device", e)
            false
        }
    }

    fun activateDeviceAdmin() {
        val intent = android.content.Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Erlaubt das Sperren des Bildschirms durch Eltern.")
        }
        context.startActivity(intent)
    }

    fun setKioskModeEnabled(active: Boolean) {
        if (isDeviceOwner()) {
            val packageName = context.packageName
            try {
                if (active) {
                    dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                    
                    // Disable status bar pulldown (requires Android 6.0+)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        dpm.setStatusBarDisabled(adminComponent, true)
                    }
                    
                    // Hide settings app programmatically to block setting modifications
                    dpm.setApplicationHidden(adminComponent, "com.android.settings", true)
                    
                    // Add app controls restriction (disallows force-stopping or uninstalling)
                    dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_APPS_CONTROL)
                    Log.i("DevicePolicyController", "Kiosk mode restrictions activated")
                } else {
                    dpm.setLockTaskPackages(adminComponent, arrayOf())
                    
                    // Re-enable status bar pulldown
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        dpm.setStatusBarDisabled(adminComponent, false)
                    }
                    
                    // Unhide settings app
                    dpm.setApplicationHidden(adminComponent, "com.android.settings", false)
                    
                    // Remove app controls restriction
                    dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_APPS_CONTROL)
                    Log.i("DevicePolicyController", "Kiosk mode restrictions deactivated")
                }
            } catch (e: Exception) {
                Log.e("DevicePolicyController", "Error setting kiosk mode restrictions", e)
            }
        }
    }

    fun suspendPackages(packages: List<String>, suspend: Boolean): Boolean {
        return if (isDeviceOwner()) {
            try {
                if (packages.isEmpty()) return true
                val packageArray = packages.toTypedArray()
                val result = dpm.setPackagesSuspended(adminComponent, packageArray, suspend)
                result.isEmpty()
            } catch (e: Exception) {
                Log.e("DevicePolicyController", "Error suspending packages", e)
                false
            }
        } else {
            Log.w("DevicePolicyController", "Cannot suspend packages: Not Device Owner")
            false
        }
    }

    fun setApplicationHidden(packageName: String, hidden: Boolean): Boolean {
        return if (isDeviceOwner()) {
            try {
                if (dpm.isApplicationHidden(adminComponent, packageName) == hidden) {
                    return true
                }
                dpm.setApplicationHidden(adminComponent, packageName, hidden)
            } catch (e: Exception) {
                Log.e("DevicePolicyController", "Error setting app hidden: $packageName", e)
                false
            }
        } else {
            Log.w("DevicePolicyController", "Cannot hide package: Not Device Owner")
            false
        }
    }

    fun applyAntiTamperingRestrictions(active: Boolean) {
        if (isDeviceOwner()) {
            try {
                val restrictions = arrayOf(
                    android.os.UserManager.DISALLOW_SAFE_BOOT,
                    android.os.UserManager.DISALLOW_FACTORY_RESET,
                    android.os.UserManager.DISALLOW_ADD_USER
                )
                for (restriction in restrictions) {
                    if (active) {
                        dpm.addUserRestriction(adminComponent, restriction)
                    } else {
                        dpm.clearUserRestriction(adminComponent, restriction)
                    }
                }
                Log.i("DevicePolicyController", "Anti-tampering restrictions applied: $active")
            } catch (e: Exception) {
                Log.e("DevicePolicyController", "Error setting user restrictions", e)
            }
        }
    }

    fun setPrivateDns(enabled: Boolean): Boolean {
        if (!isDeviceOwner()) {
            Log.w("DevicePolicyController", "Cannot set Private DNS: Not Device Owner")
            return false
        }
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                if (enabled) {
                    dpm.setGlobalPrivateDnsModeSpecifiedHost(adminComponent, "family.cloudflare-dns.com")
                    Log.i("DevicePolicyController", "Enforced Cloudflare Family Private DNS")
                } else {
                    dpm.setGlobalPrivateDnsModeOpportunistic(adminComponent)
                    Log.i("DevicePolicyController", "Reset Private DNS to Opportunistic")
                }
                true
            } catch (e: Exception) {
                Log.e("DevicePolicyController", "Error setting Private DNS", e)
                false
            }
        } else {
            Log.w("DevicePolicyController", "Private DNS enforcement requires Android 10 (API 29)+")
            false
        }
    }

    fun setSchoolMode(active: Boolean) {
        if (isDeviceOwner()) {
            try {
                if (active) {
                    dpm.setMasterVolumeMuted(adminComponent, true)
                    dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_ADJUST_VOLUME)
                    Log.i("DevicePolicyController", "School mode activated: device muted, volume keys disabled")
                } else {
                    dpm.setMasterVolumeMuted(adminComponent, false)
                    dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_ADJUST_VOLUME)
                    Log.i("DevicePolicyController", "School mode deactivated: device unmuted, volume keys restored")
                }
            } catch (e: Exception) {
                Log.e("DevicePolicyController", "Error setting school mode", e)
            }
        }
    }
}

