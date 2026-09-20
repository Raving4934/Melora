package com.leyu.melora.playback;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;

/** 仅测试APK的独立SAF目录；所有操作被限制在自身cache，绝不访问用户下载目录。 */
public final class DownloadFixtureProvider extends DocumentsProvider {
    /** 模拟系统目录选择器：由目录所属UID发放前缀授权，不依赖shell特权。 */
    public static final class GrantActivity extends android.app.Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            String target = getPackageName().replaceFirst("\\.test$", "");
            grantUriPermission(target, DocumentsContract.buildTreeDocumentUri(getPackageName() + ".download-fixture", "root"),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            finish();
        }
    }
    private int failedWrites;
    private File root() {
        File dir = new File(getContext().getCacheDir(), "isolated-download-documents");
        dir.mkdirs();
        return dir;
    }
    private File file(String id) throws FileNotFoundException {
        if ("root".equals(id)) return root();
        if (id.isEmpty() || id.contains("/") || id.equals(".") || id.equals("..")) throw new FileNotFoundException("Invalid fixture ID");
        return new File(root(), id);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS });
        MatrixCursor.RowBuilder row = result.newRow();
        for (String key : result.getColumnNames()) {
            Object value = key.equals(DocumentsContract.Root.COLUMN_FLAGS) ? DocumentsContract.Root.FLAG_SUPPORTS_CREATE : "root";
            row.add(key, value);
        }
        return result;
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED });
    }
    private void add(MatrixCursor cursor, String id, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String key : cursor.getColumnNames()) {
            Object value = null;
            switch (key) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: value = id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: value = file.getName(); break;
                case DocumentsContract.Document.COLUMN_SIZE: value = file.length(); break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: value = file.lastModified(); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    value = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : (id.endsWith(".flac") ? "audio/flac" : "audio/mpeg"); break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    value = file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE :
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME; break;
            }
            row.add(key, value);
        }
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor result = cursor(projection); File target = file(id);
        if (target.exists()) add(result, id, target);
        return result;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String order) throws FileNotFoundException {
        MatrixCursor result = cursor(projection);
        File[] files = file(id).listFiles();
        if (files != null) for (File child : files) add(result, child.getName(), child);
        return result;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        File target = file(id);
        if (mode.contains("w") && failedWrites-- > 0) {
            try (FileOutputStream out = new FileOutputStream(target)) { out.write(new byte[] {1, 2}); }
            catch (Exception ignored) { }
            throw new FileNotFoundException("Injected download publication failure");
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException("Invalid parent");
        try { if (!file(name).createNewFile()) throw new FileNotFoundException("Already exists"); }
        catch (java.io.IOException e) { throw new FileNotFoundException(e.toString()); }
        return name;
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        File target = file(name);
        if (target.exists() || !file(id).renameTo(target)) throw new FileNotFoundException("Cannot rename fixture");
        return name;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (id.equals("root") || !file(id).delete()) throw new FileNotFoundException("Cannot delete fixture");
    }
    @Override public boolean isChildDocument(String parent, String child) { return parent.equals("root") && !child.equals("root"); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("reset")) {
            File[] files = root().listFiles(); if (files != null) for (File file : files) file.delete();
            failedWrites = 0; return Bundle.EMPTY;
        }
        if (method.equals("failNextWrite")) { failedWrites = 1; return Bundle.EMPTY; }
        return super.call(method, arg, extras);
    }
}
