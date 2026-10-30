# android mcp server app

android mcp server app adalah aplikasi android native yang mengekspos fungsi perangkat ke model ai via protokol mcp (model context protocol). project ini adalah server kontrol perangkat: ia membuka endpoint mcp lokal di smartphone dan memungkinkan client ai mengakses fitur seperti kontak, sms, log panggilan, file, notifikasi, lokasi, kamera, media, aksesibilitas, dan pengaturan sistem dengan skema tool yang bisa diaktifkan/ditutup.

## ringkasan

repo ini berisi satu modul aplikasi android yang berfungsi sebagai server local di perangkat. ia menyiapkan:

- service background `PhoneMcpService`
- server MCP HTTP di `http://127.0.0.1:9009/mcp`
- tool registry yang besar dan modular
- panel pengaturan untuk mengaktifkan atau menonaktifkan tool per kategori
- autentikasi bearer token opsional
- tunnel publik via cloudflared untuk akses luar perangkat
- dukungan akses perangkat seperti notifikasi, aksesibilitas, overlay, package usage, storage, dan screen capture

secara garis besar, project ini mengubah handphone jadi endpoint system control untuk agent ai.

## tujuan proyek

project ini dibuat untuk menyalurkan kemampuan perangkat android ke model ai. beberapa contoh penggunaan:

- membaca/sunting file di storage
- mengecek kontak dan log panggilan
- membaca atau mengirim sms
- menangani notifikasi
- mengakses lokasi dan jaringan
- mengambil screenshot atau menangani capture layar
- menjalankan perintah terminal jika termux terpasang
- mengelola aplikasi dan pengaturan sistem tertentu

intinya: ai bisa "mencapai" device yang menjalankan app ini seperti tool-tool yang terdaftar di MCP.

## arsitektur aplikasi

struktur utama project ini:

```text
android-mcp-server-app/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── assets/
│           │   └── fontawesome/
│           │       ├── fa-brands-400.ttf
│           │       ├── fa-regular-400.ttf
│           │       └── fa-solid-900.ttf
│           ├── java/mcp/android/phone/
│           │   ├── BootReceiver.java
│           │   ├── CrashDiagnostics.java
│           │   ├── FileOps.java
│           │   ├── JsonToolsManager.java
│           │   ├── MainActivity.java
│           │   ├── McpServer.java
│           │   ├── PermissionUtil.java
│           │   ├── PhoneAccessibilityService.java
│           │   ├── PhoneFileProvider.java
│           │   ├── PhoneMcpService.java
│           │   ├── PhoneNotificationListener.java
│           │   ├── ScreenCaptureActivity.java
│           │   ├── ScreenCaptureManager.java
│           │   ├── ServiceRecoveryReceiver.java
│           │   ├── TerminalExecManager.java
│           │   ├── TerminalLogActivity.java
│           │   ├── TerminalLogDb.java
│           │   ├── TerminalResultService.java
│           │   ├── ToolDef.java
│           │   ├── ToolRegistry.java
│           │   └── ToolSettings.java
│           ├── jniLibs/arm64-v8a/libcloudflared.so
│           └── res/
│               ├── drawable/ic_launcer.png
│               ├── layout/main.xml
│               ├── values/
│               │   ├── strings.xml
│               │   └── styles.xml
│               └── xml/accessibility_service_config.xml
├── build.gradle
├── settings.gradle
├── .gitignore
├── README.md
└── LICENSE
```

## pembaruan terbaru

