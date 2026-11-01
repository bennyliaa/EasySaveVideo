package com.easysavevideo.app.validation;

import android.content.Intent;
import android.net.Uri;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Android/JDK only: the standalone test-provider process has no target-app classloader. */
public final class EvaluationFixtureContract {
    private EvaluationFixtureContract() {}
    public static final String TARGET_PACKAGE = "com.easysavevideo.app";
    public static final String DOCUMENT_AUTHORITY = "com.easysavevideo.app.test.evaluation.documents";
    public static final String CONTROL_AUTHORITY = "com.easysavevideo.app.test.evaluation.control";
    public static final String BEGIN = "owned_begin";
    public static final String SNAPSHOT = "owned_snapshot";
    public static final String CLEANUP = "owned_cleanup";
    public static final int READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    public static final int MAX_FIXTURE_BYTES = 1_048_576;
    public static final List<String> CASE_IDS = Collections.unmodifiableList(Arrays.asList(
        "empty", "nonempty", "open_failure", "pipe", "read_only", "no_grant", "read_grant", "write_grant"));
    public static final byte[] SENTINEL = "OWNED_EXISTING_BYTES_MUST_SURVIVE\n".getBytes(StandardCharsets.UTF_8);
    public static final Uri CONTROL_URI = Uri.parse("content://" + CONTROL_AUTHORITY);
}
