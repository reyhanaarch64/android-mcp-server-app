package mcp.android.phone;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class FileOps {
    public static final int MAX_BASE64_BYTES = 16 * 1024 * 1024;

    private FileOps() {
    }

    public static File base(Context c, String root) throws Exception {
        if ("shared".equalsIgnoreCase(root)) {
            if (!PermissionUtil.hasAllFiles(c) && android.os.Build.VERSION.SDK_INT >= 30) {
                throw new SecurityException("Akses semua file belum diaktifkan");
            }
            return Environment.getExternalStorageDirectory().getCanonicalFile();
        }
        return c.getFilesDir().getCanonicalFile();
    }

    public static File resolve(Context c, String root, String path) throws Exception {
        if (path == null) throw new IllegalArgumentException("path wajib diisi");
        File b = base(c, root);
        File f = new File(b, path).getCanonicalFile();
        String bp = b.getCanonicalPath();
        if (!f.getPath().equals(bp) && !f.getPath().startsWith(bp + File.separator)) {
            throw new SecurityException("path keluar dari root yang dipilih");
        }
        return f;
    }

    public static JSONObject read(Context c, String root, String path, int maxBytes) throws Exception {
        if (maxBytes <= 0) maxBytes = 2 * 1024 * 1024;
        File f = resolve(c, root, path);
        if (!f.isFile()) throw new IllegalArgumentException("bukan file");
        if (f.length() > maxBytes) throw new IllegalArgumentException("file terlalu besar: " + f.length() + " byte");
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        String text = new String(out.toByteArray(), Charset.forName("UTF-8"));
        JSONObject r = new JSONObject();
        r.put("path", path);
        r.put("bytes", f.length());
        r.put("content", text);
        return r;
    }

    public static JSONObject write(Context c, String root, String path, String content, String encoding, boolean mustNotExist, boolean append) throws Exception {
        File f = resolve(c, root, path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("gagal membuat direktori");
        if (mustNotExist && f.exists()) throw new IllegalArgumentException("file sudah ada");
        byte[] bytes;
        if ("base64".equalsIgnoreCase(encoding)) {
            bytes = Base64.decode(content, Base64.DEFAULT);
            if (bytes.length > MAX_BASE64_BYTES) throw new IllegalArgumentException("data base64 melebihi batas 16 MB");
        } else bytes = content.getBytes(Charset.forName("UTF-8"));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(f, append));
        out.write(bytes);
        out.flush();
        out.close();
        JSONObject r = new JSONObject();
        r.put("path", path);
        r.put("bytesWritten", bytes.length);
        r.put("totalBytes", f.length());
        return r;
    }

    public static JSONObject list(Context c, String root, String path, boolean recursive, int limit) throws Exception {
        if (limit <= 0) limit = 5000;
        File start = resolve(c, root, path);
        if (!start.isDirectory()) throw new IllegalArgumentException("bukan direktori");
        JSONArray a = new JSONArray();
        List<File> queue = new ArrayList<File>();
        queue.add(start);
        int idx = 0;
        while (idx < queue.size() && a.length() < limit) {
            File dir = queue.get(idx++);
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                JSONObject o = new JSONObject();
                o.put("name", f.getName());
                o.put("path", relativize(base(c, root), f));
                o.put("directory", f.isDirectory());
                o.put("size", f.isFile() ? f.length() : 0);
                o.put("modified", f.lastModified());
                a.put(o);
                if (recursive && f.isDirectory() && a.length() < limit) queue.add(f);
                if (a.length() >= limit) break;
            }
        }
        JSONObject r = new JSONObject();
        r.put("items", a);
        r.put("count", a.length());
        return r;
    }

    public static void deleteRecursive(File f) throws Exception {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        if (!f.delete() && f.exists()) throw new IllegalStateException("gagal menghapus " + f.getAbsolutePath());
    }

    public static void copyRecursive(File from, File to) throws Exception {
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) throw new IllegalStateException("gagal membuat direktori tujuan");
            File[] list = from.listFiles();
            if (list != null) for (File child : list) copyRecursive(child, new File(to, child.getName()));
        } else {
            File parent = to.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = new BufferedInputStream(new FileInputStream(from));
            OutputStream out = new BufferedOutputStream(new FileOutputStream(to));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            out.close();
        }
    }

    public static String relativize(File base, File child) throws Exception {
        String b = base.getCanonicalPath();
        String c = child.getCanonicalPath();
        if (c.equals(b)) return ".";
        if (c.startsWith(b + File.separator)) return c.substring(b.length() + 1).replace(File.separatorChar, '/');
        return child.getName();
    }

    public static JSONObject zip(Context c, String root, String source, String zipPath) throws Exception {
        File src = resolve(c, root, source);
        File out = resolve(c, root, zipPath);
        if (src.equals(out)) throw new IllegalArgumentException("source dan zip tidak boleh sama");
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)));
        if (src.isDirectory()) addDirectory(zos, src, src.getName());
        else addFile(zos, src, src.getName());
        zos.close();
        JSONObject r = new JSONObject();
        r.put("zip", zipPath);
        r.put("bytes", out.length());
        return r;
    }

    private static void addDirectory(ZipOutputStream zos, File dir, String name) throws Exception {
        File[] list = dir.listFiles();
        if (list == null || list.length == 0) {
            zos.putNextEntry(new ZipEntry(name + "/"));
            zos.closeEntry();
            return;
        }
        for (File f : list) {
            String childName = name + "/" + f.getName();
            if (f.isDirectory()) addDirectory(zos, f, childName);
            else addFile(zos, f, childName);
        }
    }

    private static void addFile(ZipOutputStream zos, File f, String name) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setTime(f.lastModified());
        zos.putNextEntry(e);
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
        in.close();
        zos.closeEntry();
    }

    public static JSONObject unzip(Context c, String root, String zipPath, String destination) throws Exception {
        File zip = resolve(c, root, zipPath);
        File dst = resolve(c, root, destination);
        if (!dst.exists() && !dst.mkdirs()) throw new IllegalStateException("gagal membuat direktori tujuan");
        final String canonicalRoot = dst.getCanonicalPath();
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        byte[] buf = new byte[8192];
        int count = 0;
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            File target = new File(dst, e.getName()).getCanonicalFile();
            if (!target.getPath().equals(canonicalRoot) && !target.getPath().startsWith(canonicalRoot + File.separator)) {
                zis.closeEntry();
                throw new SecurityException("zip-slip ditolak: " + e.getName());
            }
            if (e.isDirectory()) {
                if (!target.exists()) target.mkdirs();
            } else {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                OutputStream out = new BufferedOutputStream(new FileOutputStream(target));
                int n;
                while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                out.close();
                target.setLastModified(e.getTime());
            }
            zis.closeEntry();
            count++;
        }
        zis.close();
        JSONObject r = new JSONObject();
        r.put("entries", count);
        r.put("destination", destination);
        return r;
    }

    public static String edit(String original, String find, String replace, boolean regex) {
        if (regex) return original.replaceAll(find, java.util.regex.Matcher.quoteReplacement(replace));
        return original.replace(find, replace);
    }
}
