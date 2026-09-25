package mcp.android.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ToolRegistry {
    private static final LinkedHashMap<String, ToolDef> TOOLS = new LinkedHashMap<String, ToolDef>();

    private ToolRegistry() {
    }

    private static JSONObject schema() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "object");
            o.put("properties", new JSONObject());
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject schema(String[] names, String[] types, String[] descriptions, String[] required) {
        JSONObject o = schema();
        try {
            JSONObject props = o.getJSONObject("properties");
            for (int i = 0; i < names.length; i++) {
                JSONObject p = new JSONObject();
                p.put("type", types[i]);
                if (descriptions != null && descriptions[i] != null) {
                    p.put("description", descriptions[i]);
                }
                props.put(names[i], p);
            }
            if (required != null && required.length > 0) {
                JSONArray r = new JSONArray();
                for (String s : required) r.put(s);
                o.put("required", r);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void add(String name, String desc, JSONObject schema, String category, int mode, boolean enabled) {
        TOOLS.put(name, new ToolDef(name, desc, schema, category, mode, enabled));
    }

    static {
        add("device.spec", "Melihat spesifikasi perangkat. Parameter section dapat membatasi kategori.", schema(
                new String[]{"section"}, new String[]{"string"}, new String[]{"all, model, android, storage, memory, display, cpu, battery, network"}, null), "device", ToolDef.ACCESS_READ, true);
        add("device.info", "Melihat informasi perangkat dan runtime Android.", schema(), "device", ToolDef.ACCESS_READ, true);
        add("battery.info", "Melihat status baterai, suhu, tegangan, dan sumber daya.", schema(), "device", ToolDef.ACCESS_READ, true);
        add("display.info", "Melihat ukuran layar, density, refresh rate, dan informasi FPS yang tersedia.", schema(), "device", ToolDef.ACCESS_READ, true);
        add("sensor.list", "Mendaftar sensor perangkat yang tersedia.", schema(), "device", ToolDef.ACCESS_READ, true);
        add("sensor.read", "Membaca satu sampel sensor dengan sensorType integer Android.", schema(new String[]{"sensorType","timeoutMs"}, new String[]{"integer","integer"}, new String[]{"Constants Sensor.TYPE_*","Batas tunggu 100-3000 ms"}, new String[]{"sensorType"}), "device", ToolDef.ACCESS_READ, true);
        add("storage.info", "Melihat total dan ruang tersisa pada penyimpanan.", schema(), "device", ToolDef.ACCESS_READ, true);
        add("network.info", "Melihat status jaringan aktif dan alamat jaringan perangkat.", schema(), "network", ToolDef.ACCESS_READ, true);
        add("time.now", "Melihat waktu perangkat saat ini.", schema(), "device", ToolDef.ACCESS_READ, true);
        add("toast.show", "Menampilkan toast kepada pengguna.", schema(new String[]{"message","duration"}, new String[]{"string","string"}, new String[]{"Isi toast","short atau long"}, new String[]{"message"}), "ui", ToolDef.ACCESS_WRITE, true);
        add("url.open", "Membuka URL menggunakan aplikasi yang tersedia.", schema(new String[]{"url"}, new String[]{"string"}, new String[]{"URL lengkap"}, new String[]{"url"}), "ui", ToolDef.ACCESS_WRITE, true);
        add("clipboard.read", "Membaca teks clipboard bila Android mengizinkannya.", schema(), "clipboard", ToolDef.ACCESS_READ, false);
        add("clipboard.write", "Menulis teks ke clipboard.", schema(new String[]{"text"}, new String[]{"string"}, new String[]{"Teks"}, new String[]{"text"}), "clipboard", ToolDef.ACCESS_WRITE, false);

        add("file.read", "Membaca isi file dari internal atau shared storage.", schema(new String[]{"root","path","maxBytes"}, new String[]{"string","string","integer"}, new String[]{"internal atau shared","Path relatif","Batas byte, default 2 MB"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_READ, false);
        add("file.write", "Menulis atau menimpa file.", schema(new String[]{"root","path","content","encoding"}, new String[]{"string","string","string","string"}, new String[]{"internal atau shared","Path relatif","Isi","UTF-8 atau base64"}, new String[]{"root","path","content"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.create", "Membuat file baru dan gagal bila sudah ada.", schema(new String[]{"root","path","content"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Path relatif","Isi awal"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.append", "Menambahkan teks ke akhir file.", schema(new String[]{"root","path","content"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Path relatif","Isi tambahan"}, new String[]{"root","path","content"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.delete", "Menghapus file atau direktori kosong.", schema(new String[]{"root","path"}, new String[]{"string","string"}, new String[]{"internal atau shared","Path relatif"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.exists", "Memeriksa apakah file atau direktori ada.", schema(new String[]{"root","path"}, new String[]{"string","string"}, new String[]{"internal atau shared","Path relatif"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_READ, false);
        add("file.list", "Mendaftar isi direktori.", schema(new String[]{"root","path","recursive","limit"}, new String[]{"string","string","boolean","integer"}, new String[]{"internal atau shared","Direktori relatif","Baca rekursif","Batas item"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_READ, false);
        add("file.mkdir", "Membuat direktori.", schema(new String[]{"root","path"}, new String[]{"string","string"}, new String[]{"internal atau shared","Path relatif"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.copy", "Menyalin file atau direktori.", schema(new String[]{"root","from","to"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Sumber","Tujuan"}, new String[]{"root","from","to"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.move", "Memindahkan file atau direktori.", schema(new String[]{"root","from","to"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Sumber","Tujuan"}, new String[]{"root","from","to"}), "files", ToolDef.ACCESS_WRITE, false);
        add("zip.create", "Membuat arsip ZIP dari file atau direktori.", schema(new String[]{"root","source","zip"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Sumber","Path ZIP tujuan"}, new String[]{"root","source","zip"}), "files", ToolDef.ACCESS_WRITE, false);
        add("zip.extract", "Mengekstrak ZIP secara aman tanpa zip-slip ke luar root.", schema(new String[]{"root","zip","destination"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Path ZIP","Direktori tujuan"}, new String[]{"root","zip","destination"}), "files", ToolDef.ACCESS_WRITE, false);
        add("editor.read_file", "Editor MCP: membaca file teks.", schema(new String[]{"root","path","maxBytes"}, new String[]{"string","string","integer"}, new String[]{"internal atau shared","Path file","Batas byte"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_READ, false);
        add("editor.create_file", "Editor MCP: membuat file kode/teks baru.", schema(new String[]{"root","path","content"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Path file","Isi file"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_WRITE, false);
        add("editor.edit_file", "Editor MCP: mengganti teks secara literal atau regex.", schema(new String[]{"root","path","find","replace","regex"}, new String[]{"string","string","string","string","boolean"}, new String[]{"internal atau shared","Path file","Teks yang dicari","Pengganti","Gunakan regex"}, new String[]{"root","path","find","replace"}), "files", ToolDef.ACCESS_WRITE, false);

        add("contacts.read", "Membaca daftar kontak.", schema(new String[]{"query","limit"}, new String[]{"string","integer"}, new String[]{"Pencarian nama/nomor","Batas hasil"}, null), "contacts", ToolDef.ACCESS_READ, false);
        add("contacts.list", "Membaca seluruh daftar kontak yang dapat dikembalikan oleh ContactsProvider.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas hasil, default 10000"}, null), "contacts", ToolDef.ACCESS_READ, false);
        add("contacts.create", "Membuat kontak dengan nama dan nomor.", schema(new String[]{"name","phone"}, new String[]{"string","string"}, new String[]{"Nama","Nomor"}, new String[]{"name","phone"}), "contacts", ToolDef.ACCESS_WRITE, false);
        add("contacts.update", "Memperbarui nama dan/atau nomor kontak berdasarkan rawContactId.", schema(new String[]{"rawContactId","name","phone"}, new String[]{"string","string","string"}, new String[]{"ID raw contact","Nama baru","Nomor baru"}, new String[]{"rawContactId"}), "contacts", ToolDef.ACCESS_WRITE, false);
        add("contacts.delete", "Menghapus raw contact berdasarkan rawContactId.", schema(new String[]{"rawContactId"}, new String[]{"string"}, new String[]{"ID raw contact"}, new String[]{"rawContactId"}), "contacts", ToolDef.ACCESS_WRITE, false);

        add("sms.read", "Membaca SMS inbox, sent, atau draft.", schema(new String[]{"folder","limit"}, new String[]{"string","integer"}, new String[]{"all, inbox, sent, draft","Batas hasil"}, null), "sms", ToolDef.ACCESS_READ, false);
        add("sms.send", "Mengirim SMS menggunakan SIM yang tersedia.", schema(new String[]{"to","message"}, new String[]{"string","string"}, new String[]{"Nomor tujuan","Isi SMS"}, new String[]{"to","message"}), "sms", ToolDef.ACCESS_WRITE, false);
        add("sms.delete", "Menghapus SMS berdasarkan ID. Biasanya mensyaratkan aplikasi SMS default.", schema(new String[]{"id"}, new String[]{"string"}, new String[]{"ID SMS"}, new String[]{"id"}), "sms", ToolDef.ACCESS_WRITE, false);
        add("sms.mark_read", "Menandai SMS telah dibaca. Biasanya mensyaratkan aplikasi SMS default.", schema(new String[]{"id","read"}, new String[]{"string","boolean"}, new String[]{"ID SMS","true untuk dibaca"}, new String[]{"id","read"}), "sms", ToolDef.ACCESS_WRITE, false);

        add("calllog.read", "Membaca riwayat panggilan.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas hasil"}, null), "calllog", ToolDef.ACCESS_READ, false);
        add("calllog.delete", "Menghapus item riwayat panggilan berdasarkan ID.", schema(new String[]{"id"}, new String[]{"string"}, new String[]{"ID log"}, new String[]{"id"}), "calllog", ToolDef.ACCESS_WRITE, false);
        add("phone.dial", "Membuka dialer dengan nomor yang diisi.", schema(new String[]{"number"}, new String[]{"string"}, new String[]{"Nomor"}, new String[]{"number"}), "phone", ToolDef.ACCESS_WRITE, false);
        add("phone.call", "Melakukan panggilan langsung bila CALL_PHONE diizinkan.", schema(new String[]{"number"}, new String[]{"string"}, new String[]{"Nomor"}, new String[]{"number"}), "phone", ToolDef.ACCESS_WRITE, false);

        add("calendar.read", "Membaca event kalender mendatang.", schema(new String[]{"from","to","limit"}, new String[]{"integer","integer","integer"}, new String[]{"Millis awal","Millis akhir","Batas hasil"}, null), "calendar", ToolDef.ACCESS_READ, false);
        add("calendar.create", "Membuat event kalender pada kalender pertama yang dapat ditulis.", schema(new String[]{"title","description","start","end"}, new String[]{"string","string","integer","integer"}, new String[]{"Judul","Deskripsi","Millis awal","Millis akhir"}, new String[]{"title","start","end"}), "calendar", ToolDef.ACCESS_WRITE, false);
        add("calendar.update", "Memperbarui judul dan waktu event kalender.", schema(new String[]{"id","title","start","end"}, new String[]{"string","string","integer","integer"}, new String[]{"ID event","Judul","Millis awal","Millis akhir"}, new String[]{"id"}), "calendar", ToolDef.ACCESS_WRITE, false);
        add("calendar.delete", "Menghapus event kalender.", schema(new String[]{"id"}, new String[]{"string"}, new String[]{"ID event"}, new String[]{"id"}), "calendar", ToolDef.ACCESS_WRITE, false);

        add("media.images", "Mendaftar gambar pada MediaStore.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas hasil"}, null), "media", ToolDef.ACCESS_READ, false);
        add("media.videos", "Mendaftar video pada MediaStore.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas hasil"}, null), "media", ToolDef.ACCESS_READ, false);
        add("media.audio", "Mendaftar audio pada MediaStore.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas hasil"}, null), "media", ToolDef.ACCESS_READ, false);
        add("media.delete", "Menghapus media berdasarkan mediaUri.", schema(new String[]{"uri"}, new String[]{"string"}, new String[]{"Content URI media"}, new String[]{"uri"}), "media", ToolDef.ACCESS_WRITE, false);

        add("camera.open", "Membuka aplikasi kamera perangkat.", schema(), "camera", ToolDef.ACCESS_WRITE, false);
        add("audio.record_start", "Memulai perekaman mikrofon ke file M4A.", schema(new String[]{"root","path"}, new String[]{"string","string"}, new String[]{"internal atau shared","Path output"}, new String[]{"root","path"}), "audio", ToolDef.ACCESS_WRITE, false);
        add("audio.record_stop", "Menghentikan perekaman mikrofon.", schema(), "audio", ToolDef.ACCESS_WRITE, false);
        add("audio.volume_get", "Membaca volume stream audio.", schema(new String[]{"stream"}, new String[]{"string"}, new String[]{"music, ring, alarm, notification, system, voice_call"}, null), "audio", ToolDef.ACCESS_READ, false);
        add("audio.volume_set", "Mengatur volume stream audio.", schema(new String[]{"stream","value"}, new String[]{"string","integer"}, new String[]{"Jenis stream","Level"}, new String[]{"stream","value"}), "audio", ToolDef.ACCESS_WRITE, false);
        add("tts.stop", "Menghentikan Text-to-Speech.", schema(), "tts", ToolDef.ACCESS_WRITE, false);
        add("vibrate", "Mengaktifkan getaran selama durasi tertentu.", schema(new String[]{"milliseconds"}, new String[]{"integer"}, new String[]{"Durasi maksimum 5000 ms"}, new String[]{"milliseconds"}), "hardware", ToolDef.ACCESS_WRITE, false);
        add("flashlight.set", "Mengaktifkan atau mematikan lampu flash.", schema(new String[]{"enabled"}, new String[]{"boolean"}, new String[]{"Status"}, new String[]{"enabled"}), "hardware", ToolDef.ACCESS_WRITE, false);

        add("location.get", "Membaca lokasi terakhir yang tersedia.", schema(), "location", ToolDef.ACCESS_READ, false);
        add("wifi.info", "Melihat status Wi-Fi dan SSID yang tersedia.", schema(), "wifi", ToolDef.ACCESS_READ, false);
        add("wifi.scan", "Memulai dan membaca hasil pemindaian Wi-Fi.", schema(new String[]{"limit"}, new String[]{"integer"}, new String[]{"Batas jaringan"}, null), "wifi", ToolDef.ACCESS_READ, false);
        add("bluetooth.info", "Melihat status Bluetooth.", schema(), "bluetooth", ToolDef.ACCESS_READ, false);
        add("bluetooth.paired", "Melihat perangkat Bluetooth yang dipasangkan.", schema(), "bluetooth", ToolDef.ACCESS_READ, false);

        add("app.list", "Melihat daftar aplikasi pihak ketiga yang memiliki launcher activity.", schema(), "apps", ToolDef.ACCESS_READ, false);
        add("app.info", "Melihat metadata aplikasi berdasarkan package name.", schema(new String[]{"package"}, new String[]{"string"}, new String[]{"Package name"}, new String[]{"package"}), "apps", ToolDef.ACCESS_READ, false);
        add("app.open", "Membuka aplikasi berdasarkan package name.", schema(new String[]{"package"}, new String[]{"string"}, new String[]{"Package name"}, new String[]{"package"}), "apps", ToolDef.ACCESS_WRITE, false);
        add("app.uninstall_request", "Meminta pengguna menghapus aplikasi melalui dialog sistem.", schema(new String[]{"package"}, new String[]{"string"}, new String[]{"Package name"}, new String[]{"package"}), "apps", ToolDef.ACCESS_WRITE, false);
        add("share.text", "Membuka chooser berbagi teks.", schema(new String[]{"text","title"}, new String[]{"string","string"}, new String[]{"Isi","Judul chooser"}, new String[]{"text"}), "apps", ToolDef.ACCESS_WRITE, false);
        add("share.file", "Membuka chooser berbagi file menggunakan ContentProvider internal.", schema(new String[]{"root","path","mime"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Path file","MIME type"}, new String[]{"root","path"}), "apps", ToolDef.ACCESS_WRITE, false);
        add("download.url", "Mengunduh URL menggunakan DownloadManager.", schema(new String[]{"url","filename"}, new String[]{"string","string"}, new String[]{"URL","Nama file"}, new String[]{"url"}), "download", ToolDef.ACCESS_WRITE, false);

        add("notification.send", "Mengirim atau memperbarui notifikasi menggunakan ID yang diberikan.", schema(new String[]{"id","title","message"}, new String[]{"integer","string","string"}, new String[]{"ID notifikasi","Judul","Isi"}, new String[]{"id","title","message"}), "notifications", ToolDef.ACCESS_WRITE, false);
        add("notifications.read", "Membaca notifikasi aktif dan riwayat notifikasi yang tertangkap saat listener aktif melalui Notification Listener.", schema(), "notifications", ToolDef.ACCESS_READ, false);
        add("notifications.dismiss", "Menutup notifikasi aktif berdasarkan key.", schema(new String[]{"key"}, new String[]{"string"}, new String[]{"Notification key"}, new String[]{"key"}), "notifications", ToolDef.ACCESS_WRITE, false);
        add("usage.stats", "Membaca statistik penggunaan aplikasi melalui Usage Access.", schema(new String[]{"days","limit"}, new String[]{"integer","integer"}, new String[]{"Jumlah hari","Batas item"}, null), "usage", ToolDef.ACCESS_READ, false);
        add("accessibility.status", "Melihat apakah aksesibilitas Phone To MCP aktif.", schema(), "accessibility", ToolDef.ACCESS_READ, false);
        add("accessibility.global_action", "Menjalankan aksi global aksesibilitas seperti home, back, recents, notifications, dan quick settings.", schema(new String[]{"action"}, new String[]{"string"}, new String[]{"home, back, recents, notifications, quick_settings"}, new String[]{"action"}), "accessibility", ToolDef.ACCESS_WRITE, false);

        add("display.brightness_get", "Membaca brightness layar.", schema(), "displaywrite", ToolDef.ACCESS_READ, false);
        add("display.brightness_set", "Mengatur brightness layar. Memerlukan Write System Settings.", schema(new String[]{"value"}, new String[]{"integer"}, new String[]{"0-255"}, new String[]{"value"}), "displaywrite", ToolDef.ACCESS_WRITE, false);
        add("settings.app_info", "Membuka halaman informasi aplikasi Phone To MCP.", schema(), "settings", ToolDef.ACCESS_WRITE, true);
        add("settings.wifi", "Membuka pengaturan Wi-Fi.", schema(), "settings", ToolDef.ACCESS_WRITE, true);
        add("settings.bluetooth", "Membuka pengaturan Bluetooth.", schema(), "settings", ToolDef.ACCESS_WRITE, true);
        add("settings.location", "Membuka pengaturan lokasi.", schema(), "settings", ToolDef.ACCESS_WRITE, true);
        add("settings.notifications", "Membuka pengaturan notifikasi aplikasi.", schema(), "settings", ToolDef.ACCESS_WRITE, true);
        add("access.special_status", "Melihat status akses khusus Android yang tersedia.", schema(), "settings", ToolDef.ACCESS_READ, true);
        add("server.status", "Melihat status MCP server, port lokal, autentikasi, dan URL tunnel terakhir.", schema(), "settings", ToolDef.ACCESS_READ, true);
        add("access.permissions", "Melihat status permission Android penting untuk fitur Phone To MCP.", schema(), "settings", ToolDef.ACCESS_READ, true);
        add("mcp.tools_enabled", "Melihat tool MCP yang sedang diizinkan oleh konfigurasi aplikasi.", schema(), "settings", ToolDef.ACCESS_READ, true);
        add("pm.read", "Melihat seluruh aplikasi pihak ketiga yang terpasang, termasuk yang tidak memiliki launcher activity.", schema(), "apps", ToolDef.ACCESS_READ, false);
        add("spec.view", "Alias tampilan spesifikasi perangkat; section dapat diisi all, model, memory, storage, display, cpu, battery, network.", schema(new String[]{"section"}, new String[]{"string"}, new String[]{"Kategori spesifikasi"}, null), "device", ToolDef.ACCESS_READ, true);
        add("file.write_base64", "Membuat atau menimpa file dari data Base64 dengan batas 16 MB hasil dekode.", schema(new String[]{"root","path","base64"}, new String[]{"string","string","string"}, new String[]{"internal atau shared","Path file relatif","Data Base64"}, new String[]{"root","path","base64"}), "files", ToolDef.ACCESS_WRITE, false);
        add("file.read_base64", "Membaca file sebagai Base64 dengan batas 16 MB.", schema(new String[]{"root","path"}, new String[]{"string","string"}, new String[]{"internal atau shared","Path file relatif"}, new String[]{"root","path"}), "files", ToolDef.ACCESS_READ, false);
        add("app.current", "Melihat proses aplikasi yang sedang berjalan dan tingkat kepentingannya.", schema(), "apps", ToolDef.ACCESS_READ, false);
        add("screen.capture", "Mengambil tangkapan layar dan mengembalikan gambar ke client MCP. Parameter timer dalam detik.", schema(new String[]{"timer"}, new String[]{"integer"}, new String[]{"Waktu tunggu sebelum mengambil gambar, 0-30 detik"}, null), "screen", ToolDef.ACCESS_READ, false);
        add("screen.capcure", "Alias untuk screen.capture.", schema(new String[]{"timer"}, new String[]{"integer"}, new String[]{"Waktu tunggu sebelum mengambil gambar, 0-30 detik"}, null), "screen", ToolDef.ACCESS_READ, false);
        add("logcat.read", "Membaca keluaran logcat Android yang dapat diakses oleh aplikasi.", schema(new String[]{"lines","filter"}, new String[]{"integer","string"}, new String[]{"Jumlah baris maksimum","Filter teks opsional"}, null), "system", ToolDef.ACCESS_READ, false);
        add("tiktok.download", "Mengunduh media TikTok berdasarkan URL ke /storage/emulated/0/RHDOWN/Tiktok/<id> dan memperbarui MediaStore.", schema(new String[]{"url"}, new String[]{"string"}, new String[]{"URL TikTok"}, new String[]{"url"}), "tiktok", ToolDef.ACCESS_WRITE, false);
        add("tts.speak", "Membuat suara menggunakan Google Translate TTS dan memutarnya tanpa menyimpan file hasil.", schema(new String[]{"text","lang","queue"}, new String[]{"string","string","string"}, new String[]{"Teks","Kode bahasa, default id","flush atau add"}, new String[]{"text"}), "tts", ToolDef.ACCESS_WRITE, false);
    }

    public static ToolDef get(String name) {
        return TOOLS.get(name);
    }

    public static List<ToolDef> all() {
        return new ArrayList<ToolDef>(TOOLS.values());
    }

    public static JSONArray toJson(Context context) {
        JSONArray a = new JSONArray();
        List<ToolDef> list = all();
        Collections.sort(list, new Comparator<ToolDef>() {
            public int compare(ToolDef a, ToolDef b) {
                return a.name.compareTo(b.name);
            }
        });
        for (ToolDef t : list) {
            if (context == null || ToolSettings.isEnabled(context, t)) a.put(t.toJson());
        }
        return a;
    }

    public static JSONArray toJson() {
        JSONArray a = new JSONArray();
        List<ToolDef> list = all();
        Collections.sort(list, new Comparator<ToolDef>() {
            public int compare(ToolDef a, ToolDef b) {
                return a.name.compareTo(b.name);
            }
        });
        for (ToolDef t : list) a.put(t.toJson());
        return a;
    }
}
