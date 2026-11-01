package com.easysavevideo.app.validation;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Only provider-process state. The target instrumentation communicates exclusively through IPC. */
final class OwnedEvaluationFixtures {
    private OwnedEvaluationFixtures() {}
    private static final class Fixture {
        final String id;
        final File file;
        final Uri uri;
        final int grantFlags;
        int openCalls, opened, closed, closeErrors, pipeBytes;
        boolean onlyReadWrite = true;
        final List<ParcelFileDescriptor> observers = new ArrayList<>();
        Fixture(String id, File file, Uri uri, int flags) { this.id = id; this.file = file; this.uri = uri; grantFlags = flags; }
    }
    private static final class Run {
        final String token;
        final int ownerUid;
        final File root;
        final Map<String, Fixture> fixtures = new LinkedHashMap<>();
        Run(String token, int uid, File root) { this.token = token; ownerUid = uid; this.root = root; }
    }
    private static Run active;

    static int requireCaller(Context context) {
        int uid = Binder.getCallingUid();
        try {
            int target = context.getPackageManager().getPackageUid(EvaluationFixtureContract.TARGET_PACKAGE, 0);
            require(uid == target || uid == Process.myUid());
            require(context.getPackageManager().checkSignatures(EvaluationFixtureContract.TARGET_PACKAGE, context.getPackageName()) == PackageManager.SIGNATURE_MATCH);
        } catch (PackageManager.NameNotFoundException failure) { throw new SecurityException("OWNED_TARGET_ABSENT"); }
        return uid;
    }
    private static void require(boolean value) { if (!value) throw new SecurityException("OWNED_FIXTURE_REFUSED"); }

    static synchronized Bundle begin(Context context, int ownerUid) {
        require(active == null);
        String token = UUID.randomUUID().toString();
        File root = new File(context.getCacheDir(), "esv-owned-evaluation-" + token);
        require(root.mkdir());
        Run run = new Run(token, ownerUid, root);
        active = run;
        Bundle result = new Bundle();
        result.putString("token", token);
        try {
            for (String id : EvaluationFixtureContract.CASE_IDS) {
                File file = new File(root, id + ".json");
                require(file.createNewFile());
                int flags = "no_grant".equals(id) ? 0 : "read_grant".equals(id) ? Intent.FLAG_GRANT_READ_URI_PERMISSION :
                    "write_grant".equals(id) ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : EvaluationFixtureContract.READ_WRITE;
                Uri uri = DocumentsContract.buildDocumentUri(EvaluationFixtureContract.DOCUMENT_AUTHORITY, token + ":" + id);
                Fixture fixture = new Fixture(id, file, uri, flags);
                run.fixtures.put(id, fixture);
                result.putString("uri_" + id, uri.toString());
                if ("nonempty".equals(id)) try (FileOutputStream out = new FileOutputStream(file)) { out.write(EvaluationFixtureContract.SENTINEL); }
                if (flags != 0) {
                    long identity = Binder.clearCallingIdentity();
                    try { context.grantUriPermission(EvaluationFixtureContract.TARGET_PACKAGE, uri, flags); }
                    finally { Binder.restoreCallingIdentity(identity); }
                }
            }
            result.putBoolean("setup_complete", true);
        } catch (Exception failure) {
            // Return ownership even for partial setup. The runner's finally block must report cleanup.
            result.putBoolean("setup_complete", false);
            result.putString("setup_error_class", failure.getClass().getSimpleName());
        }
        return result;
    }

    private static Run owned(int uid, String token) {
        require(active != null && active.ownerUid == uid && active.token.equals(token));
        return active;
    }
    private static Fixture fixture(Context context, String documentId) {
        int caller = requireCaller(context);
        require(active != null && active.ownerUid == caller);
        for (Fixture fixture : active.fixtures.values()) if (DocumentsContract.getDocumentId(fixture.uri).equals(documentId)) return fixture;
        throw new SecurityException("OWNED_DOCUMENT_ABSENT");
    }
    static synchronized File document(Context context, String id) { return fixture(context, id).file; }

