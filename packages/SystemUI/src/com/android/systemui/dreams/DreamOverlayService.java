/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.dreams;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;

import com.android.internal.policy.PhoneWindow;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.touch.DreamOverlayTouchMonitor;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The {@link DreamOverlayService} is responsible for placing an overlay on top of a dream. The
 * dream reaches directly out to the service with a Window reference (via LayoutParams), which the
 * service uses to insert its own child Window into the dream's parent Window.
 */
public class DreamOverlayService extends android.service.dreams.DreamOverlayService {
    private static final String TAG = "DreamOverlayService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // The Context is used to construct the hosting constraint layout and child overlay views.
    private final Context mContext;
    // The Executor ensures actions and ui updates happen on the same thread.
    private final Executor mExecutor;
    // A controller for the dream overlay container view (which contains both the status bar and the
    // content area).
    private final DreamOverlayContainerViewController mDreamOverlayContainerViewController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    // A reference to the {@link Window} used to hold the dream overlay.
    private Window mWindow;

    private final Complication.Host mHost = new Complication.Host() {
        @Override
        public void requestExitDream() {
            mExecutor.execute(DreamOverlayService.this::requestExit);
        }
    };

    private final LifecycleRegistry mLifecycleRegistry;

    private ViewModelStore mViewModelStore = new ViewModelStore();

    private DreamOverlayTouchMonitor mDreamOverlayTouchMonitor;

    private final KeyguardUpdateMonitorCallback mKeyguardCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onShadeExpandedChanged(boolean expanded) {
                    if (mLifecycleRegistry.getCurrentState() != Lifecycle.State.RESUMED
                            && mLifecycleRegistry.getCurrentState() != Lifecycle.State.STARTED) {
                        return;
                    }

                    mLifecycleRegistry.setCurrentState(
                            expanded ? Lifecycle.State.STARTED : Lifecycle.State.RESUMED);
                }
            };

    @Inject
    public DreamOverlayService(
            Context context,
            @Main Executor executor,
            DreamOverlayComponent.Factory dreamOverlayComponentFactory,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        mContext = context;
        mExecutor = executor;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);

        final DreamOverlayComponent component =
                dreamOverlayComponentFactory.create(mViewModelStore, mHost);
        mDreamOverlayContainerViewController = component.getDreamOverlayContainerViewController();
        setCurrentState(Lifecycle.State.CREATED);
        mLifecycleRegistry = component.getLifecycleRegistry();
        mDreamOverlayTouchMonitor = component.getDreamOverlayTouchMonitor();
        mDreamOverlayTouchMonitor.init();
    }

    private void setCurrentState(Lifecycle.State state) {
        mExecutor.execute(() -> mLifecycleRegistry.setCurrentState(state));
    }

    @Override
    public void onDestroy() {
        mKeyguardUpdateMonitor.registerCallback(mKeyguardCallback);
        setCurrentState(Lifecycle.State.DESTROYED);
        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.removeView(mWindow.getDecorView());
        super.onDestroy();
    }

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
        setCurrentState(Lifecycle.State.STARTED);
        mExecutor.execute(() -> {
            addOverlayWindowLocked(layoutParams);
            setCurrentState(Lifecycle.State.RESUMED);
        });
    }

    /**
     * Inserts {@link Window} to host the dream overlay into the dream's parent window. Must be
     * called from the main executing thread. The window attributes closely mirror those that are
     * set by the {@link android.service.dreams.DreamService} on the dream Window.
     * @param layoutParams The {@link android.view.WindowManager.LayoutParams} which allow inserting
     *                     into the dream window.
     */
    private void addOverlayWindowLocked(WindowManager.LayoutParams layoutParams) {
        mWindow = new PhoneWindow(mContext);
        mWindow.setAttributes(layoutParams);
        mWindow.setWindowManager(null, layoutParams.token, "DreamOverlay", true);

        mWindow.setBackgroundDrawable(new ColorDrawable(0));

        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        // Hide all insets when the dream is showing
        mWindow.getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
        mWindow.setDecorFitsSystemWindows(false);

        if (DEBUG) {
            Log.d(TAG, "adding overlay window to dream");
        }

        mDreamOverlayContainerViewController.init();
        mWindow.setContentView(mDreamOverlayContainerViewController.getContainerView());

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(mWindow.getDecorView(), mWindow.getAttributes());
    }
}
