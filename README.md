<div align="center">

<img src="https://raw.githubusercontent.com/ehsanbagherigit/Manjaniq/master/assets/logos/manjaniq_logo.png" alt="Manjaniq Logo" width="120" height="120">

# Manjaniq

**An Advanced Android SOCKS5 & Loopback Proxy Client powered by Aether & RSTA Cores.**

[![CI/CD Status](https://github.com/ehsanbagherigit/manjaniq/actions/workflows/build-debug.yml/badge.svg)](https://github.com/ehsanbagherigit/manjaniq/actions)
[![Android Min SDK](https://img.shields.io/badge/Min%20SDK-29%28Android%2010%29-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## 📖 About the Project

Manjaniq is a modular Android networking client that integrates third-party cores to support internet access in restrictive network environments. It includes techniques intended to help circumvent censorship and DPI-based filtering.

Engineered for local SOCKS5 and loopback traffic routing, Manjaniq relies on a modern **Jetpack Compose** interface to deliver a stable, secure, and lightweight proxy management experience. Manual engine startup remains SOCKS5-only; the optional **Quick Connect** button adds a one-tap TUN→SOCKS5 VPN tunnel that routes device traffic through the running core on demand.

---

## ⚙️ Core Architectures

* **Aether Core:** Powers high-performance proxy routing, identity management (`AetherIdentityManager`), key parsing, and command rendering with native JNI integration (`libaether.so`).
* **RSTA Core:** Integrates robust proxy and tunnel support, enhancing the app's capability to handle specialized networking protocols and bypass complex network restrictions.

---

## ✨ Key Features

* **Pure SOCKS5 / Loopback Architecture:** Binds directly to a local loopback port (`127.0.0.1:1819`); manual engine startup never touches system routing.
* **Quick Connect VPN:** One tap on the Home button starts the selected core, waits for its local SOCKS5 listener (e.g. `127.0.0.1:1819`) and brings up an Android `VpnService` tunnel that routes device TCP and DNS traffic through it. Tap again to tear down. Note: RSTA is a single-destination SNI-spoofing relay, not a general-purpose SOCKS5 proxy, so the full-device tunnel is only usable with Aether.
* **Censorship & DPI Evasion:** Built-in techniques and modular core support designed to bypass deep packet inspection and restrictive filtering.
* **Psiphon Support with Conditional UI:** Integrated Psiphon tunneling capabilities featuring dynamically conditioned **Exit Country Codes** (visible and active exclusively when Psiphon is toggled ON).
* **Advanced Proxy Settings:** Comprehensive scan modes, IP version selector, and UDP obfuscation controls.
* **Clean UI & Modern Design:** Built with Jetpack Compose and Material Design 3, completely stripped of deprecated fields (such as remote "remark" inputs) for a streamlined user experience.

---

## 🛠️ Tech Stack

* **Language:** Kotlin (Android UI & Application Logic) / Rust (Native Core Integrations).
* **UI Framework:** Jetpack Compose & Material 3.
* **Interfacing:** JNI (Java Native Interface) bridge for high-speed native execution.
* **CI/CD & Automation:** GitHub Actions (automated compilation, artifact packaging, and emulator smoke tests).

---

🙏 Acknowledgments & Special Thanks
We would like to express our sincere gratitude to the following developers and organizations whose open-source projects, architectures, and inspirations made Manjaniq possible:
rstagit - For core contributions, RSTA integration, and networking utilities.
CluvexStudio - For design frameworks and architectural inspiration.
patterniha - For reference patterns, PattNG implementations, and core components.

----


🤝 Contributing
Contributions, feature requests, and bug reports are welcome! Feel free to open an issue or submit a pull request.

----


📄 License
This project is licensed under the MIT License. See the LICENSE file for details.


---


## 🏗️ Project Structure

```text
com.manjaniq/
├── core/           # Core management, identity handlers, and argument renderers
├── ui/             # Jetpack Compose screens, view models, and UI components
├── native/         # JNI bridge implementations and native library bindings
└── data/           # Storage, preferences, and configuration handlers
