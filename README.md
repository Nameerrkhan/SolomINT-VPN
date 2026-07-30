# SolomINT VPN — Android WireGuard VPN Client (MVP)

A personal learning project: a working Android VPN client built from scratch, targeting an affordable, trustworthy VPN experience for South Asian users. Built with a near-zero budget on free-tier cloud infrastructure.

> ⚠️ **This is a personal/portfolio learning project, not a production security product.** It has not been security-audited and should not be used to protect sensitive traffic.

## What this is

A native Android app (Kotlin + Jetpack Compose) that establishes a real WireGuard VPN tunnel to a self-hosted server, using the open-source [`wireguard-android`](https://github.com/WireGuard/wireguard-android) tunnel library under the hood. The app tracks real system-level VPN state (not just its own in-memory assumptions) and runs a foreground service with a persistent notification, matching how production VPN apps stay alive in the background.

## Why I built this

Most consumer VPNs are either expensive subscriptions or free apps that quietly sell user data. I wanted to understand — end to end — what it actually takes to build a trustworthy VPN, from raw cloud infrastructure up to a working, reliable mobile client, before deciding whether to build this into a real product for the South Asia market.

## Architecture

```
Android App (Kotlin/Compose)
      │
      │  WireGuard tunnel (UDP 51820)
      ▼
Cloud VPS (Ubuntu, AWS EC2)
      │
      ▼
   Internet
```

- **Client**: Android app using `VpnService` + the WireGuard tunnel library, with a Compose UI (connect/disconnect toggle, live status synced from real system state) and a foreground service maintaining a persistent "Connected" notification.
- **Server**: A WireGuard server running on a cloud VPS, configured with NAT/IP forwarding to route client traffic to the internet.

## Tech stack

- **Mobile**: Kotlin, Jetpack Compose, `com.wireguard.android:tunnel`, Android `VpnService` + foreground `Service`
- **Server**: Ubuntu 24.04, WireGuard, iptables/NAT
- **Infra**: AWS EC2 (free-tier), previously prototyped on Oracle Cloud
- **Tooling**: Android Studio, Gradle, Git

## What I actually ran into (and solved)

This project involved a lot more real infrastructure and systems debugging than the UI code itself:

**Infrastructure**
- Diagnosed and fixed an Oracle Cloud instance with no public IP by tracing through VCN → subnet → internet gateway → route table configuration
- Recovered from repeated "out of capacity" errors on Oracle's free-tier Ampere shapes by understanding it was a regional capacity limit, not an account issue
- Debugged and resolved an out-of-memory kernel kill during package installation on a memory-constrained (1GB RAM) cloud instance by configuring swap space
- Migrated the whole server setup to AWS EC2 mid-project without losing progress
- Set up billing safety nets (AWS zero-spend budget alerts) to build confidently on free-tier infrastructure

**Android**
- Fixed a `NetworkOnMainThreadException` by moving tunnel operations onto a background coroutine dispatcher
- Diagnosed a subtle state-management bug where the UI showed the correct real-world VPN state, but the connect/disconnect button acted on stale in-memory object state — fixed by making the button trust the same verified state source as the UI (`statusState`) instead of a tunnel object recreated on every process restart
- Implemented a foreground service with a persistent notification so the VPN process survives being backgrounded, matching production VPN app behavior — including working through the Android 14+ `FOREGROUND_SERVICE_SPECIAL_USE` permission and `specialUse` service type requirements
- Diagnosed a crash loop (missing foreground service permission) that was silently killing and restarting the app process, which looked like "disconnect doesn't work" but was actually the WireGuard library auto-recovering the last tunnel after each crash-restart
- Set up real system-level VPN state checks (`ConnectivityManager` + `TRANSPORT_VPN`) instead of trusting the WireGuard library's own in-memory bookkeeping, which doesn't survive process death

## Setup (if you want to run this yourself)

1. Stand up your own WireGuard server (any cloud VPS running Ubuntu works) and note its public IP, port, and keys.
2. Clone this repo and open it in Android Studio.
3. Copy `LocalConfig.kt.example` to a new file `LocalConfig.kt` in the same package, and fill in your own values:
   - `CLIENT_PRIVATE_KEY`
   - `SERVER_PUBLIC_KEY`
   - `SERVER_ENDPOINT`

   (`LocalConfig.kt` is gitignored — your real keys never get committed.)
4. Register your client's public key as a peer on your server (`wg set wg0 peer <key> allowed-ips 10.8.0.2/32`).
5. Run on an emulator or device.

## Roadmap

- [x] Server + tunnel proof of concept
- [x] Custom Android client (connect/disconnect, live status)
- [x] Local testing hardening — real state tracking, foreground service, crash/lifecycle fixes
- [ ] Per-user backend (dynamic key/config issuance instead of one shared config)
- [ ] Kill switch + DNS leak protection
- [ ] Traffic obfuscation for restrictive network environments

## Disclaimer

Built for learning and portfolio purposes. Not independently audited. Do not use for sensitive or high-stakes traffic.
