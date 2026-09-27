package app.seanime.tv;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A writable, in-memory document provider used only by debug SAF instrumentation tests. */
public final class TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.seanime.tv.test.documents";
    private static final String ROOT_ID = "root";
    private static final Object LOCK = new Object();
    private static final String[] ROOT_COLUMNS = {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
    };
    private static final String[] DOCUMENT_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
    };
    private static volatile boolean renameUnavailable;
    private static volatile String failWriteName;
    private static volatile String denyDeleteName;
    private static MemoryDocument root = new MemoryDocument(ROOT_ID, null, true);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? ROOT_COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Root.COLUMN_ROOT_ID:
                case Root.COLUMN_DOCUMENT_ID:
                    row[i] = ROOT_ID;
                    break;
                case Root.COLUMN_TITLE:
                    row[i] = "Test USB";
                    break;
                case Root.COLUMN_FLAGS:
                    row[i] = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD;
                    break;
                case Root.COLUMN_MIME_TYPES:
                    row[i] = "*/*";
                    break;
                case Root.COLUMN_AVAILABLE_BYTES:
                    row[i] = Long.MAX_VALUE;
                    break;
                default:
                    row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MemoryDocument document = findDocument(documentId);
        awaitWrites(document);
        return cursor(java.util.Collections.singletonList(document), projection);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MemoryDocument parent = findDocument(parentDocumentId);
        if (!parent.directory) throw new FileNotFoundException("Parent is not a directory");
        List<MemoryDocument> children;
        synchronized (LOCK) {
            children = new ArrayList<>(parent.children.values());
        }
        return cursor(children, projection);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (parentDocumentId == null || documentId == null ||
                !documentId.startsWith(parentDocumentId + "/")) return false;
        try {
            findDocument(documentId);
            return true;
        } catch (FileNotFoundException ignored) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        MemoryDocument document = findDocument(documentId);
        if (document.directory) throw new FileNotFoundException("Cannot open a directory");
        if ("r".equals(mode)) return openForRead(document);
        if ("w".equals(mode) || "wt".equals(mode) || "wa".equals(mode) || "rw".equals(mode) || "rwt".equals(mode)) {
            if (document.name.equals(failWriteName)) throw new FileNotFoundException("Injected write failure");
            return openForWrite(document, mode);
        }
        throw new IllegalArgumentException("Unsupported document mode: " + mode);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        validateName(displayName);
        MemoryDocument parent = findDocument(parentDocumentId);
        if (!parent.directory) throw new FileNotFoundException("Parent is not a directory");
        synchronized (LOCK) {
            if (parent.children.containsKey(displayName)) throw new IllegalStateException("Document already exists");
            MemoryDocument child = new MemoryDocument(displayName, parent, Document.MIME_TYPE_DIR.equals(mimeType));
            parent.children.put(displayName, child);
            return documentId(child);
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (renameUnavailable) throw new FileNotFoundException("Injected rename failure");
        validateName(displayName);
        MemoryDocument document = findDocument(documentId);
        awaitWrites(document);
        MemoryDocument parent = document.parent;
        if (parent == null) throw new IllegalStateException("Cannot rename the root document");
        synchronized (LOCK) {
            if (parent.children.containsKey(displayName)) throw new IllegalStateException("Document already exists");
            parent.children.remove(document.name);
            document.name = displayName;
            document.lastModified = System.currentTimeMillis();
            parent.children.put(displayName, document);
            return documentId(document);
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        MemoryDocument document = findDocument(documentId);
        if (document.name.equals(denyDeleteName)) throw new IllegalStateException("Injected delete failure");
        MemoryDocument parent = document.parent;
        if (parent == null) throw new IllegalStateException("Cannot delete the root document");
        awaitWrites(document);
        synchronized (LOCK) {
            if (parent.children.remove(document.name) == null) throw new FileNotFoundException("Document does not exist");
        }
    }

    private Cursor cursor(List<MemoryDocument> documents, String[] projection) {
        String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        for (MemoryDocument document : documents) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case Document.COLUMN_DOCUMENT_ID:
                        row[i] = documentId(document);
                        break;
                    case Document.COLUMN_DISPLAY_NAME:
                        row[i] = document.parent == null ? "Test USB" : document.name;
                        break;
                    case Document.COLUMN_MIME_TYPE:
                        row[i] = document.directory ? Document.MIME_TYPE_DIR : "application/octet-stream";
                        break;
                    case Document.COLUMN_SIZE:
                        synchronized (LOCK) {
                            row[i] = (long) document.contents.length;
                        }
                        break;
                    case Document.COLUMN_LAST_MODIFIED:
                        row[i] = document.lastModified;
                        break;
                    case Document.COLUMN_FLAGS:
                        row[i] = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE |
                                Document.FLAG_SUPPORTS_RENAME |
                                (document.directory ? Document.FLAG_DIR_SUPPORTS_CREATE : 0);
                        break;
                    default:
                        throw new IllegalArgumentException("Unexpected document column: " + columns[i]);
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    private ParcelFileDescriptor openForRead(MemoryDocument document) {
        awaitWrites(document);
        byte[] contents;
        synchronized (LOCK) {
            contents = document.contents.clone();
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output =
                             new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(contents);
                } catch (IOException ignored) {
                }
            }, "test-documents-read").start();
            return pipe[0];
        } catch (IOException error) {
            throw new IllegalStateException("Could not open document for reading", error);
        }
    }

    private ParcelFileDescriptor openForWrite(MemoryDocument document, String mode) {
        boolean truncate = "w".equals(mode) || "wt".equals(mode) || "rwt".equals(mode);
        boolean append = "wa".equals(mode);
        synchronized (LOCK) {
            if (truncate) document.contents = new byte[0];
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            CountDownLatch completion = new CountDownLatch(1);
            synchronized (LOCK) {
                document.pendingWrites.add(completion);
            }
            new Thread(() -> {
                byte[] contents = new byte[0];
                try (ParcelFileDescriptor.AutoCloseInputStream input =
                             new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                    contents = readAll(input);
                } catch (IOException ignored) {
                } finally {
                    synchronized (LOCK) {
                        if (append) {
                            byte[] appended = new byte[document.contents.length + contents.length];
                            System.arraycopy(document.contents, 0, appended, 0, document.contents.length);
                            System.arraycopy(contents, 0, appended, document.contents.length, contents.length);
                            document.contents = appended;
                        } else {
                            document.contents = contents;
                        }
                        document.lastModified = System.currentTimeMillis();
                        document.pendingWrites.remove(completion);
                    }
                    completion.countDown();
                }
            }, "test-documents-write").start();
            return pipe[1];
        } catch (IOException error) {
            throw new IllegalStateException("Could not open document for writing", error);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private void awaitWrites(MemoryDocument document) {
        List<CountDownLatch> pending;
        synchronized (LOCK) {
            pending = new ArrayList<>(document.pendingWrites);
        }
        for (CountDownLatch completion : pending) {
            try {
                if (!completion.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for document write");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for document write", error);
            }
        }
    }

    private MemoryDocument findDocument(String id) throws FileNotFoundException {
        synchronized (LOCK) {
            if (ROOT_ID.equals(id)) return root;
            if (id == null || !id.startsWith(ROOT_ID + "/")) throw new FileNotFoundException("Invalid document ID");
            MemoryDocument current = root;
            String relativeId = id.substring(ROOT_ID.length() + 1);
            for (String segment : relativeId.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new FileNotFoundException("Invalid document ID");
                }
                current = current.children.get(segment);
                if (current == null) throw new FileNotFoundException("Document does not exist: " + segment);
            }
            return current;
        }
    }

    private String documentId(MemoryDocument document) {
        if (document.parent == null) return ROOT_ID;
        List<String> segments = new ArrayList<>();
        MemoryDocument current = document;
        while (current.parent != null) {
            segments.add(0, current.name);
            current = current.parent;
        }
        StringBuilder id = new StringBuilder(ROOT_ID);
        for (String segment : segments) id.append('/').append(segment);
        return id.toString();
    }

    private void validateName(String name) {
        if (name == null || name.trim().isEmpty() || name.contains("/") || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("Invalid document name");
        }
    }

    private static final class MemoryDocument {
        String name;
        final MemoryDocument parent;
        final boolean directory;
        byte[] contents = new byte[0];
        long lastModified = System.currentTimeMillis();
        final Map<String, MemoryDocument> children = new LinkedHashMap<>();
        final List<CountDownLatch> pendingWrites = new ArrayList<>();

        MemoryDocument(String name, MemoryDocument parent, boolean directory) {
            this.name = name;
            this.parent = parent;
            this.directory = directory;
        }
    }

    public static void reset(Context context) {
        synchronized (LOCK) {
            root = new MemoryDocument(ROOT_ID, null, true);
        }
        renameUnavailable = false;
        failWriteName = null;
        denyDeleteName = null;
    }

    public static void setRenameUnavailable(boolean unavailable) {
        renameUnavailable = unavailable;
    }

    public static void setWriteFailureName(String name) {
        failWriteName = name;
    }

    public static void setDeleteDeniedName(String name) {
        denyDeleteName = name;
    }

    public static void grantTree(Context context, Uri treeUri, int flags) {
        context.grantUriPermission(context.getPackageName(), treeUri, flags);
    }
}
