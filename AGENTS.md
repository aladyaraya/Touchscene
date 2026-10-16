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
- The 2026-10-16 touch/contour repair permits exactly three TactileMapView edits:
  remove the coarse-grid null gate for displayed-image sampling and use the
  tested display-only ContourBandRasterizer with canvas perimeter filtering;
  ORIGINAL samples zero instead of pixel brightness (user-reported false haptics).
  Guards must restore only those exact edits to recover the original hash.
  GrabCut, its boundary/subject masks, parameters and haptic waveforms stay original.
- AndroidHapticRenderer has two exact call-site exceptions routing existing effects
  through TouchFeedbackVibration. This labels them TOUCH (legacy sonification on
  API29-32), without interruption bypass, waveform edits or camera lifecycle changes.
  Reverse only those calls for baseline hashing; all state/timing code stays protected.
- The user now wants whole-image content narration, not single-subject framing.
  Keep original-image input and existing Bailian model/API; do not imply a new
  multi-object segmentation algorithm was implemented. Offline hints must be
  labeled partial and must not present contour position/size as a scene caption.
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
- The user explicitly approved app_name = 触景Touchscene in both locales on
  2026-10-16; this is the sole original-string exception. Keep all other guards.
  Local build/cache trees may be removed after validation; rebuild before APK
  guards, do not restore tracked generated output from the historical worktree.