- **fitur browser dipindah.** `BrowserManager` dan tool `browser.*` sudah dihapus dari aplikasi ini dan dipisah ke aplikasi browser sendiri di repository [browser-for-models](https://github.com/reyhanaarch64/browser-for-models). alasannya, ekosistem browser untuk model AI dan tool umum perangkat sebaiknya dipisah supaya masing-masing lebih sederhana. versi lama yang masih berisi fitur browser ada di branch `legacy-browser`, dan proyek ini sudah tidak dilanjutkan di branch tersebut.
- **tampilan baru.** ui dibuat ulang dengan sistem tab dan layout yang lebih rapi. icon memakai [font awesome](https://fontawesome.com) (`solid`, `regular`, `brands`) yang disimpan di `app/src/main/assets/fontawesome/`.
- **tool `json.batch`.** editor json terstruktur untuk beberapa file sekaligus. tiap file diproses dan di-commit sendiri-sendiri, dan kegagalan satu aksi tidak menulis dokumen yang setengah diedit. backup dibuat sebelum file ditimpa (`JsonToolsManager.java`).

## komponen utama

### 1. mainactivity
`MainActivity.java` adalah panel kontrol utama. ui ini menampilkan:

- status server
- url lokal dan url publik
- token mcp
- toggle autentikasi
- daftar kategori tool yang bisa diaktifkan/dinonaktifkan
- tombol akses permission penting
- tombol untuk mengelola screen capture, terminal, battery, storage, overlay, dll

ini membuat app terasa seperti dashboard kontrol device untuk ai agent.

### 2. phonemcpservice
`PhoneMcpService.java` adalah komponen paling penting setelah server. service ini berjalan di background dan memegang sistem tool. di dalamnya terdapat banyak method untuk:

- membaca dan menulis file
- akses media dan storage
- akses telepon, kontak, sms, panggilan, kalender
- akses lokasi, bluetooth, wi-fi, jaringan
- pemeriksaan app dan permission
- screen capture
- notifications dan accessibility
- terminal / termux integration
- pemutaran audio, ringtone, dan text-to-speech
- operasi hardware, display, ui, dan settings sistem

file ini sangat besar dan berisi banyak fitur runtime device-level. ini menjadikan repo ini sebagai "server system operasi mobile" untuk agent AI.

### 3. mcpserver
`McpServer.java` adalah socket server HTTP yang menerima request MCP. server ini mendengarkan port `9009` dan menjalankan endpoint:

- `http://127.0.0.1:9009/mcp`
- `/health` untuk cek hidupnya service
- `POST` JSON-RPC dengan method seperti `initialize`, `ping`, `tools/list`, `resources/list`, `prompts/list`, dan `tools/call`

ia mem-parsing request, memeriksa auth bearer bila diaktifkan, lalu memanggil tool dari registry.

### 4. toolregistry dan tooldf
`ToolRegistry.java` plus `ToolDef.java` mendefinisikan semua tools yang bisa diakses agent. tool diorganisir berdasarkan kategori, contohnya:

- device
- network
- download
- files
- contacts
- sms
- calllog
- phone
- calendar
- media
- camera
- audio
- tts
- location
- wifi
- bluetooth
- apps
- notifications
- usage
- accessibility
- clipboard
- displaywrite
- ui
- hardware
- settings
- screen
- system
- tiktok
- terminal

fitur ini artinya app ini mengandung kemungkinan akses yang sangat luas ke data dan kemampuan perangkat. ini memang fokusnya sebagai "phone tool server" untuk agent AI.

### 5. toolsettings
`ToolSettings.java` mengatur flag per tool, mengganti auth, server enabled, dan token. ini membuat app bisa diaktifkan atau dibatasi per tool. auth default di sini `true`, jadi request MCP cenderung butuh bearer token.

### 6. permisionutil dan permission android
`PermissionUtil.java` menangani izin runtime dan pengaturan android yang relevan. repo ini memang sangat sadar bahwa banyak fitur hanya bisa tersedia jika user memberi izin ke app: storage, notification access, accessibility, overlay, battery optimization, usage access, write settings, location, camera, dan lain-lain.

### 7. screen capture
`BrowserManager.java` sudah dipindah ke repository browser-for-models (lihat pembaruan terbaru).

`ScreenCaptureManager.java` dan `ScreenCaptureActivity.java` menangani screenshot dan capture layar dari perangkat. `PhoneAccessibilityService.java` dan `PhoneNotificationListener.java` memberi akses ke UI dan notifikasi sistem.

### 8. terminal dan desktop-like integration
`TerminalExecManager.java`, `TerminalLogActivity.java`, `TerminalResultService.java` menandakan repo ini punya integrasi termux/terminal. itu membuat app bisa berjalan seperti mesin kontrol android yang punya shell ke perangkat.

### 9. file ops dan phone file provider
`FileOps.java` dan `PhoneFileProvider.java` menyiapkan akses file serta sharing file ke aplikasi lain dengan URI. ini penting untuk AI yang perlu membaca, menulis, atau memberitahukan hasil ke sistem lain.

## fitur yang paling mencolok

repo ini jauh lebih kuat dan berisiko dibanding server mcp biasa karena mengandung tool seperti:

- akses kontak dan sms
- log panggilan
- akses notifikasi
- lokasi dan sensor
- akses kamera/mikrofon
- pengaturan sistem dan overlay
- file system dan media
- screen capture
- akses accessibility
- package usage
- AI terminal integration via termux

jadi project ini lebih cocok disebut sebagai "mobile device control server" daripada hanya MCP server biasa.

## alur kerja general

1. user membuka app
2. `MainActivity` menampilkan dashboard control
3. user menyalakan server MCP
4. `PhoneMcpService` berjalan di background sebagai foreground service
5. `McpServer` membuka socket di `127.0.0.1:9009`
6. client AI mengakses `http://127.0.0.1:9009/mcp`
7. client memanggil `tools/list` untuk melihat tool yang tersedia
8. client memanggil `tools/call` dengan nama tool dan args
9. service menjalankan operasi device-level sesuai izin yang ada
10. response dikembalikan dalam bentuk MCP json dengan `content` dan `structuredContent`

## keamanan dan risiko

repo ini sangat sensitif secara keamanan. tool yang ada bisa memungkinkan AI untuk:

- membaca pesan dan kontak
- melihat notifikasi
- mengakses lokasi
- mengontrol UI / aksesibilitas
- melihat file dan media
- membuka screen capture
- menulis pengaturan sistem

ini bukan app ringan. ini adalah tool "agentic operating system" untuk perangkat Android. karena itu:

- autentikasi bearer token sebaiknya selalu aktif pada environment yang rentan
- gunakan hanya di lingkungan yang sah dan aman
- batasi tool yang diaktifkan
- hindari menyalakan tunnel publik tanpa kontrol akses
- pikirkan efek privasi dan legalitas akses ke data pengguna

## build system

project ini memakai gradle android seperti yang terlihat di `build.gradle`:

```gradle
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:1.+'
    }
}
```

modul app:

```gradle
apply plugin: 'com.android.application'

android {
    compileSdkVersion 21
    buildToolsVersion "21.1.0"
    defaultConfig {
        applicationId "mcp.android.phone"
        minSdkVersion 14
        targetSdkVersion 29
        versionCode 1
        versionName "1.0"
    }
}
```

ini menunjukkan project legacy, mirip struktur android lama dengan `jcenter` dan gradle plugin 1.x.

## pengaturan manifest

`AndroidManifest.xml` mencakup banyak permission, seperti:

- internet
- baca/tulis storage
- notifikasi dan foreground service
- lokasi
- kamera/mikrofon
- bluetooth
- read contacts / sms / call log
- write settings
- overlay
- package usage
- accessibility service
- notification listener access
- request install package
- manage external storage

ini memperkuat kesimpulan bahwa repo ini benar-benar sistem control device, bukan cuma server mcp dasar.

## alur aksesibilitas dan interaksi UI

app menyiapkan:

- `PhoneAccessibilityService` dengan service config di `res/xml/accessibility_service_config.xml`
- `PhoneNotificationListener` untuk membaca notifikasi
- `ScreenCaptureManager` untuk capture layar
- `PhoneFileProvider` untuk file URI akses

semua ini membuka jalan buat AI mengontrol UI perangkat secara lebih luas daripada sekadar API HTTP.

## teknologi yang dipakai

- bahasa: java
- platform: android native
- service: foreground service
- protocol: mcp over http/json-rpc
- akses: permissions + accessibility + notification listener + usage stats + overlay + file provider
- extra: termux integration, cloudflared tunnel, screen capture
- network: localhost server + optional public tunnel

## catatan penting untuk pengembangan

- project ini membutuhkan perhatian besar pada permission dan keamanan
- banyak tool akan gagal kalau user tidak memberi izin runtime
- beberapa fitur memerlukan manajemen khusus vendor: vivo, samsung, xiaomi, dll
- aksesibilitas dan notification listener seringnya butuh setup manual di perangkat
- app berisiko tinggi untuk memata-matai data sensitif jika digunakan sembarangan
- tool yang tidak perlu sebaiknya dimatikan

## kesimpulan

repo `reyhanaarch64/android-mcp-server-app` adalah aplikasi android yang mengubah ponsel menjadi server kontrol perangkat untuk model ai. ini adalah implementasi MCP yang sangat luas dan berisiko, dengan tool-tool yang mengakses data dan fungsi sistem ponsel.

fitur paling khas project ini adalah kombinasi beberapa hal:

- server MCP local
- tool registry yang besar
- kontrol device-level
- akses notifikasi, kontaks, sms, lokasi, kamera, storage, ui, dan terminal
- bonding dengan permission android dan accessibility system

project ini lebih cocok dipahami sebagai "AI device agent runtime" ketimbang server mcp biasa.

## lisensi

repo ini menggunakan lisensi MIT. lihat file `LICENSE` untuk detail lengkap.

## rekomendasi lanjutan

hal-hal yang sangat disarankan untuk project ini:

- dokumentasi per kategori tool dan contoh payload request
- redaction untuk data sensitif seperti contact, sms, lokasi, notifications
- peringatan keamanan di README utama
- sandboxing untuk tool yang berisiko tinggi
- log audit untuk tool calls
- test harness dan mocking untuk tool
- pemisahan tool read-only vs write-sensitive
- `LICENSE` jelas dan disertakan di repo
- dokumentasi tentang permission yang harus diminta per fitur

---

repo ini sangat powerfull dan punya scope yang jauh lebih besar daripada server mcp biasa. karena itu, README dan dokumentasinya perlu dibuat dengan hati-hati agar jelas siapa yang bisa pakai, apa yang bisa diakses, dan risikonya.


[![M8ven Score](https://m8ven.ai/badge/mcp/reyhanaarch64/android-mcp-server-app)](https://m8ven.ai/mcp/reyhanaarch64/android-mcp-server-app?s=readme)