    static synchronized ParcelFileDescriptor open(Context context, String documentId, String mode) throws FileNotFoundException {
        Fixture fixture = fixture(context, documentId);
        fixture.openCalls++;
        fixture.onlyReadWrite &= "rw".equals(mode);
        require("rw".equals(mode)); // A truncating production mode must fail this test.
        if ("open_failure".equals(fixture.id)) throw new FileNotFoundException("OWNED_OPEN_FAILURE");
        try {
            if ("pipe".equals(fixture.id)) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                fixture.opened++;
                Thread reader = new Thread(() -> {
                    boolean eof = false;
                    boolean failed = false;
                    try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                        byte[] bytes = new byte[128];
                        while (true) {
                            int count = input.read(bytes);
                            if (count < 0) { pipe[0].checkError(); eof = true; break; }
                            synchronized (OwnedEvaluationFixtures.class) {
                                fixture.pipeBytes += count;
                                require(fixture.pipeBytes <= EvaluationFixtureContract.MAX_FIXTURE_BYTES);
                            }
                        }
                    } catch (Exception failure) { failed = true; }
                    synchronized (OwnedEvaluationFixtures.class) {
                        if (eof) fixture.closed++;
                        if (failed) fixture.closeErrors++;
                    }
                }, "owned-evaluation-pipe");
                reader.setDaemon(true);
                reader.start();
                return pipe[1];
            }
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(fixture.file,
                "read_only".equals(fixture.id) ? ParcelFileDescriptor.MODE_READ_ONLY : ParcelFileDescriptor.MODE_READ_WRITE,
                new Handler(Looper.getMainLooper()), error -> {
                    synchronized (OwnedEvaluationFixtures.class) {
                        fixture.closed++;
                        if (error != null) fixture.closeErrors++;
                    }
                });
            fixture.opened++;
            try { fixture.observers.add(ParcelFileDescriptor.dup(descriptor.getFileDescriptor())); }
            catch (IOException failure) { descriptor.close(); throw failure; }
            return descriptor;
        } catch (IOException failure) { throw new FileNotFoundException("OWNED_DESCRIPTOR_FAILURE"); }
    }

    static synchronized Bundle snapshot(int ownerUid, String token, String caseId) {
        require(EvaluationFixtureContract.CASE_IDS.contains(caseId));
        Fixture fixture = owned(ownerUid, token).fixtures.get(caseId);
        require(fixture != null && fixture.file.length() <= EvaluationFixtureContract.MAX_FIXTURE_BYTES);
        Bundle result = new Bundle();
        try (FileInputStream input = new FileInputStream(fixture.file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                require(output.size() + count <= EvaluationFixtureContract.MAX_FIXTURE_BYTES);
                output.write(buffer, 0, count);
            }
            result.putByteArray("bytes", output.toByteArray());
            boolean append = false;
            for (ParcelFileDescriptor observer : fixture.observers) append |=
                (Os.fcntlInt(observer.getFileDescriptor(), OsConstants.F_GETFL, 0) & OsConstants.O_APPEND) != 0;
            result.putBoolean("append_seen", append);
        } catch (Exception failure) { throw new IllegalStateException("OWNED_SNAPSHOT_FAILED"); }
        result.putInt("open_calls", fixture.openCalls); result.putInt("opened", fixture.opened); result.putInt("closed", fixture.closed);
        result.putInt("close_errors", fixture.closeErrors); result.putInt("pipe_bytes", fixture.pipeBytes);
        result.putBoolean("only_rw", fixture.onlyReadWrite);
        return result;
    }

    static synchronized Bundle cleanup(Context context, int ownerUid, String token) {
        Run run = owned(ownerUid, token);
        boolean closed = true;
        for (Fixture fixture : run.fixtures.values()) closed &= fixture.opened == fixture.closed && fixture.closeErrors == 0;
        boolean grantsRevoked = true;
        for (Fixture fixture : run.fixtures.values()) if (fixture.grantFlags != 0) {
            long identity = Binder.clearCallingIdentity();
            try { context.revokeUriPermission(EvaluationFixtureContract.TARGET_PACKAGE, fixture.uri, fixture.grantFlags); }
            catch (Exception failure) { grantsRevoked = false; }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        boolean removed = closed;
        if (closed) {
            for (Fixture fixture : run.fixtures.values()) {
                for (ParcelFileDescriptor observer : fixture.observers) try { observer.close(); } catch (Exception failure) { removed = false; }
                require(fixture.file.getParentFile().equals(run.root) && fixture.file.getName().equals(fixture.id + ".json"));
                if (!fixture.file.delete()) removed = false;
            }
            // Only this newly created, now-empty directory; never descend into other files.
            if (!run.root.delete()) removed = false;
        }
        boolean complete = closed && grantsRevoked && removed;
        if (complete) active = null;
        Bundle result = new Bundle();
        result.putBoolean("complete", complete); result.putBoolean("descriptors_closed", closed);
        result.putBoolean("grants_revoked", grantsRevoked); result.putBoolean("exact_files_removed", removed);
        return result;
    }
}
