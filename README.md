# SolomINT VPN — Android WireGuard VPN Client (MVP)

A personal learning project: a working Android VPN client built from scratch on a near-zero budget, using free-tier cloud infrastructure end to end.

> ⚠️ **This is a personal/portfolio learning project, not a production security product.** It has not been security-audited and should not be used to protect sensitive traffic.

## What this is

A native Android app (Kotlin + Jetpack Compose) that dynamically registers each device with a self-hosted backend, receives a unique WireGuard identity, and establishes a real VPN tunnel — using the open-source [`wireguard-android`](https://github.com/WireGuard/wireguard-android) tunnel library under the hood. The app tracks real system-level VPN state (not just its own in-memory assumptions), runs a foreground service with a persistent notification, and includes IPv6 leak protection, kill switch guidance, a connection-loss monitor, and per-app split tunneling.

## Why I built this

I wanted to understand — end to end — what it actually takes to build a trustworthy VPN, from raw cloud infrastructure up to a working, reliable mobile client with the same core features found in established VPN apps.

## Architecture

```
Android App (Kotlin/Compose)
      │
      │  1. POST /register-device  (first launch only)
      ▼
Flask API (same VPS)
      │  generates keypair, assigns IP, registers as WireGuard peer, saves to SQLite
      ▼
      │  2. WireGuard tunnel (UDP 51820), using the issued identity
      ▼
Cloud VPS (Ubuntu, AWS EC2) — WireGuard server
      │
      ▼
   Internet
```

- **Client**: Android app using `VpnService` + the WireGuard tunnel library. On first launch, it calls the backend to get a unique identity, stores it in `EncryptedSharedPreferences`, and reuses it on every subsequent launch. Compose UI shows live status synced from real system state; a foreground service keeps a persistent "Connected" notification.
- **Backend**: A minimal Flask API on the same VPS. `/register-device` generates a fresh WireGuard key pair, assigns the next free internal IP (tracked in SQLite to avoid collisions), and registers the new peer directly on the live `wg0` interface via `wg set`.
- **Server**: WireGuard running on a cloud VPS, with NAT/IP forwarding routing client traffic to the internet.

## Features

- Real system-level VPN state tracking (not just in-app assumptions)
- Foreground service with persistent notification, so the tunnel survives being backgrounded
- Per-device dynamic key/IP issuance via a custom backend (no shared hardcoded credentials)
- IPv6 leak protection (routes IPv6 into the tunnel instead of leaking it to the raw network)
- Kill switch guidance (deep-links to Android's system-level "block connections without VPN" setting, plus an in-app connection-loss monitor as a secondary safety net)
- Split tunneling — per-app exclusion list, letting specific apps bypass the VPN

## Tech stack

- **Mobile**: Kotlin, Jetpack Compose, `com.wireguard.android:tunnel`, Android `VpnService` + foreground `Service`, `androidx.security.crypto` (EncryptedSharedPreferences)
- **Backend**: Python, Flask, SQLite
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
- Diagnosed a silent WireGuard peer IP collision, where a database-unaware manual peer and a newly backend-registered peer both claimed the same internal IP — root-caused it to WireGuard's `allowed-ips` acting as an exclusive routing claim, not an additive one

**Android**
- Fixed a `NetworkOnMainThreadException` by moving tunnel operations onto a background coroutine dispatcher
- Diagnosed a subtle state-management bug where the UI showed the correct real-world VPN state, but the connect/disconnect button acted on stale in-memory object state — fixed by making the button trust the same verified state source as the UI instead of a tunnel object recreated on every process restart
- Implemented a foreground service with a persistent notification so the VPN process survives being backgrounded, matching production VPN app behavior — including working through the Android 14+ `FOREGROUND_SERVICE_SPECIAL_USE` permission and `specialUse` service type requirements
- Diagnosed a crash loop (missing foreground service permission) that was silently killing and restarting the app process, which looked like "disconnect doesn't work" but was actually the WireGuard library auto-recovering the last tunnel after each crash-restart
- Fixed a `CLEARTEXT communication not permitted` error by scoping a network security config exception to the backend's specific IP, rather than disabling Android's HTTPS-by-default policy globally
- Fixed a Kotlin "Redeclaration" build error caused by an example/template file accidentally being treated as real source code because it still had a `.kt` extension
- Diagnosed and fixed an ANR (App Not Responding) caused by loading the full installed-apps list synchronously on the UI thread for the split tunneling screen — moved the `PackageManager` lookup to a background coroutine with a loading state

**Backend**
- Built a minimal device-registration API that generates real WireGuard key pairs via `wg genkey`/`wg pubkey` subprocess calls, tracks IP allocation in SQLite to prevent collisions, and live-registers new peers on a running WireGuard interface without restarting it
- Configured passwordless `sudo` access scoped to exactly one binary (`/usr/bin/wg`) so the Flask process can manage WireGuard peers without broader root access
- Diagnosed a "connection refused" issue down to a missing AWS Security Group rule, distinct from OS-level firewall or application-level problems

## Setup (if you want to run this yourself)

1. Stand up your own WireGuard server (any cloud VPS running Ubuntu works) and note its public IP and server keys.
2. Set up the backend: copy the `backend/` folder to your server, create a Python virtual environment, `pip install flask`, and run `app.py`. Update `SERVER_PUBLIC_KEY` and `SERVER_ENDPOINT` in `app.py` to match your server.
3. Allow the Flask user to manage WireGuard peers without a password prompt (`visudo` → `<user> ALL=(ALL) NOPASSWD: /usr/bin/wg`).
4. Open the relevant ports in your cloud provider's firewall/security group: UDP 51820 (WireGuard) and your backend's port.
5. Clone this repo and open it in Android Studio.
6. In `DeviceConfigManager.kt`, update `REGISTER_URL` to point at your own backend.
7. If testing over plain HTTP, update `network_security_config.xml` with your server's IP.
8. Run on an emulator or device — the app will automatically register itself and fetch its own unique WireGuard identity on first launch.

## Known limitations (by design, for now)

- The `/register-device` endpoint has no authentication — anyone with the URL can register a new peer. Fine for solo MVP testing; will need rate-limiting/auth before any public exposure.
- Backend runs on Flask's development server, not a production WSGI server — fine for testing, not for real traffic.
- Backend traffic is currently unencrypted HTTP; HTTPS via Let's Encrypt requires a domain name and is planned before any public deployment.
- Single server location; no multi-region support yet.

## Roadmap

- [x] Server + tunnel proof of concept
- [x] Custom Android client (connect/disconnect, live status)
- [x] Local testing hardening — real state tracking, foreground service, crash/lifecycle fixes
- [x] Per-device backend — dynamic key/IP issuance, no more hardcoded shared config
- [x] IPv6 leak protection, kill switch guidance, split tunneling
- [ ] HTTPS for the backend API
- [ ] Backend auth/rate-limiting
- [ ] Multi-server/multi-region support

## Disclaimer

Built for learning and portfolio purposes. Not independently audited. Do not use for sensitive or high-stakes traffic.
