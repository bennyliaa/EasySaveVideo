package com.easysavevideo.app.validation;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;

/** Test APK only. Retains MANAGE_DOCUMENTS and uses exact temporary grants. */
public final class OwnedEvaluationDocumentsProvider extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        // No user-picker roots. Documents exist only when the owned runner creates a bounded run.
        return new MatrixCursor(projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS });
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        File fixture = OwnedEvaluationFixtures.document(getContext(), documentId);
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: values[i] = documentId; break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: values[i] = "owned-evaluation.json"; break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: values[i] = "application/json"; break;
                case DocumentsContract.Document.COLUMN_FLAGS: values[i] = DocumentsContract.Document.FLAG_SUPPORTS_WRITE; break;
                case DocumentsContract.Document.COLUMN_SIZE: values[i] = fixture.length(); break;
                default: values[i] = null;
            }
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) throws FileNotFoundException {
        throw new FileNotFoundException("OWNED_NO_DIRECTORIES");
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        return OwnedEvaluationFixtures.open(getContext(), documentId, mode);
    }
}
