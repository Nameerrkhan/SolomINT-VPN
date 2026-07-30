package com.example.solomint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.solomint.ui.theme.SolomINTTheme
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import kotlinx.coroutines.launch
import java.net.InetAddress
import androidx.compose.ui.Alignment
import android.os.Build

class MainActivity : ComponentActivity() {

    private lateinit var backend: GoBackend
    private lateinit var tunnel: MyTunnel
    private val vpnRequestCode = 100

    private var statusState = mutableStateOf("Disconnected")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
            }
        }
        backend = GoBackend(applicationContext)
        tunnel = MyTunnel("MyVPNTunnel")

        setContent {
            SolomINTTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VpnScreen(
                        status = statusState.value,
                        modifier = Modifier.padding(innerPadding),
                        onButtonClick = { toggleConnection() }
                    )
                }
            }
        }
    }

    private fun toggleConnection() {
        if (statusState.value == "Connected") {
            disconnect()
        } else {
            requestPermissionAndConnect()
        }
    }

    private fun requestPermissionAndConnect() {
        val intent = GoBackend.VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            connect()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) connect()
    }

    private fun connect() {
        statusState.value = "Connecting..."
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                backend.setState(tunnel, Tunnel.State.UP, buildConfig())
                startService(Intent(this@MainActivity, VpnStatusService::class.java))
                statusState.value = "Connected"
            } catch (e: Exception) {
                statusState.value = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
    private fun disconnect() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                stopService(Intent(this@MainActivity, VpnStatusService::class.java))
                statusState.value = "Disconnected"
            } catch (e: Exception) {
                statusState.value = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
    private fun isSystemVpnActive(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    override fun onResume() {
        super.onResume()
        val actuallyConnected = isSystemVpnActive()
        statusState.value = if (actuallyConnected) "Connected" else "Disconnected"
    }

    private fun buildConfig(): Config {
        var deviceConfig = DeviceConfigManager.getSavedConfig(applicationContext)
        if (deviceConfig == null) {
            deviceConfig = DeviceConfigManager.fetchAndSaveNewConfig(applicationContext)
        }

        val iface = Interface.Builder()
            .parsePrivateKey(deviceConfig.clientPrivateKey)
            .addAddress(InetNetwork.parse(deviceConfig.clientAddress))
            .addDnsServer(InetAddress.getByName(deviceConfig.dns))
            .build()

        val peer = Peer.Builder()
            .parsePublicKey(deviceConfig.serverPublicKey)
            .setEndpoint(InetEndpoint.parse(deviceConfig.serverEndpoint))
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            .build()

        return Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()
    }
}

@Composable
fun VpnScreen(status: String, modifier: Modifier = Modifier, onButtonClick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = status, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onButtonClick) {
            Text(if (status == "Connected") "Disconnect" else "Connect")
        }
    }
}