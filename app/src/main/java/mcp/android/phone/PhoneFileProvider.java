package mcp.android.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class PhoneFileProvider extends ContentProvider {
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        String root = uri.getQueryParameter("root");
        String path = uri.getQueryParameter("path");
        if (root == null) root = "internal";
        if (path == null) throw new FileNotFoundException("path kosong");
        File base = "shared".equals(root) ? Environment.getExternalStorageDirectory() : c.getFilesDir();
        try {
            File f = new File(base, path).getCanonicalFile();
            String b = base.getCanonicalPath();
            if (!f.getPath().equals(b) && !f.getPath().startsWith(b + File.separator)) throw new FileNotFoundException("path di luar root");
            if (!f.exists() || !f.isFile()) throw new FileNotFoundException("file tidak ditemukan");
            return f;
        } catch (Exception e) {
            if (e instanceof FileNotFoundException) throw (FileNotFoundException) e;
            throw new FileNotFoundException(e.toString());
        }
    }

    public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public String getType(Uri uri) {
        String type = uri.getQueryParameter("mime");
        return type == null ? "application/octet-stream" : type;
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = resolve(uri);
            c.addRow(new Object[]{f.getName(), f.length()});
        } catch (Exception ignored) {
        }
        return c;
    }

    public int delete(Uri uri, String s, String[] a) { return 0; }
    public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    public Uri insert(Uri uri, ContentValues v) { return null; }
}
