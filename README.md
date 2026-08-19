# Mic Anchor

**Fixes slowed-down / deep-voiced microphone recordings on MediaTek MT8167 devices, for every app on the device.**

If you record at 16000 Hz mono on affected hardware and the playback sounds slowed down, pitched
down, or "demonic" while 48000 Hz stereo recording sounds perfectly fine; this is that bug.

Known affected: **Lenovo Smart Clock 2** (MT8167). Likely affects other MediaTek devices using the
same audio HAL.

---

## The symptom

- Recording at 16 kHz mono → audio plays back slow and deep.
- Recording at 48 kHz stereo → audio is clean.
- No errors anywhere. `AudioRecord` reports successful init, start, and stop the whole time.
- Breaks anything that wants 16 kHz mono — which is exactly what most voice assistants and
  speech-to-text pipelines ask for (Home Assistant Assist, Whisper, View Assist, VACA).

## The cause

The mic ADC on this hardware only ever captures natively at 48000 Hz stereo:

```
AudioSPELayer: mRecordSampleRate=48000
```

Any request for a different rate or channel count is routed through MediaTek's on-board
"BesRecord" sample-rate converter:

```
AudioALSACaptureDataClient: CheckNeedBesRecordSRC()
```

That converter silently corrupts the output while still reporting success at the `AudioRecord`
API level. It is a vendor HAL bug. Nothing in the Java/Kotlin app layer can see it or fix it.

## Why a background service fixes it

AudioFlinger does not open one HAL input stream per app. It opens **a single stream on the vendor
HAL and shares it among clients.** Whichever client opens the mic first determines that stream's
format.

- First client asks for 16 kHz mono → the HAL stream opens at 16 kHz → `CheckNeedBesRecordSRC()`
  fires → broken vendor SRC → corrupted audio for that client and everyone after it.
- First client asks for 48 kHz stereo → no conversion needed at the HAL level. A later client
  asking for 16 kHz mono is attached to the existing 48 kHz stream, and **AudioFlinger's own
  software resampler** does the conversion. That resampler works correctly.

Mic Anchor is that first client. It opens an `AudioRecord` at 48000 Hz stereo, reads samples into
a throwaway buffer, and never stops. That's the entire app.

**The captured audio is discarded immediately. Nothing is stored, written to disk, or
transmitted.** There is no network permission in the manifest — the app cannot make network
requests even if it wanted to. See
[`MicAnchorService.kt`](app/src/main/java/com/micanchor/MicAnchorService.kt); the whole thing is
about 200 lines.

## Why this is better than patching individual apps

The alternative is forking each affected app to capture at 48 kHz and downsample in software. That
works, but:

- It has to be redone for every app you care about.
- It has to be rebased on every upstream release.
- **It is impossible for closed-source apps.** VACA (View Assist Companion App) cannot be patched
  at all.

Mic Anchor fixes everything on the device at once, including closed-source apps.

---

## ⚠️ Before you start: nothing else may be holding the mic

**Mic Anchor only works if it is the *first* client to open the microphone.** That is the entire
mechanism — whichever app opens the mic first determines the shared HAL stream's format.

If another app already has the mic open at 16 kHz when the anchor starts, the anchor is simply
attached to that existing 16 kHz stream and nothing is fixed. It will appear to run correctly while
doing nothing useful.

So before starting it, **stop everything that uses the microphone**, including things you may not
realise are listening:

- Voice assistants and hotword listeners. On the Lenovo Smart Clock 2, `com.google.assistant.core`
  runs a permanent 16 kHz hotword capture — this is the usual culprit.
- Home Assistant Companion, View Assist, VACA, or any other voice satellite app.
- Recorder apps, video call apps, anything with an open mic.

```bash
# Force-stop a competing app
adb shell am force-stop <package.name>

# Check what's currently holding an input stream
adb shell dumpsys media.audio_flinger | grep -iE "Input thread|Sample rate|Audio source|record clients"
```

A stream with **no** "No active record clients" line under it has a live client holding it.

Once Mic Anchor is running and holding the 48 kHz stream, you can start your voice apps again
normally — they'll attach to the anchor's stream and get clean audio. **The ordering only matters at
startup**, which is why boot persistence matters (see below).

