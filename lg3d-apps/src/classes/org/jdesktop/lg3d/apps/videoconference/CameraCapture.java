/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.videoconference;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A pluggable local camera-capture seam.
 *
 * <p><b>Why a seam, not a direct webcam grab.</b> The JDK ships no standard
 * webcam API, and this project deliberately carries no native media stack (no
 * JMF, no OpenCV/JavaCV, no GStreamer bindings). The actual audio/video of a
 * meeting is carried by the launched Jitsi Meet client, which owns the real
 * camera through the browser's WebRTC stack. This interface therefore exists so
 * the pre-call lobby can show a local preview <em>when a capture backend is
 * present</em>, and degrade gracefully to a clear "camera preview unavailable"
 * state when it is not &mdash; which is the default, out-of-the-box case.</p>
 *
 * <p>A backend is discovered by reflection: {@link #detect()} loads the optional
 * class named by the {@value #BACKEND_PROPERTY} system property (or the built-in
 * {@value #DEFAULT_BACKEND} name) if it is on the classpath, so a future native
 * capture library can be dropped in with zero code change here. When nothing
 * resolves, {@link NullCameraCapture} is returned and {@link #isAvailable()} is
 * {@code false}.</p>
 */
public interface CameraCapture {

    /** System property naming an optional {@link CameraCapture} backend class. */
    String BACKEND_PROPERTY = "lg3d.videoconference.camera.backend";

    /** Default backend class name probed by {@link #detect()}. */
    String DEFAULT_BACKEND = "org.jdesktop.lg3d.apps.videoconference.V4lCameraCapture";

    /** A discovered camera device. */
    record DeviceInfo(String id, String name) {
        @Override
        public String toString() {
            return (name == null || name.isEmpty()) ? id : name;
        }
    }

    /** Receives decoded video frames on the capture thread. */
    interface FrameListener {
        /**
         * Called for each captured frame.
         *
         * @param frame the RGB image; not null
         */
        void onFrame(BufferedImage frame);
    }

    /** @return a short human-readable backend name (e.g. "v4l2", "none"). */
    String backendName();

    /** @return true when this backend can actually capture video. */
    boolean isAvailable();

    /** @return the cameras this backend can see (empty when unavailable). */
    List<DeviceInfo> listDevices();

    /** Registers a frame sink. May be called before or after {@link #start}. */
    void addFrameListener(FrameListener listener);

    /** Removes a previously registered frame sink. */
    void removeFrameListener(FrameListener listener);

    /**
     * Begins capturing from the given device.
     *
     * @param deviceId the device id, or null for the backend default
     * @return true when capture started
     */
    boolean start(String deviceId);

    /** Stops capturing and releases the device. */
    void stop();

    /** @return true while frames are being delivered. */
    boolean isRunning();

    /**
     * Resolves the best available backend: the class named by
     * {@value #BACKEND_PROPERTY} (else {@value #DEFAULT_BACKEND}) when it loads
     * and reports {@link #isAvailable()}, otherwise {@link NullCameraCapture}.
     *
     * @return a non-null capture backend
     */
    static CameraCapture detect() {
        String className = System.getProperty(BACKEND_PROPERTY, DEFAULT_BACKEND);
        if (className != null && !className.isBlank()) {
            try {
                Class<?> c = Class.forName(className);
                Object o = c.getDeclaredConstructor().newInstance();
                if (o instanceof CameraCapture cap && cap.isAvailable()) {
                    return cap;
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                // No backend present or it failed to initialise: fall through.
            }
        }
        return new NullCameraCapture();
    }

    /**
     * The default no-op backend used when no native capture library is present.
     * It reports no devices, never starts, and lets the UI show an honest
     * "camera preview unavailable" message.
     */
    final class NullCameraCapture implements CameraCapture {

        private final List<FrameListener> listeners = new CopyOnWriteArrayList<>();

        @Override
        public String backendName() {
            return "none";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public List<DeviceInfo> listDevices() {
            return List.of();
        }

        @Override
        public void addFrameListener(FrameListener listener) {
            if (listener != null) {
                listeners.add(listener);
            }
        }

        @Override
        public void removeFrameListener(FrameListener listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean start(String deviceId) {
            return false;
        }

        @Override
        public void stop() {
            // nothing to release
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }
}
