package mcp.android.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Structural JSON editor. Each file in a batch is parsed, edited and committed
 * independently. A failed action never writes a partially edited document.
 */
public final class JsonToolsManager {
    private static final int MAX_FILES = 10;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FIND_RESULTS = 200;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final Context context;

    public JsonToolsManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized JSONObject execute(JSONObject args) throws Exception {
        JSONArray files = args.optJSONArray("files");
        if (files == null) throw new IllegalArgumentException("files wajib berupa array");
        if (files.length() < 1 || files.length() > MAX_FILES) {
            throw new IllegalArgumentException("jumlah file harus 1 sampai " + MAX_FILES);
        }

        JSONArray results = new JSONArray();
        ArrayList<String> seen = new ArrayList<String>();
        int succeeded = 0;
        int failed = 0;
        int dryRunCount = 0;

        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            JSONObject result;
            if (item == null) {
                result = fileError("", "entri files[" + i + "] harus berupa object");
            } else {
                String root = item.optString("root", "shared");
                String path = item.optString("path", "");
                try {
                    if (!"shared".equals(root) && !"internal".equals(root)) {
                        throw new IllegalArgumentException("root harus shared atau internal");
                    }
                    if (path.length() == 0) throw new IllegalArgumentException("path wajib diisi");
                    File target = FileOps.resolve(context, root, path);
                    String identity = target.getCanonicalPath();
                    if (seen.contains(identity)) {
                        throw new IllegalArgumentException("file yang sama muncul lebih dari sekali dalam batch");
                    }
                    seen.add(identity);
                    result = processFile(item, target, root, path);
                } catch (Exception e) {
                    result = fileError(path, safeMessage(e));
                    result.put("root", root);
                }
            }
            if ("success".equals(result.optString("status"))) {
                succeeded++;
                if (result.optBoolean("dryRun", false)) dryRunCount++;
            } else {
                failed++;
            }
            results.put(result);
        }

