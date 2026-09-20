package com.leyu.melora.playback;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 仅测试APK的独立进程：只用Android/Java API，不依赖目标APK的Kotlin运行库或用户文件。 */
public final class BackupFixtureProvider extends ContentProvider {
    private int writesToFail;
    private File file() {
        if (getContext() == null) throw new IllegalStateException("Missing provider context");
        return new File(getContext().getCacheDir(), "isolated-backup-provider.json");
    }
    @Override public boolean onCreate() { return true; }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode.contains("w") && writesToFail > 0) {
            writesToFail--;
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write("partial".getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            throw new FileNotFoundException("Injected document write failure");
        }
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("reset")) {
            try (FileOutputStream ignored = new FileOutputStream(file())) {
                writesToFail = 0;
            } catch (IOException error) { throw new IllegalStateException(error); }
        } else if (method.equals("failNextWrite")) writesToFail = 1;
        else if (method.equals("failBothWrites")) writesToFail = 2;
        else throw new IllegalArgumentException("Unknown fixture operation");
        return Bundle.EMPTY;
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return file().delete() ? 1 : 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
