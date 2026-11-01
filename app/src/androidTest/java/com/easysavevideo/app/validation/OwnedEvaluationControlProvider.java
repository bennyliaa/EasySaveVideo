package com.easysavevideo.app.validation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.Collections;

/** UID-checked fixture IPC only; no path, arbitrary URI, query or general deletion API. */
public final class OwnedEvaluationControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = OwnedEvaluationFixtures.requireCaller(getContext());
        switch (method) {
            case EvaluationFixtureContract.BEGIN:
                require(arg == null && extras == null);
                return OwnedEvaluationFixtures.begin(getContext(), caller);
            case EvaluationFixtureContract.SNAPSHOT:
                require(arg != null && extras != null && extras.keySet().equals(Collections.singleton("case")));
                return OwnedEvaluationFixtures.snapshot(caller, arg, extras.getString("case"));
            case EvaluationFixtureContract.CLEANUP:
                require(arg != null && extras == null);
                return OwnedEvaluationFixtures.cleanup(getContext(), caller, arg);
            default: throw new SecurityException("OWNED_CONTROL_METHOD");
        }
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("OWNED_CONTROL_ARGUMENT"); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new SecurityException("OWNED_CONTROL_QUERY"); }
    @Override public String getType(Uri uri) { throw new SecurityException("OWNED_CONTROL_TYPE"); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("OWNED_CONTROL_INSERT"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new SecurityException("OWNED_CONTROL_UPDATE"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("OWNED_CONTROL_DELETE"); }
}
