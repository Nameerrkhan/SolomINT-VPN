package com.example.solomint

import com.wireguard.android.backend.Tunnel

class MyTunnel(private val tunnelName: String) : Tunnel {
    var state: Tunnel.State = Tunnel.State.DOWN
        private set

    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        state = newState
    }
}