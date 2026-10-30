package com.farmerbb.taskbar.virtualdisplay;

import android.view.Surface;

interface IVirtualDisplayService {
    void destroy() = 16777114;
    int createVirtualDisplay(int width, int height, int densityDpi, in Surface surface) = 1;
    void releaseVirtualDisplay() = 4;
    String getLastError() = 5;
    boolean injectHover(int displayId, float x, float y) = 7;
    boolean injectMouseClick(int displayId, float x, float y) = 9;
    boolean injectTouchEvent(int displayId, int action, float x, float y) = 14;
    boolean injectTouchPoints(int displayId, int action, in int[] pointerIds, in float[] xs, in float[] ys) = 16;
    boolean injectTouchClick(int displayId, float x, float y) = 15;
    boolean injectMouseButton(int displayId, int action, float x, float y) = 12;
    boolean injectKeyEvents(int displayId, in int[] actions, in int[] keyCodes, in int[] metaStates) = 13;
    boolean injectMouseScroll(int displayId, float x, float y, float horizontal, float vertical) = 10;
    boolean setVirtualDisplaySurface(in Surface surface) = 11;
    String getDisplayDiagnostics(int displayId) = 8;
    boolean runShellCommand(String command) = 17;
    String requestRootAccess() = 18;
}
