package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.model.AppCommand
import com.example.model.MyraPrefs
import com.example.service.AccessibilityHelperService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.URLEncoder

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"

        private val APP_PACKAGE_MAP: Map<String, String> = mapOf(
            "youtube" to "com.google.android.youtube",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "snapchat" to "com.snapchat.android",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "phone" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "play store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "zoom" to "us.zoom.videomeetings",
            "meet" to "com.google.android.apps.tachyon",
            "google meet" to "com.google.android.apps.tachyon",
            "teams" to "com.microsoft.teams",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "linkedin" to "com.linkedin.android"
        )
    }

    private val appContext: Context = application.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _commandResult = MutableLiveData<String?>()
    val commandResult: LiveData<String?> = _commandResult

    fun clearCommandResult() {
        _commandResult.value = null
    }

    fun executeCommand(command: AppCommand) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = when (command.type) {
                "OPEN_APP" -> openApp(command.params["app_name"].orEmpty())
                "CLOSE_APP" -> closeCurrentApp()
                "CALL" -> makeCallByNameOrNumber(command.params["name"].orEmpty())
                "SMS" -> sendSms(
                    command.params["name"].orEmpty(),
                    command.params["message"].orEmpty()
                )
                "WHATSAPP_MSG" -> openWhatsApp(
                    command.params["name"].orEmpty(),
                    command.params["message"].orEmpty()
                )
                "WHATSAPP_CALL" -> openWhatsApp(
                    command.params["name"].orEmpty(),
                    ""
                )
                "PRIME_CALL" -> callPrimeContact(command.params["index"]?.toIntOrNull() ?: 0)
                "PRIME_MSG" -> messagePrimeContact(
                    command.params["index"]?.toIntOrNull() ?: 0,
                    command.params["message"].orEmpty()
                )
                "VOLUME_UP" -> adjustVolume(AudioManager.ADJUST_RAISE)
                "VOLUME_DOWN" -> adjustVolume(AudioManager.ADJUST_LOWER)
                "FLASHLIGHT_ON" -> setFlashlight(true)
                "FLASHLIGHT_OFF" -> setFlashlight(false)
                "WIFI_ON" -> toggleWifi(true)
                "WIFI_OFF" -> toggleWifi(false)
                "BLUETOOTH_ON" -> toggleBluetooth(true)
                "BLUETOOTH_OFF" -> toggleBluetooth(false)
                else -> null
            }
            if (!result.isNullOrBlank()) {
                _commandResult.postValue(result)
            }
        }
    }

    private fun openApp(rawName: String): String {
        if (!AccessibilityHelperService.isEnabled(appContext)) {
            mainHandler.post { AccessibilityHelperService.promptEnable(appContext) }
            return "Accessibility service is disabled. Please enable MYRA Accessibility Service first."
        }

        val query = rawName.trim().lowercase()
        if (query.isEmpty()) return "Please specify which app to open."

        val pm = appContext.packageManager
        val knownPkg = APP_PACKAGE_MAP[query]
            ?: APP_PACKAGE_MAP.entries.firstOrNull { query.contains(it.key) }?.value

        if (knownPkg != null) {
            val launchIntent = pm.getLaunchIntentForPackage(knownPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    appContext.startActivity(launchIntent)
                    "Opened $rawName successfully."
                } catch (e: Exception) {
                    Log.e(TAG, "Failed launching known app $knownPkg", e)
                    "Could not open $rawName."
                }
            }
        }

        // Fallback: scan all launchable installed apps by label name
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)
        val matched = resolveList.firstOrNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            label == query || label.contains(query) || query.contains(label)
        }
        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    appContext.startActivity(launchIntent)
                    "Opened ${matched.loadLabel(pm)} successfully."
                } catch (e: Exception) {
                    Log.e(TAG, "Failed launching matched app", e)
                    "Could not open $rawName."
                }
            }
        }

        return "$rawName is not installed on this phone."
    }

    private fun closeCurrentApp(): String {
        if (!AccessibilityHelperService.isEnabled(appContext)) {
            mainHandler.post { AccessibilityHelperService.promptEnable(appContext) }
            return "Accessibility service is disabled. Please enable MYRA Accessibility Service first."
        }
        val closed = AccessibilityHelperService.instance?.closeCurrentApp() == true
        return if (closed) {
            "Closed the current app and returned to home screen."
        } else {
            "Accessibility service is not connected yet. Please re-enable it in settings."
        }
    }

    private fun callPrimeContact(index: Int): String {
        val primes = MyraPrefs.getPrimeContacts(appContext)
        if (primes.isEmpty()) {
            return "No prime contacts saved yet. Add your prime contacts in MYRA Settings."
        }
        val contact = primes.getOrNull(index) ?: primes.first()
        return placePhoneCall(contact.name, contact.number)
    }

    private fun messagePrimeContact(index: Int, message: String): String {
        val primes = MyraPrefs.getPrimeContacts(appContext)
        if (primes.isEmpty()) {
            return "No prime contacts saved yet. Add your prime contacts in MYRA Settings."
        }
        val contact = primes.getOrNull(index) ?: primes.first()
        return openSmsComposer(contact.name, contact.number, message)
    }

    private fun makeCallByNameOrNumber(nameOrNumber: String): String {
        val cleaned = nameOrNumber.trim()
        if (cleaned.isEmpty()) return "Who should I call?"

        val digitsOnly = cleaned.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 7 && digitsOnly.count { it.isDigit() } >= 7) {
            return placePhoneCall(cleaned, digitsOnly)
        }

        val resolved = lookupContactNumber(cleaned)
            ?: return "Could not find $cleaned in your contacts."
        return placePhoneCall(resolved.first, resolved.second)
    }

    private fun placePhoneCall(displayName: String, number: String): String {
        val hasCallPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            if (hasCallPerm) "Calling $displayName now." else "Opened dialer for $displayName."
        } catch (e: Exception) {
            Log.e(TAG, "Failed placing call", e)
            "Unable to start phone call to $displayName."
        }
    }

    private fun sendSms(name: String, message: String): String {
        val resolved = lookupContactNumber(name)
            ?: return "Could not find $name in your contacts."
        return openSmsComposer(resolved.first, resolved.second, message)
    }

    private fun openSmsComposer(displayName: String, number: String, message: String): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Opened SMS to $displayName."
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening SMS", e)
            "Could not open SMS app for $displayName."
        }
    }

    private fun openWhatsApp(name: String, message: String): String {
        val resolved = lookupContactNumber(name)
            ?: return "Could not find $name in your contacts for WhatsApp."
        val cleanNum = resolved.second.filter { it.isDigit() }
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val uri = if (message.isNotEmpty()) {
            Uri.parse("https://wa.me/$cleanNum?text=$encodedMsg")
        } else {
            Uri.parse("https://wa.me/$cleanNum")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            "Opened WhatsApp chat with ${resolved.first}."
        } catch (e: Exception) {
            Log.e(TAG, "Failed opening WhatsApp", e)
            "Could not open WhatsApp for ${resolved.first}."
        }
    }

    fun lookupContactNumber(queryName: String): Pair<String, String>? {
        val trimmed = queryName.trim().lowercase()
        if (trimmed.isEmpty()) return null

        // Check Prime Contacts first
        val primeMatch = MyraPrefs.getPrimeContacts(appContext).firstOrNull {
            it.name.lowercase() == trimmed || it.name.lowercase().contains(trimmed)
        }
        if (primeMatch != null) {
            return primeMatch.name to primeMatch.number
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$trimmed%"),
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (cursor.moveToFirst()) {
                    val foundName = cursor.getString(nameCol) ?: queryName
                    val foundNum = cursor.getString(numCol) ?: return null
                    foundName to foundNum
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
            null
        }
    }

    private fun adjustVolume(direction: Int): String {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Audio service unavailable."
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
        return if (direction == AudioManager.ADJUST_RAISE) "Volume increased." else "Volume decreased."
    }

    private fun setFlashlight(enable: Boolean): String {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "Camera service unavailable."
        return try {
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight found on this device."

            cm.setTorchMode(cameraId, enable)
            if (enable) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight error", e)
            "Unable to change flashlight state."
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(enable: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(panelIntent)
                if (enable) "Opened WiFi panel to turn WiFi on." else "Opened WiFi panel to turn WiFi off."
            } else {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.isWifiEnabled = enable
                if (enable) "WiFi turned on." else "WiFi turned off."
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi toggle error", e)
            "Unable to open WiFi settings."
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth(enable: Boolean): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                "Opened Bluetooth settings."
            } else {
                val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter ?: return "Bluetooth is not supported."
                val canControl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                if (canControl) {
                    if (enable) adapter.enable() else adapter.disable()
                    if (enable) "Bluetooth turned on." else "Bluetooth turned off."
                } else {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    "Opened Bluetooth settings."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth toggle error", e)
            "Unable to change Bluetooth settings."
        }
    }

    fun acceptCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val tm = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            tm?.acceptRingingCall()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting ringing call", e)
            false
        }
    }

    fun rejectCall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            val tm = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            tm?.endCall() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting call", e)
            false
        }
    }
}
