package com.leyu.melora.playback.local;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已注册的测试 SAF 目录夹具，也支持 ContentResolver.wrap 的进程内独立实例。
 * 只依赖 Android/Java，允许测试 APK 独立进程加载；不访问真实媒体文件。
 */
public final class LoadingDocumentsProvider extends DocumentsProvider {
    public static final class Document {
        final String id;
        final String name;
        final boolean directory;

        public Document(String id, String name) { this(id, name, false); }
        public Document(String id, String name, boolean directory) {
            this.id = id;
            this.name = name;
            this.directory = directory;
        }
    }

    private static final class Listing {
        final List<Document> children;
        final Boolean loading;

        Listing(List<Document> children, Boolean loading) {
            this.children = new ArrayList<>(children);
            this.loading = loading;
        }
    }

    private final Map<String, Listing> listings = new LinkedHashMap<>();
    public final List<String> childQueries = new ArrayList<>();
    public final List<MatrixCursor> cursors = new ArrayList<>();

    public void setChildren(String id, List<Document> children, Boolean loading) {
        listings.put(id, new Listing(children, loading));
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection != null ? projection :
            new String[] { DocumentsContract.Root.COLUMN_ROOT_ID });
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        Document document = null;
        for (Listing listing : listings.values()) {
            for (Document child : listing.children) {
                if (child.id.equals(documentId)) { document = child; break; }
            }
            if (document != null) break;
        }
        if (document == null && listings.containsKey(documentId)) {
            document = new Document(documentId, documentId, true);
        }
        if (document == null) throw new FileNotFoundException(documentId);
        MatrixCursor result = cursor(projection);
        addDocument(result, document);
        return result;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        Listing listing = listings.get(parentDocumentId);
        if (listing == null) throw new FileNotFoundException(parentDocumentId);
        childQueries.add(parentDocumentId);
        MatrixCursor result = cursor(projection);
        for (Document document : listing.children) addDocument(result, document);
        if (listing.loading != null) {
            Bundle extras = new Bundle();
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, listing.loading);
            result.setExtras(extras);
        }
        return result;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        throw new FileNotFoundException("夹具没有媒体内容：" + documentId);
    }

    private MatrixCursor cursor(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS });
        cursors.add(result);
        return result;
    }

    private void addDocument(MatrixCursor cursor, Document document) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: value = document.id; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: value = document.name; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    value = document.directory ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/mpeg"; break;
                case DocumentsContract.Document.COLUMN_SIZE: value = document.directory ? 0L : 2_000_000L; break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: value = 2_000L; break;
                case DocumentsContract.Document.COLUMN_FLAGS: value = 0; break;
            }
            row.add(column, value);
        }
    }
}
