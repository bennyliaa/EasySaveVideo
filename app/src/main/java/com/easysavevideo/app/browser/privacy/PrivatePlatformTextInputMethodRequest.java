package com.easysavevideo.app.browser.privacy;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.PlatformTextInputMethodRequest;
import java.util.Objects;

/**
 * Original nullable factory bridge for the admitted Compose 1.8.3 request interface.
 *
 * <p>The Kotlin interface declares a nonnull return. Its pinned InputMethodSession bytecode passes
 * the result to NullableInputConnectionWrapper, whose constructor accepts/stores null without a
 * null check or dereference. Therefore a denied factory never exposes a live delegate, but Compose
 * still returns its own inert outer wrapper to Android. This bridge does NOT establish a true-null
 * outer Android boundary or platform acceptance. No upstream implementation is copied or changed.
 *
 * <p>The factory must capture an exact session token, call the token-bound
 * createPrivateInputConnection helper, and sanitize EditorInfo on denial. The owner must cancel and
 * restart its text-input session when mode/root/generation changes. Do not reacquire a newer token
 * from within a stale factory callback.
 */
public final class PrivatePlatformTextInputMethodRequest implements PlatformTextInputMethodRequest {
    @FunctionalInterface
    public interface Factory {
        @Nullable InputConnection create(@NonNull EditorInfo editorInfo);
    }

    private final Factory factory;

    public PrivatePlatformTextInputMethodRequest(@NonNull Factory factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    // Java permits the nullable factory result. Do not add a Kotlin nonnull cast or fake delegate.
    @Override
    public InputConnection createInputConnection(@NonNull EditorInfo editorInfo) {
        return factory.create(editorInfo);
    }
}
