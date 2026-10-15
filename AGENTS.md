# TouchScene Android maintenance

Read `README.md`, `docs/ARCHITECTURE.md`, `docs/ACCEPTANCE.md` and
`docs/MAINTENANCE.md` before changing this branch.

- Active app entrypoints are in `android/app/src/mobile/java`, not the preserved
  original SDK Activity/Application/Preview under main. Source roots are explicit
  in app Gradle; do not re-enable the vendor SDK, assets or Maven credentials.
- Preserve the original OpenCV algorithms, parameters, YUV helpers, layer order,
  coordinates, frozen map behavior and haptic waveforms unless explicitly requested.
- Keep full-source guards: the baseline fixture is from original commit 3eea391,
  not a snapshot of whatever currently passes. Never regenerate it to hide drift.
- Keep native libraries and provenance/NOTICE together; source checks do not
  replace actual JNI tests, and fallback Canny does not count as native acceptance.
- Never commit keys, generated BuildConfig, local.properties, APKs, keystores,
  build directories, traces, photos, audio or private material. Do not publish
  a locally keyed Debug package. CI must remain keyless and read-only.
- Keep historical verification separate from a fresh run. Human sound, felt
  vibration, TalkBack and shape comprehension require explicit human evidence.
- Use scoped topic branches and reviewable commits for future changes; no force
  pushes, repository permission changes, app uninstall or data clear without request.
- Run source guards, JVM tests, Debug/unsigned Release/test builds, native APK
  guards and Lint as documented. Update affected docs and verification evidence.
