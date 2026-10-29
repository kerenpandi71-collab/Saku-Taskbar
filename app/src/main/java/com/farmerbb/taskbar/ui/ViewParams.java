/* Copyright 2019 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.ui;

import android.graphics.PixelFormat;
import android.os.Build;
import android.view.WindowManager;

import com.farmerbb.taskbar.util.U;

import java.lang.reflect.Field;

public class ViewParams {
    public int width;
    public int height;
    public int gravity;
    public int flags;
    public int bottomMargin;
    private int blurBehindRadius;

    public ViewParams(int width, int height, int gravity, int flags, int bottomMargin) {
        this.width = width;
        this.height = height;
        this.gravity = gravity;
        this.flags = flags;
        this.bottomMargin = bottomMargin;
    }

    public WindowManager.LayoutParams toWindowManagerParams() {
        final WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams(
                width,
                height,
                U.getOverlayType(),
                flags,
                PixelFormat.TRANSLUCENT
        );

        if(gravity > -1)
            wmParams.gravity = gravity;

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurBehindRadius > 0) {
            wmParams.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            wmParams.setBlurBehindRadius(blurBehindRadius);
        }

        if(bottomMargin > -1)
            wmParams.y = bottomMargin;

        U.allowReflection();
        try {
            Class<?> layoutParamsClass = Class.forName("android.view.WindowManager$LayoutParams");

            Field privateFlags = layoutParamsClass.getField("privateFlags");
            Field noAnim = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION");

            int privateFlagsValue = privateFlags.getInt(wmParams);
            int noAnimFlag = noAnim.getInt(wmParams);
            privateFlagsValue |= noAnimFlag;

            privateFlags.setInt(wmParams, privateFlagsValue);
        } catch (Exception ignored) {}

        return wmParams;
    }

    public ViewParams withBlurBehindRadius(int radiusPixels) {
        ViewParams params = new ViewParams(width, height, gravity, flags, bottomMargin);
        params.blurBehindRadius = Math.max(0, radiusPixels);
        return params;
    }

    public ViewParams updateWidth(int width) {
        ViewParams params = new ViewParams(width, height, gravity, flags, bottomMargin);
        params.blurBehindRadius = blurBehindRadius;
        return params;
    }

    public ViewParams updateHeight(int height) {
        ViewParams params = new ViewParams(width, height, gravity, flags, bottomMargin);
        params.blurBehindRadius = blurBehindRadius;
        return params;
    }

    public ViewParams updateBottomMargin(int bottomMargin) {
        ViewParams params = new ViewParams(width, height, gravity, flags, bottomMargin);
        params.blurBehindRadius = blurBehindRadius;
        return params;
    }
}