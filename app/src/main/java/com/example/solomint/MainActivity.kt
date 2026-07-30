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

class MainActivity : ComponentActivity() {

    private lateinit var backend: GoBackend
    private lateinit var tunnel: MyTunnel
    private val vpnRequestCode = 100

    private var statusState = mutableStateOf("Disconnected")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
        if (tunnel.state == Tunnel.State.UP) disconnect() else requestPermissionAndConnect()
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
                statusState.value = "Disconnected"
            } catch (e: Exception) {
                statusState.value = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun buildConfig(): Config {
        val iface = Interface.Builder()
            .parsePrivateKey("YOUR_CLIENT_PRIVATE_KEY_HERE")
            .addAddress(InetNetwork.parse("10.8.0.2/32"))
            .addDnsServer(InetAddress.getByName("1.1.1.1"))
            .build()

        val peer = Peer.Builder()
            .parsePublicKey("YOUR_SERVER_PUBLIC_KEY_HERE")
            .setEndpoint(InetEndpoint.parse("YOUR_SERVER_IP:51820"))
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