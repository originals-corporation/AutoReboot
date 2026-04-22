# 🚀 Auto Reboot (Fabric)

**Auto Reboot** is a professional-grade server management utility for Minecraft (Fabric) designed to handle automated restarts with industrial precision. It transforms a simple server stop into a smart, data-protected process with flexible scheduling and a refined player notification system.

---

## ✨ Key Features (v1.0.1)

* **Three Operational Modes:** Full control through manual execution, time intervals, or precise 24-hour scheduling (default reboots every 6 hours: 00:00, 06:00, 12:00, 18:00).
* **Safety First (Auto-Save):** The mod forcefully executes a world save (`save-all`) the moment a reboot sequence is initiated to prevent data loss.
* **Customizable Warning Schedule:** Define your own alert intervals (e.g., 10, 5, 2, and 1 minutes) in the configuration file.
* **Visual Countdown:** Large titles and descriptive subtitles appear in the center of every player's screen during the final 5 seconds.
* **Emergency Stop:** Abort a pending reboot sequence instantly with the `/reboot stop` command.
* **JSON5 Configuration:** Modern format with English comments that can be reloaded on the fly without stopping the server.
* **True Reboot Automation:** Automatically triggers a launch script (`.bat` or `.sh`) upon shutdown for a seamless restart cycle.

---

## 🛠 Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/reboot` | **OP Level 4** | Initiates the reboot sequence manually. |
| `/reboot info` | **All Players** | Displays current mode, visual progress bar, and time until restart. |
| `/reboot stop` | **OP Level 4** | Aborts a pending reboot and resets the timers. |
| `/reboot reload` | **OP Level 4** | Instantly reloads all configs and translations from the `/config` folder. |

---

## ⚙️ Configuration

Upon first launch, the mod generates all necessary files in the `/config/autorebootmod/` directory.

### 1. Main Settings (`autorebootmod.json5`)
* **Manual Mode:** Restarts only occur when an administrator types `/reboot`.
* **Interval Mode:** Restarts automatically every X minutes based on server uptime.
* **Scheduled Mode:** Restarts at fixed 24-hour marks (e.g., `00:00`, `06:00`, `12:00`, `18:00`).

### 2. Localization (`en_us.json5` / `ru_ru.json5`)
The mod supports complete customization of system prefixes, colors (using the `§` symbol), and all notification messages.

---

## 📦 Installation
1.  Verify **Fabric Loader (0.18.4+)** and **Fabric API** are installed.
2.  Drop the mod `.jar` into your `mods` folder.
3.  Launch the server to generate default configurations.
4.  Targeted Minecraft Version: **1.20.1**.