If a hotword listener wins the race at every boot, you'll need to disable it for the anchor to be
useful on that device.

## Install

Download the APK from [Releases](../../releases) and sideload it:

```bash
adb install -r micanchor.apk
adb shell pm grant com.micanchor android.permission.RECORD_AUDIO
```

Then start it either way:

**Tap the app icon.** Mic Anchor appears in your launcher as "Mic Anchor". Opening it starts the
service and closes immediately — there is no UI, that's expected. A "Mic anchor started" toast
confirms it. After that it runs in the background on its own and you never need to open it again.

**Or start it over ADB:**

```bash
adb shell am start-foreground-service -n com.micanchor/.MicAnchorService
```

The permission grant over ADB is recommended because kiosk-style launchers (like the Smart Clock's)
often swallow the runtime permission dialog. If your device has a normal launcher, tapping the icon
will prompt for the mic permission normally and the `pm grant` step is unnecessary.

Root is **not** required.

## Verify it's working

```bash
adb shell dumpsys media.audio_flinger | grep -iE "Input thread|Sample rate|Channel count|Audio source|record clients"
```

You want to see a thread with:

```
  Sample rate: 48000 Hz
  Channel count: 2
  Audio source: 1 (AUDIO_SOURCE_MIC)
```

and **no** "No active record clients" line under it. If you see `Sample rate: 16000 Hz` with
`Channel count: 1`, the anchor is not holding the stream — see Troubleshooting.

Then do the actual ear test: record 16 kHz mono in any recorder app and play it back. A good
lightweight one is
[Recording Studio Lite](https://f-droid.org/en/packages/io.github.leonidius20.recorder.lite/)
(F-Droid), which lets you set the sample rate explicitly.

## Troubleshooting

**Stream shows 16000 Hz instead of 48000 Hz.** Something else grabbed the mic first — see the
section above. Force-stop the competing app, then force-stop and restart Mic Anchor so it can open
the stream cleanly:

```bash
adb shell am force-stop com.micanchor
adb shell am start-foreground-service -n com.micanchor/.MicAnchorService
```

Restarting the anchor alone is not enough; the competing app has to release the mic first, or the
old stream stays open and the anchor just re-attaches to it.

**Works when started manually, but broken after a reboot.** A hotword listener is winning the race
at boot. Either disable it, or use the Magisk `service.d` script in [`extras/`](extras/) and reduce
its startup delay so the anchor starts earlier.

**Service starts but nothing happens.** Check the process is actually alive:

```bash
adb shell pidof com.micanchor
adb logcat -d | grep -i micanchor
```

**Audio still bad in one specific app.** That app may be requesting a different audio source
(`VOICE_RECOGNITION` or `VOICE_COMMUNICATION` rather than `MIC`), which can be routed to a separate
HAL input stream that bypasses the anchor. Open an issue with the output of the `dumpsys` command
above taken *while that app is recording*.

## Build from source

Requires JDK 17 and Android SDK platform 34.

```bash
git clone https://github.com/fritzbytes/MicAnchor.git
cd MicAnchor
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Every release APK is built by GitHub Actions from the tagged commit — check the Actions tab to see
the build log for any release you download.

## Boot persistence

`BootReceiver` handles `BOOT_COMPLETED` and works on most devices. If your launcher is unreliable
about delivering boot broadcasts (some kiosk launchers are), and you have root, there's a Magisk
`service.d` script in [`extras/`](extras/) as a backup.

## Notes and caveats

- The service holds a `PARTIAL_WAKE_LOCK` to stay alive. On a mains-powered device this is a
  non-issue; on battery it will prevent deep sleep.
- Continuous capture is cheap (it's a read-and-discard loop) but not free.
- A persistent notification is required — Android will not allow background mic access without
  foreground service status.
- `targetSdk` is 28 to match the affected hardware. On Android 10+ you'd want to add
  `foregroundServiceType` enforcement handling.

## Credits

Root cause traced by decompiling and cross-referencing live `logcat` output on a Lenovo Smart Clock
2. The diagnostic command that surfaced it:

```bash
adb logcat | grep -iE "audiorecord|samplerate"
```

Building on the rooting work at
[ThomasPrior/LenovoSmartClock2](https://github.com/ThomasPrior/LenovoSmartClock2).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
