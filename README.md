# T2 Remote — Android controller for WANBO T2 Ultra

Phone → projector wireless control: touchpad mouse, keyboard (English + Russian), remote buttons, and projector configuration.

| Tab | What it does | Channel |
|---|---|---|
| Connect | Pair / reconnect to the projector | Bluetooth |
| Mouse | Touchpad: 1 finger move, tap = click, 2 fingers scroll, 2-finger tap = right click, Drag lock | Bluetooth HID |
| Keyboard | Live typing from your phone keyboard (EN/RU), auto layout switching, special keys, Ctrl shortcuts | Bluetooth HID (or Wi-Fi ADB) |
| Remote | D-pad/OK, Back, Home, Menu, Search, Power, volume, media | Bluetooth HID |
| Config | Pointer/scroll speed, layout-switch shortcut, key delay; projector settings pages, sleep/wake, reboot, shell | Wi-Fi ADB (optional) |

The phone presents itself to the projector as a standard **Bluetooth keyboard + mouse**, so nothing is installed on the projector.

## Build the APK

**Option A — GitHub (no tools needed):** push this folder to a GitHub repo → Actions → "Build APK" runs automatically → download `T2Remote-apk` artifact → install on phone.

**Option B — Android Studio:** File → Open → this folder → Run (or Build → Build APK).

Requirements: phone with Android 9+ (Bluetooth HID device support — most Samsung/Pixel/Xiaomi phones work).

## First connection (2 min)

1. Open the app, allow Bluetooth permissions.
2. Connect tab → **Make phone visible**.
3. Projector: **Settings → Remotes & Accessories → Add accessory** → choose your phone → confirm the code.
4. Done. Next launches reconnect automatically.

## Russian typing — one-time projector setup

HID sends key positions, so the projector needs both layouts:

1. Projector: Settings → Device Preferences → Keyboard → **Physical keyboard** → your keyboard → add **English (US)** and **Russian**.
   If the page is hidden, use Config tab → Wi-Fi ADB → **Keyboard layouts** to open it.
2. In the app Config tab, pick the shortcut the projector uses to switch layouts (Android default: **Ctrl + Space**).
3. Type in Russian or English on your phone — the app switches the projector's layout as needed.
   If it ever types the wrong alphabet, tap **EN active / RU active** in the Keyboard tab to resync.

**Fallback** if the projector offers no Russian physical layout: install the open-source **ADBKeyBoard** IME on the projector
(`adb install ADBKeyboard.apk`), connect Wi-Fi ADB, tap **Enable ADBKeyBoard**, and switch Keyboard tab → Text route → **Wi-Fi ADB**.
Any Unicode text then goes through directly.

## Wi-Fi ADB (configuration channel)

Projector: Settings → Device Preferences → About → tap **Build** 7× → Developer options → enable **USB debugging** / **Network debugging**.
Enter the projector IP (Settings → Network) in the Config tab, port 5555, Connect, and accept the "Allow debugging" prompt on the projector.
Some Wanbo firmwares hide Developer options; Bluetooth control works regardless.

## Troubleshooting

- *Registration failed*: close other "Bluetooth keyboard" apps — Android allows one HID app at a time.
- *Keys/characters dropped*: raise **Delay between keys** in Config.
- *Projector doesn't see the phone*: keep the app open on the Connect tab while pairing; re-tap Make phone visible.
- *Mouse pointer doesn't appear*: move a finger on the touchpad — Android TV shows the pointer on first movement.

## Code map

```
hid/HidDescriptor.kt   HID report descriptor (keyboard + mouse + consumer keys)
hid/HidController.kt   BluetoothHidDevice registration, connect, report sending
hid/KeyMap.kt          char → HID code for EN (QWERTY) and RU (ЙЦУКЕН) layouts
adb/AdbController.kt   Wi-Fi ADB client (dadb) + ready-made configuration commands
ui/*                   Compose screens
```