        JSONObject response = new JSONObject();
        response.put("success", failed == 0);
        response.put("totalFiles", files.length());
        response.put("succeeded", succeeded);
        response.put("failed", failed);
        response.put("dryRunFiles", dryRunCount);
        response.put("results", results);
        return response;
    }

    private JSONObject processFile(JSONObject item, File target, String root, String path) throws Exception {
        if (!target.isFile()) throw new IllegalArgumentException("file JSON tidak ditemukan atau bukan file");
        if (target.length() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("ukuran file melebihi batas " + MAX_FILE_BYTES + " byte");
        }
        byte[] originalBytes = readAll(target);
        String originalText = new String(originalBytes, UTF8);
        Object document = parseDocument(originalText);
        JSONArray actions = item.optJSONArray("actions");
        if (actions == null || actions.length() < 1) {
            throw new IllegalArgumentException("actions wajib berupa array dan minimal berisi satu operasi");
        }
        if (actions.length() > 100) throw new IllegalArgumentException("maksimal 100 operasi per file");

        JSONArray actionResults = new JSONArray();
        Object working = document;
        boolean hasMutation = false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) throw new IllegalArgumentException("actions[" + i + "] harus berupa object");
            String operation = action.optString("operation", "").toLowerCase(Locale.US);
            String key = action.optString("key", "");
            if (!"find".equals(operation) && !"get_structure".equals(operation) && !action.has("key")) {
                throw new IllegalArgumentException("actions[" + i + "] wajib menyertakan key; gunakan key \"\" hanya jika memang menargetkan root JSON");
            }
            if ("set".equals(operation) || "add".equals(operation) || "insert".equals(operation)
                    || "remove".equals(operation) || "merge".equals(operation) || "rename".equals(operation)) {
                hasMutation = true;
            }
            JSONObject actionResult;
            try {
                actionResult = executeAction(working, operation, key, action);
            } catch (Exception actionError) {
                throw new IllegalArgumentException("actions[" + i + "] operation=" + operation
                        + " key=" + key + ": " + safeMessage(actionError));
            }
            if (actionResult.has("__replacement")) {
                working = actionResult.get("__replacement");
                actionResult.remove("__replacement");
            }
            actionResult.put("index", i);
            actionResult.put("operation", operation);
            actionResult.put("key", key);
            actionResults.put(actionResult);
        }

        boolean dryRun = item.optBoolean("dry_run", false);
        boolean pretty = item.optBoolean("pretty_print", true);
        boolean makeBackup = item.optBoolean("backup", true);
        String output = hasMutation ? serialize(working, pretty) : originalText;
        byte[] outputBytes = output.getBytes(UTF8);
        if (outputBytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("hasil JSON melebihi batas " + MAX_FILE_BYTES + " byte");
        }
        String backupPath = null;

        if (!dryRun && hasMutation && !originalText.equals(output)) {
            if (makeBackup) backupPath = createBackup(target, originalBytes);
            atomicReplace(target, outputBytes);
        }

        JSONObject result = new JSONObject();
        result.put("root", root);
        result.put("path", path);
        result.put("absolutePath", target.getAbsolutePath());
        result.put("status", "success");
        result.put("dryRun", dryRun);
        result.put("readOnly", !hasMutation);
        result.put("backup", backupPath == null ? JSONObject.NULL : backupPath);
        result.put("operations", actionResults);
        result.put("operationCount", actionResults.length());
        result.put("bytesBefore", originalBytes.length);
        result.put("bytesAfter", outputBytes.length);
        result.put("changed", !originalText.equals(output));
        return result;
    }

    private JSONObject executeAction(Object document, String operation, String key, JSONObject action) throws Exception {
        JSONObject result = new JSONObject();
        if ("get".equals(operation)) {
            Object value = cloneJsonValue(getAt(document, parsePath(key)));
            result.put("found", true);
            result.put("value", value);
        } else if ("set".equals(operation)) {
            requireValue(action);
            Object value = action.get("value");
            Object replacement = setAt(document, parsePath(key), value, action.optBoolean("create_missing", false));
            result.put("changed", true);
            result.put("__replacement", replacement);
        } else if ("add".equals(operation)) {
            requireValue(action);
            Object target = getAt(document, parsePath(key));
            if (!(target instanceof JSONArray)) throw new IllegalArgumentException("add hanya berlaku untuk target array");
            ((JSONArray) target).put(action.get("value"));
            result.put("length", ((JSONArray) target).length());
        } else if ("insert".equals(operation)) {
            requireValue(action);
            Object target = getAt(document, parsePath(key));
            if (!(target instanceof JSONArray)) throw new IllegalArgumentException("insert hanya berlaku untuk target array");
            JSONArray source = (JSONArray) target;
            int index = action.optInt("index", source.length());
            if (index < 0 || index > source.length()) throw new IllegalArgumentException("index array di luar rentang");
            JSONArray updated = new JSONArray();
            for (int i = 0; i < source.length() + 1; i++) {
                if (i == index) updated.put(action.get("value"));
                if (i < source.length()) updated.put(source.get(i));
            }
            Object replacement = setAt(document, parsePath(key), updated, false);
            result.put("length", updated.length());
            result.put("__replacement", replacement);
        } else if ("remove".equals(operation)) {
            Object replacement = removeAt(document, parsePath(key));
            result.put("removed", true);
            result.put("__replacement", replacement);
        } else if ("merge".equals(operation)) {
            Object current = getAt(document, parsePath(key));
            Object incoming = action.opt("value");
            if (!(current instanceof JSONObject) || !(incoming instanceof JSONObject)) {
                throw new IllegalArgumentException("merge membutuhkan target dan value berupa object");
            }
            String mode = action.optString("merge_mode", "deep");
            if (!"deep".equalsIgnoreCase(mode) && !"shallow".equalsIgnoreCase(mode)) {
                throw new IllegalArgumentException("merge_mode harus deep atau shallow");
            }
            JSONObject merged = mergeObjects((JSONObject) current, (JSONObject) incoming, "deep".equalsIgnoreCase(mode));
            Object replacement = setAt(document, parsePath(key), merged, false);
            result.put("mergedKeys", ((JSONObject) incoming).length());
            result.put("mergeMode", mode);
            result.put("__replacement", replacement);
        } else if ("rename".equals(operation)) {
            String newKey = action.optString("new_key", "");
            if (newKey.length() == 0) throw new IllegalArgumentException("new_key wajib diisi");
            Object replacement = renameAt(document, parsePath(key), newKey);
            result.put("renamed", true);
            result.put("newKey", newKey);
            result.put("__replacement", replacement);
        } else if ("find".equals(operation)) {
            String query = action.optString("query", "");
            if (query.length() == 0) throw new IllegalArgumentException("query wajib diisi untuk find");
            JSONArray matches = new JSONArray();
            findKeys(document, query, "", matches, MAX_FIND_RESULTS);
            result.put("matches", matches);
            result.put("count", matches.length());
            result.put("truncated", matches.length() >= MAX_FIND_RESULTS);
        } else if ("get_structure".equals(operation)) {
            Object target = key.length() == 0 ? document : getAt(document, parsePath(key));
            result.put("structure", describe(target, 0));
        } else {
            throw new IllegalArgumentException("operasi JSON tidak dikenal: " + operation);
        }
        return result;
    }

    private Object parseDocument(String text) throws Exception {
        if (text != null && text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (tokener.nextClean() != 0) throw new JSONException("ada karakter tambahan setelah nilai JSON utama");
        return value;
    }

    private Object cloneJsonValue(Object value) throws Exception {
        if (value instanceof JSONObject) return new JSONObject(value.toString());
        if (value instanceof JSONArray) return new JSONArray(value.toString());
        return value;
    }

    private String serialize(Object value, boolean pretty) throws Exception {
        if (value instanceof JSONObject) return pretty ? ((JSONObject) value).toString(2) : value.toString();
        if (value instanceof JSONArray) return pretty ? ((JSONArray) value).toString(2) : value.toString();
        if (value == JSONObject.NULL || value == null) return "null";
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    private List<String> parsePath(String path) {
        ArrayList<String> parts = new ArrayList<String>();
        if (path == null || path.length() == 0) return parts;
        if (path.charAt(0) == '/') {
            String[] split = path.substring(1).split("/", -1);
            for (int i = 0; i < split.length; i++) {
                parts.add(split[i].replace("~1", "/").replace("~0", "~"));
            }
            return parts;
        }
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '\\' && i + 1 < path.length()) {
                token.append(path.charAt(++i));
            } else if (ch == '.') {
                parts.add(token.toString());
                token.setLength(0);
            } else if (ch == '[') {
                if (token.length() > 0) {
                    parts.add(token.toString());
                    token.setLength(0);
                }
                int end = path.indexOf(']', i + 1);
                if (end < 0) throw new IllegalArgumentException("path bracket tidak ditutup");
                String inside = path.substring(i + 1, end).trim();
                if ((inside.startsWith("\"") && inside.endsWith("\"")) || (inside.startsWith("'") && inside.endsWith("'"))) {
                    inside = inside.substring(1, inside.length() - 1);
                }
                parts.add(inside);
                i = end;
                if (i + 1 < path.length() && path.charAt(i + 1) == '.') i++;
            } else {
                token.append(ch);
            }
        }
        if (token.length() > 0 || path.endsWith(".")) parts.add(token.toString());
        return parts;
    }

    private Object getAt(Object root, List<String> path) throws Exception {
        Object current = root;
        for (int i = 0; i < path.size(); i++) current = getChild(current, path.get(i));
        return current;
    }

    private Object getChild(Object parent, String key) throws Exception {
        if (parent instanceof JSONObject) {
            JSONObject object = (JSONObject) parent;
            if (!object.has(key)) throw new IllegalArgumentException("key tidak ditemukan: " + key);
            return object.get(key);
        }
        if (parent instanceof JSONArray) {
            JSONArray array = (JSONArray) parent;
            int index = parseArrayIndex(key, false);
            if (index < 0 || index >= array.length()) throw new IllegalArgumentException("index array tidak ditemukan: " + key);
            return array.get(index);
        }
        throw new IllegalArgumentException("path melewati nilai non-object/non-array pada key: " + key);
    }

    private Object setAt(Object root, List<String> path, Object value, boolean createMissing) throws Exception {
        if (path.size() == 0) return value;
        Object parent = root;
        for (int i = 0; i < path.size() - 1; i++) {
            String part = path.get(i);
            String next = path.get(i + 1);
            if (parent instanceof JSONObject) {
                JSONObject object = (JSONObject) parent;
                if (!object.has(part) || object.isNull(part)) {
                    if (!createMissing) throw new IllegalArgumentException("parent path tidak ditemukan: " + part);
                    object.put(part, newContainer(next));
                }
                parent = object.get(part);
            } else if (parent instanceof JSONArray) {
                JSONArray array = (JSONArray) parent;
                int index = parseArrayIndex(part, false);
                if (index < 0 || index > array.length()) throw new IllegalArgumentException("index parent array di luar rentang: " + part);
                if (index == array.length()) {
                    if (!createMissing) throw new IllegalArgumentException("parent index array tidak ditemukan: " + part);
                    array.put(newContainer(next));
                } else if (array.isNull(index)) {
                    if (!createMissing) throw new IllegalArgumentException("parent array bernilai null: " + part);
                    array.put(index, newContainer(next));
                }
                parent = array.get(index);
            } else {
                throw new IllegalArgumentException("parent path bukan object atau array: " + part);
            }
        }
        String last = path.get(path.size() - 1);
        if (parent instanceof JSONObject) {
            ((JSONObject) parent).put(last, value);
        } else if (parent instanceof JSONArray) {
            JSONArray array = (JSONArray) parent;
            if ("-".equals(last)) array.put(value);
            else {
                int index = parseArrayIndex(last, false);
                if (index < 0 || index > array.length()) throw new IllegalArgumentException("index array di luar rentang: " + last);
                if (index == array.length()) {
                    if (!createMissing) throw new IllegalArgumentException("index array belum ada; gunakan '-' atau create_missing");
                    array.put(value);
                } else array.put(index, value);
            }
        } else {
            throw new IllegalArgumentException("parent target bukan object atau array");
        }
        return root;
    }

    private Object newContainer(String nextKey) {
        if (isArrayIndex(nextKey) || "-".equals(nextKey)) return new JSONArray();
        return new JSONObject();
    }

    private boolean isArrayIndex(String value) {
        if (value == null || value.length() == 0) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        return true;
    }

    private int parseArrayIndex(String value, boolean allowEnd) {
        try {
            if (!isArrayIndex(value)) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("index array harus berupa bilangan bulat non-negatif: " + value);
        }
    }

    private Object removeAt(Object root, List<String> path) throws Exception {
        if (path.size() == 0) throw new IllegalArgumentException("root JSON tidak bisa dihapus");
        ArrayList<String> parentPath = new ArrayList<String>(path.subList(0, path.size() - 1));
        Object parent = getAt(root, parentPath);
        String last = path.get(path.size() - 1);
        if (parent instanceof JSONObject) {
            JSONObject object = (JSONObject) parent;
            if (!object.has(last)) throw new IllegalArgumentException("key tidak ditemukan: " + last);
            object.remove(last);
        } else if (parent instanceof JSONArray) {
            JSONArray source = (JSONArray) parent;
            int index = parseArrayIndex(last, false);
            if (index < 0 || index >= source.length()) throw new IllegalArgumentException("index array tidak ditemukan: " + last);
            JSONArray updated = new JSONArray();
            for (int i = 0; i < source.length(); i++) if (i != index) updated.put(source.get(i));
            return setAt(root, parentPath, updated, false);
        } else {
            throw new IllegalArgumentException("parent target bukan object atau array");
        }
        return root;
    }

    private Object renameAt(Object root, List<String> path, String newKey) throws Exception {
        if (path.size() == 0) throw new IllegalArgumentException("root JSON tidak memiliki nama key");
        ArrayList<String> parentPath = new ArrayList<String>(path.subList(0, path.size() - 1));
        Object parent = getAt(root, parentPath);
        if (!(parent instanceof JSONObject)) throw new IllegalArgumentException("rename hanya berlaku untuk key di dalam object");
        JSONObject source = (JSONObject) parent;
        String oldKey = path.get(path.size() - 1);
        if (!source.has(oldKey)) throw new IllegalArgumentException("key lama tidak ditemukan: " + oldKey);
        if (source.has(newKey)) throw new IllegalArgumentException("new_key sudah digunakan: " + newKey);
        JSONObject updated = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String current = keys.next();
            updated.put(oldKey.equals(current) ? newKey : current, source.get(current));
        }
        return setAt(root, parentPath, updated, false);
    }

    private JSONObject mergeObjects(JSONObject current, JSONObject incoming, boolean deep) throws Exception {
        JSONObject result = new JSONObject(current.toString());
        Iterator<String> keys = incoming.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object newValue = incoming.get(key);
            Object oldValue = result.opt(key);
            if (deep && oldValue instanceof JSONObject && newValue instanceof JSONObject) {
                result.put(key, mergeObjects((JSONObject) oldValue, (JSONObject) newValue, true));
            } else {
                result.put(key, newValue);
            }
        }
        return result;
    }

    private void findKeys(Object node, String query, String path, JSONArray out, int limit) throws Exception {
        if (out.length() >= limit) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext() && out.length() < limit) {
                String key = keys.next();
                String childPath = path + "/" + key.replace("~", "~0").replace("/", "~1");
                if (key.equalsIgnoreCase(query) || key.toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US))) {
                    JSONObject match = new JSONObject();
                    match.put("key", key);
                    match.put("path", childPath);
                    match.put("type", typeOf(object.get(key)));
                    out.put(match);
                }
                findKeys(object.get(key), query, childPath, out, limit);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && out.length() < limit; i++) {
                findKeys(array.get(i), query, path + "/" + i, out, limit);
            }
        }
    }

    private JSONObject describe(Object node, int depth) throws Exception {
        JSONObject out = new JSONObject();
        out.put("type", typeOf(node));
        if (depth >= 8) {
            out.put("truncated", true);
            return out;
        }
        if (node instanceof JSONObject) {
            JSONObject fields = new JSONObject();
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            int count = 0;
            while (keys.hasNext() && count < 200) {
                String key = keys.next();
                fields.put(key, describe(object.get(key), depth + 1));
                count++;
            }
            out.put("fields", fields);
            out.put("fieldCount", object.length());
            if (object.length() > count) out.put("truncated", true);
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            JSONArray sample = new JSONArray();
            int count = Math.min(array.length(), 20);
            for (int i = 0; i < count; i++) sample.put(describe(array.get(i), depth + 1));
            out.put("length", array.length());
            out.put("sample", sample);
            if (array.length() > count) out.put("sampleTruncated", true);
        }
        return out;
    }

    private String typeOf(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return value.getClass().getSimpleName();
    }

    private void requireValue(JSONObject action) {
        if (!action.has("value")) throw new IllegalArgumentException("value wajib tersedia untuk operasi ini");
    }

    private byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        try {
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_FILE_BYTES) throw new IllegalArgumentException("file melebihi batas " + MAX_FILE_BYTES + " byte saat dibaca");
                out.write(buffer, 0, count);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private String createBackup(File target, byte[] original) throws Exception {
        File parent = target.getParentFile();
        String base = target.getName() + ".bak";
        File backup = new File(parent, base);
        int suffix = 1;
        while (backup.exists() && suffix < 1000) backup = new File(parent, base + "." + suffix++);
        if (backup.exists()) throw new IllegalStateException("tidak bisa menentukan nama backup yang tersedia");
        writeSynced(backup, original);
        return backup.getAbsolutePath();
    }

    private void atomicReplace(File target, byte[] bytes) throws Exception {
        File parent = target.getParentFile();
        String nonce = UUID.randomUUID().toString();
        File temp = new File(parent, target.getName() + ".tmp." + nonce);
        File rollback = new File(parent, target.getName() + ".rollback." + nonce);
        boolean movedOriginal = false;
        try {
            writeSynced(temp, bytes);
            if (!target.renameTo(rollback)) {
                throw new IllegalStateException("gagal memindahkan file lama ke file rollback");
            }
            movedOriginal = true;
            if (!temp.renameTo(target)) {
                if (movedOriginal) rollback.renameTo(target);
                throw new IllegalStateException("gagal mengganti file JSON secara atomik");
            }
            if (rollback.exists()) rollback.delete();
        } finally {
            if (temp.exists()) temp.delete();
            if (rollback.exists() && !target.exists()) rollback.renameTo(target);
            else if (rollback.exists()) rollback.delete();
        }
    }

    private void writeSynced(File file, byte[] bytes) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    private JSONObject fileError(String path, String message) throws Exception {
        JSONObject result = new JSONObject();
        result.put("path", path);
        result.put("status", "failed");
        result.put("error", message);
        return result;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.length() == 0 ? e.getClass().getSimpleName() : message;
    }
}
