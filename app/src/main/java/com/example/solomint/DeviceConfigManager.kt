package com.example.solomint

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DeviceConfig(
    val clientPrivateKey: String,
    val clientAddress: String,
    val serverPublicKey: String,
    val serverEndpoint: String,
    val dns: String
)

object DeviceConfigManager {

    private const val PREFS_NAME = "vpn_device_config"
    private const val REGISTER_URL = "http://13.48.204.27:5000/register-device"

    private fun getPrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    fun getSavedConfig(context: Context): DeviceConfig? {
        val prefs = getPrefs(context)
        val privateKey = prefs.getString("client_private_key", null) ?: return null
        val address = prefs.getString("client_address", null) ?: return null
        val serverKey = prefs.getString("server_public_key", null) ?: return null
        val endpoint = prefs.getString("server_endpoint", null) ?: return null
        val dns = prefs.getString("dns", null) ?: return null
        return DeviceConfig(privateKey, address, serverKey, endpoint, dns)
    }

    fun fetchAndSaveNewConfig(context: Context): DeviceConfig {
        val url = URL(REGISTER_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            throw Exception("Server returned $responseCode")
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)

        val config = DeviceConfig(
            clientPrivateKey = json.getString("client_private_key"),
            clientAddress = json.getString("client_address"),
            serverPublicKey = json.getString("server_public_key"),
            serverEndpoint = json.getString("server_endpoint"),
            dns = json.getString("dns")
        )

        val prefs = getPrefs(context)
        prefs.edit()
            .putString("client_private_key", config.clientPrivateKey)
            .putString("client_address", config.clientAddress)
            .putString("server_public_key", config.serverPublicKey)
            .putString("server_endpoint", config.serverEndpoint)
            .putString("dns", config.dns)
            .apply()

        return config
    }
}