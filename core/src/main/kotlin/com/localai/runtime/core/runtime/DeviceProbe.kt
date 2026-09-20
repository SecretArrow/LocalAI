package com.localai.runtime.core.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import com.localai.runtime.core.model.Availability
import com.localai.runtime.core.model.DeviceCapabilities
import com.localai.runtime.core.runtime.cpu.LlamaBridge
import java.io.File

/**
 * One-shot device capability probe. All external calls are guarded; anything
 * that cannot be determined degrades to a safe default instead of crashing.
 *
 * GPU identity is queried synchronously through a throw-away EGL 2.0 pbuffer
 * context. Call from a background thread (BackendRegistry.detectAll does).
 */
class DeviceProbe(private val context: Context) {

    private val appContext = context.applicationContext

    fun probe(): DeviceCapabilities {
        val abis = try {
            Build.SUPPORTED_ABIS.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        val activityManager = try {
            appContext.getSystemService(android.app.ActivityManager::class.java)
        } catch (_: Throwable) {
            null
        }
        var totalRam = 0L
        var availableRam = 0L
        try {
            if (activityManager != null) {
                val info = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(info)
                totalRam = info.totalMem
                availableRam = info.availMem
            }
        } catch (_: Throwable) {
            // leave zeros
        }

        val gpu = queryGpu()

        val packageManager = try {
            appContext.packageManager
        } catch (_: Throwable) {
            null
        }

        val vulkan = try {
            if (packageManager?.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) == true) {
                Availability.AVAILABLE
            } else {
                Availability.UNAVAILABLE
            }
        } catch (_: Throwable) {
            Availability.UNKNOWN
        }

        // Library presence does not imply a usable OpenCL stack (no public NDK API).
        val opencl = if (hasOpenClLibrary()) Availability.UNKNOWN else Availability.UNAVAILABLE

        // NNAPI needs a delegate runtime (ONNX/TFLite) that this build does not
        // bundle — report SUPPORTED at most, never claim execution.
        val nnapi = try {
            if (packageManager?.hasSystemFeature("android.hardware.neuralnetworks") == true) {
                Availability.SUPPORTED
            } else {
                Availability.UNKNOWN
            }
        } catch (_: Throwable) {
            Availability.UNKNOWN
        }

        return DeviceCapabilities(
            abi = abis,
            arch = abis.firstOrNull()?.substringBefore('-') ?: "",
            cpuCores = try {
                Runtime.getRuntime().availableProcessors()
            } catch (_: Throwable) {
                1
            },
            totalRamBytes = totalRam,
            availableRamBytes = availableRam,
            gpuVendor = gpu.first,
            gpuModel = gpu.second,
            vulkan = vulkan,
            opencl = opencl,
            nnapi = nnapi,
            npu = Availability.UNKNOWN, // no public NPU API
            androidVersion = try {
                Build.VERSION.RELEASE ?: ""
            } catch (_: Throwable) {
                ""
            },
            deviceModel = try {
                Build.MODEL ?: ""
            } catch (_: Throwable) {
                ""
            },
            cpuBackendAvailable = LlamaBridge.available,
        )
    }

    // ------------------------------------------------------------------ //

    private fun hasOpenClLibrary(): Boolean = try {
        File("/system/lib64/libOpenCL.so").exists() || File("/system/lib/libOpenCL.so").exists()
    } catch (_: Throwable) {
        false
    }

    /**
     * EGL/GLES query for GL_VENDOR and GL_RENDERER. Creates a temporary pbuffer
     * context, makes it current, reads the strings and releases everything.
     * Any failure returns ("Unknown", "Unknown").
     */
    private fun queryGpu(): Pair<String, String> {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var initialized = false
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == null || display == EGL14.EGL_NO_DISPLAY) return UNKNOWN_GPU
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return UNKNOWN_GPU
            initialized = true

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val matched = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, matched, 0) ||
                matched[0] == 0 || configs[0] == null
            ) {
                return UNKNOWN_GPU
            }

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return UNKNOWN_GPU

            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return UNKNOWN_GPU

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return UNKNOWN_GPU

            val renderer = try {
                GLES20.glGetString(GLES20.GL_RENDERER)
            } catch (_: Throwable) {
                null
            }
            val vendor = try {
                GLES20.glGetString(GLES20.GL_VENDOR)
            } catch (_: Throwable) {
                null
            }
            return (vendor ?: "Unknown") to (renderer ?: "Unknown")
        } catch (_: Throwable) {
            return UNKNOWN_GPU
        } finally {
            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, surface)
                    }
                    if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context)
                    }
                    // The default display is intentionally left initialized:
                    // terminating it could disrupt other EGL users in-process.
                    if (initialized) {
                        // no eglTerminate on purpose (see above)
                    }
                }
            } catch (_: Throwable) {
                // best-effort cleanup
            }
        }
    }

    private companion object {
        val UNKNOWN_GPU = "Unknown" to "Unknown"
    }
}
