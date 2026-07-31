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
import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var backend: GoBackend
    private lateinit var tunnel: MyTunnel
    private val vpnRequestCode = 100
    private var handshakeCheckJob: kotlinx.coroutines.Job? = null
    private var showAppSelector = mutableStateOf(false)
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
                    if (showAppSelector.value) {
                        AppSelectionScreen(
                            context = applicationContext,
                            modifier = Modifier.padding(innerPadding),
                            onBack = { showAppSelector.value = false }
                        )
                    } else {
                        VpnScreen(
                            status = statusState.value,
                            modifier = Modifier.padding(innerPadding),
                            onButtonClick = { toggleConnection() },
                            onKillSwitchClick = { openVpnSettingsForKillSwitch() },
                            onSplitTunnelClick = { showAppSelector.value = true }
                        )
                    }
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

    private fun startHandshakeMonitor() {
        handshakeCheckJob?.cancel()
        handshakeCheckJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(30000)
                if (statusState.value == "Connected" && !isSystemVpnActive()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        statusState.value = "Connection lost — traffic may not be protected"
                    }
                }
            }
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
                startHandshakeMonitor()
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
                handshakeCheckJob?.cancel()
            } catch (e: Exception) {
                statusState.value = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun openVpnSettingsForKillSwitch() {
        val intent = Intent("android.net.vpn.SETTINGS")
        startActivity(intent)
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

        val ifaceBuilder = Interface.Builder()
            .parsePrivateKey(deviceConfig.clientPrivateKey)
            .addAddress(InetNetwork.parse(deviceConfig.clientAddress))
            .addDnsServer(InetAddress.getByName(deviceConfig.dns))

        val excludedApps = DeviceConfigManager.getExcludedApps(applicationContext)
        excludedApps.forEach { pkg -> ifaceBuilder.excludeApplication(pkg) }

        val iface = ifaceBuilder.build()

        val peer = Peer.Builder()
            .parsePublicKey(deviceConfig.serverPublicKey)
            .setEndpoint(InetEndpoint.parse(deviceConfig.serverEndpoint))
            .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
            .addAllowedIp(InetNetwork.parse("::/0"))
            .build()

        return Config.Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()
    }

    @Composable
    fun VpnScreen(
        status: String,
        modifier: Modifier = Modifier,
        onButtonClick: () -> Unit,
        onKillSwitchClick: () -> Unit,
        onSplitTunnelClick: () -> Unit
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = status, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onButtonClick,
                enabled = status != "Connecting..."
            ) {
                Text(
                    when (status) {
                        "Connected" -> "Disconnect"
                        "Connecting..." -> "Connecting..."
                        else -> "Connect"
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onSplitTunnelClick) {
                Text("Split Tunneling", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onKillSwitchClick) {
                Text(
                    "Enable Kill Switch (System Settings)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    fun AppSelectionScreen(
        context: Context,
        modifier: Modifier = Modifier,
        onBack: () -> Unit
    ) {
        var installedApps by remember { mutableStateOf<List<InstalledApp>?>(null) }
        var excludedApps by remember {
            mutableStateOf(DeviceConfigManager.getExcludedApps(context))
        }

        LaunchedEffect(Unit) {
            installedApps = withContext(kotlinx.coroutines.Dispatchers.IO) {
                InstalledAppsHelper.getInstalledApps(context)
            }
        }

        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Split Tunneling", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) {
                    Text("Done")
                }
            }
            Text(
                "Checked apps will bypass the VPN and use your regular connection.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (installedApps == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(installedApps!!) { app ->
                        val isExcluded = excludedApps.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isExcluded,
                                onCheckedChange = { checked ->
                                    val updated = excludedApps.toMutableSet()
                                    if (checked) updated.add(app.packageName) else updated.remove(app.packageName)
                                    excludedApps = updated
                                    DeviceConfigManager.setExcludedApps(context, updated)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(app.appName)
                        }
                    }
                }
            }
        }
    }
}
