# Bundled C++ runtime

`src/main/jniLibs/arm64-v8a/libc++_shared.so` is an unmodified file extracted from
Google's official Android NDK r25c (25.2.9519653) Windows archive. Its version
matches the Android NDK note in the existing `libopencv_java4.so`; the OpenCV
binary is unchanged. No Insta360/vendor camera library is introduced.

The official download index is
[android/ndk Unsupported Downloads](https://github.com/android/ndk/wiki/Unsupported-Downloads#r25c).
The archive's published SHA-1 and size were verified before extraction. Archive,
runtime, original OpenCV and full NOTICE SHA-256 values and exact archive entries
are recorded in `native-runtime-provenance.json`.

`NDK-r25c-NOTICE.txt` is the complete, unmodified NOTICE extracted from that same
archive. It includes the LLVM Apache License 2.0 with LLVM Exceptions and the
libc++abi/third-party notices. Keep this NOTICE with redistributed binaries.
The obsolete NDK archive is used only to obtain the runtime matching the
unchanged existing OpenCV build; it is not an endorsement of an unsupported NDK
for future native development.

`node --test tests/native-apk.test.mjs` (from the repository root) checks source
hashes and Debug/Release APK contents after both APKs are built. APK checks also
compare normalized loaded ELF segment hashes, preserving compatibility with AGP
debug-symbol stripping. Android JNI tests use only generated fixtures and fail
on any OpenCV-to-Kotlin fallback; they do not prove tactile perception or human
shape recognition.
