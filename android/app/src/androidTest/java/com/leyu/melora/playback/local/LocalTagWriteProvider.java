package com.leyu.melora.playback.local;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** 本地标签专用夹具，与备份测试的文件、故障开关及 Provider 契约完全隔离。 */
public final class LocalTagWriteProvider extends ContentProvider {
    private int writesToFail;
    private boolean hiddenMetadata;

    private File file() {
        if (getContext() == null) throw new IllegalStateException("Missing provider context");
        return new File(getContext().getCacheDir(), "isolated-local-tag-provider.mp3");
    }

    @Override public boolean onCreate() { return true; }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode.contains("w") && writesToFail > 0) {
            writesToFail--;
            try (FileOutputStream out = new FileOutputStream(file())) { out.write(new byte[] {1, 2, 3}); }
            catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            throw new FileNotFoundException("Injected local tag write failure");
        }
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "reset":
                try (FileOutputStream ignored = new FileOutputStream(file())) {
                    writesToFail = 0;
                    hiddenMetadata = false;
                } catch (IOException error) { throw new IllegalStateException(error); }
                break;
            case "failNextWrite": writesToFail = 1; break;
            case "hideMetadata": hiddenMetadata = true; break;
            case "showMetadata": hiddenMetadata = false; break;
            case "setModified":
                if (!file().setLastModified(Long.parseLong(arg))) throw new IllegalStateException("Cannot set fixture timestamp");
                break;
            default: throw new IllegalArgumentException("Unknown fixture operation");
        }
        return Bundle.EMPTY;
    }

    @Override public String getType(Uri uri) { return "audio/mpeg"; }

    @Override public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (hiddenMetadata) row.add(null);
            else if (column.equals(DocumentsContract.Document.COLUMN_SIZE)) row.add(file().length());
            else if (column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) row.add(file().lastModified());
            else row.add(null);
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
