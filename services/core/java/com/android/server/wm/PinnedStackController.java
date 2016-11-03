/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.Slog;
import android.util.TypedValue;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;

import com.android.internal.os.BackgroundThread;
import com.android.internal.policy.PipMotionHelper;
import com.android.internal.policy.PipSnapAlgorithm;

import java.io.PrintWriter;

/**
 * Holds the common state of the pinned stack between the system and SystemUI.
 */
class PinnedStackController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "PinnedStackController" : TAG_WM;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final Handler mHandler = new Handler();

    private IPinnedStackListener mPinnedStackListener;
    private final PinnedStackListenerDeathHandler mPinnedStackListenerDeathHandler =
            new PinnedStackListenerDeathHandler();

    private final PinnedStackControllerCallback mCallbacks = new PinnedStackControllerCallback();
    private final PipSnapAlgorithm mSnapAlgorithm;
    private final PipMotionHelper mMotionHelper;

    // States that affect how the PIP can be manipulated
    private boolean mInInteractiveMode;
    private boolean mIsImeShowing;
    private int mImeHeight;
    private ValueAnimator mBoundsAnimator = null;

    // Used to calculate stack bounds across rotations
    private final DisplayInfo mDisplayInfo = new DisplayInfo();

    // The size and position information that describes where the pinned stack will go by default.
    private int mDefaultStackGravity;
    private Size mDefaultStackSize;
    private Point mScreenEdgeInsets;

    // Temp vars for calculation
    private final DisplayMetrics mTmpMetrics = new DisplayMetrics();
    private final Rect mTmpInsets = new Rect();
    private final Rect mTmpRect = new Rect();

    /**
     * The callback object passed to listeners for them to notify the controller of state changes.
     */
    private class PinnedStackControllerCallback extends IPinnedStackController.Stub {

        @Override
        public void setInInteractiveMode(final boolean inInteractiveMode) {
            mHandler.post(() -> {
                // Cancel any existing animations on the PIP once the user starts dragging it
                if (mBoundsAnimator != null && inInteractiveMode) {
                    mBoundsAnimator.cancel();
                }
                mInInteractiveMode = inInteractiveMode;
            });
        }
    }

    /**
     * Handler for the case where the listener dies.
     */
    private class PinnedStackListenerDeathHandler implements IBinder.DeathRecipient {

        @Override
        public void binderDied() {
            // Clean up the state if the listener dies
            mInInteractiveMode = false;
            mPinnedStackListener = null;
        }
    }

    PinnedStackController(WindowManagerService service, DisplayContent displayContent) {
        mService = service;
        mDisplayContent = displayContent;
        mSnapAlgorithm = new PipSnapAlgorithm(service.mContext);
        mMotionHelper = new PipMotionHelper(BackgroundThread.getHandler());
        mDisplayInfo.copyFrom(mDisplayContent.getDisplayInfo());
        reloadResources();
    }

    void onConfigurationChanged() {
        reloadResources();
    }

    /**
     * Reloads all the resources for the current configuration.
     */
    void reloadResources() {
        final Resources res = mService.mContext.getResources();
        final Size defaultSizeDp = Size.parseSize(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureSize));
        final Size screenEdgeInsetsDp = Size.parseSize(res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets));
        mDefaultStackGravity = res.getInteger(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity);
        mDisplayContent.getDisplay().getRealMetrics(mTmpMetrics);
        mDefaultStackSize = new Size(dpToPx(defaultSizeDp.getWidth(), mTmpMetrics),
                dpToPx(defaultSizeDp.getHeight(), mTmpMetrics));
        mScreenEdgeInsets = new Point(dpToPx(screenEdgeInsetsDp.getWidth(), mTmpMetrics),
                dpToPx(screenEdgeInsetsDp.getHeight(), mTmpMetrics));
    }

    /**
     * Registers a pinned stack listener.
     */
    void registerPinnedStackListener(IPinnedStackListener listener) {
        try {
            listener.asBinder().linkToDeath(mPinnedStackListenerDeathHandler, 0);
            listener.onListenerRegistered(mCallbacks);
            mPinnedStackListener = listener;
            notifyBoundsChanged(mIsImeShowing);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register pinned stack listener", e);
        }
    }

    /**
     * @return the default bounds to show the PIP when there is no active PIP.
     */
    Rect getDefaultBounds() {
        final Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        final Rect defaultBounds = new Rect();
        Gravity.apply(mDefaultStackGravity, mDefaultStackSize.getWidth(),
                mDefaultStackSize.getHeight(), insetBounds, 0, 0, defaultBounds);
        return defaultBounds;
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    Rect getMovementBounds(Rect stackBounds) {
        return getMovementBounds(stackBounds, true /* adjustForIme */);
    }

    /**
     * @return the movement bounds for the given {@param stackBounds} and the current state of the
     *         controller.
     */
    Rect getMovementBounds(Rect stackBounds, boolean adjustForIme) {
        final Rect movementBounds = new Rect();
        getInsetBounds(movementBounds);

        // Adjust the right/bottom to ensure the stack bounds never goes offscreen
        movementBounds.right = Math.max(movementBounds.left, movementBounds.right -
                stackBounds.width());
        movementBounds.bottom = Math.max(movementBounds.top, movementBounds.bottom -
                stackBounds.height());

        // Apply the movement bounds adjustments based on the current state
        if (adjustForIme) {
            if (mIsImeShowing) {
                movementBounds.bottom -= mImeHeight;
            }
        }
        return movementBounds;
    }

    /**
     * @return the repositioned PIP bounds given it's pre-change bounds, and the new display info.
     */
    Rect onDisplayChanged(Rect preChangeStackBounds, DisplayInfo displayInfo) {
        final Rect postChangeStackBounds = new Rect(preChangeStackBounds);
        if (!mDisplayInfo.equals(displayInfo)) {
            // Calculate the snap fraction of the current stack along the old movement bounds, and
            // then update the stack bounds to the same fraction along the rotated movement bounds.
            final Rect preChangeMovementBounds = getMovementBounds(preChangeStackBounds);
            final float snapFraction = mSnapAlgorithm.getSnapFraction(preChangeStackBounds,
                    preChangeMovementBounds);
            mDisplayInfo.copyFrom(displayInfo);

            final Rect postChangeMovementBounds = getMovementBounds(preChangeStackBounds,
                    false /* adjustForIme */);
            mSnapAlgorithm.applySnapFraction(postChangeStackBounds, postChangeMovementBounds,
                    snapFraction);
        }
        return postChangeStackBounds;
    }

    /**
     * Sets the Ime state and height.
     */
    void setAdjustedForIme(boolean adjustedForIme, int imeHeight) {
        // Return early if there is no state change
        if (mIsImeShowing == adjustedForIme && mImeHeight == imeHeight) {
            return;
        }

        final Rect stackBounds = new Rect();
        mService.getStackBounds(PINNED_STACK_ID, stackBounds);
        final Rect prevMovementBounds = getMovementBounds(stackBounds);
        mIsImeShowing = adjustedForIme;
        mImeHeight = imeHeight;
        if (mInInteractiveMode) {
            // If the user is currently interacting with the PIP and the ime state changes, then
            // don't adjust the bounds and defer that to after the interaction
            notifyBoundsChanged(adjustedForIme /* adjustedForIme */);
        } else {
            // Otherwise, we can move the PIP to a sane location to ensure that it does not block
            // the user from interacting with the IME
            final Rect movementBounds = getMovementBounds(stackBounds);
            final Rect toBounds = new Rect(stackBounds);
            if (adjustedForIme) {
                // IME visible
                toBounds.offset(0, Math.min(0, movementBounds.bottom - stackBounds.top));
            } else {
                // IME hidden
                if (stackBounds.top == prevMovementBounds.bottom) {
                    // If the PIP is resting on top of the IME, then adjust it with the hiding IME
                    toBounds.offsetTo(toBounds.left, movementBounds.bottom);
                }
            }
            if (!toBounds.equals(stackBounds)) {
                if (mBoundsAnimator != null) {
                    mBoundsAnimator.cancel();
                }
                mBoundsAnimator = mMotionHelper.createAnimationToBounds(stackBounds, toBounds);
                mBoundsAnimator.start();
            }
        }
    }

    /**
     * Sends a broadcast that the PIP movement bounds have changed.
     */
    private void notifyBoundsChanged(boolean adjustedForIme) {
        if (mPinnedStackListener != null) {
            try {
                mPinnedStackListener.onBoundsChanged(adjustedForIme);
            } catch (RemoteException e) {
                Slog.e(TAG_WM, "Error delivering bounds changed event.", e);
            }
        }
    }

    /**
     * @return the bounds on the screen that the PIP can be visible in.
     */
    private void getInsetBounds(Rect outRect) {
        mService.mPolicy.getStableInsetsLw(mDisplayInfo.rotation, mDisplayInfo.logicalWidth,
                mDisplayInfo.logicalHeight, mTmpInsets);
        outRect.set(mTmpInsets.left + mScreenEdgeInsets.x, mTmpInsets.top + mScreenEdgeInsets.y,
                mDisplayInfo.logicalWidth - mTmpInsets.right - mScreenEdgeInsets.x,
                mDisplayInfo.logicalHeight - mTmpInsets.bottom - mScreenEdgeInsets.y);
    }

    /**
     * @return the pixels for a given dp value.
     */
    private int dpToPx(float dpValue, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, dpValue, dm);
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PinnedStackController");
        pw.print(prefix + "  defaultBounds="); getDefaultBounds().printShortString(pw);
        pw.println();
        mService.getStackBounds(PINNED_STACK_ID, mTmpRect);
        pw.print(prefix + "  movementBounds="); getMovementBounds(mTmpRect).printShortString(pw);
        pw.println();
        pw.println(prefix + "  mIsImeShowing=" + mIsImeShowing);
        pw.println(prefix + "  mInInteractiveMode=" + mInInteractiveMode);
    }
}
