#!/system/bin/sh
# Magisk boot script backup for Mic Anchor.
# Install to /data/adb/service.d/micanchor.sh and chmod 755.
#
#   adb push micanchor.sh /sdcard/Download/
#   adb shell su -c 'cp /sdcard/Download/micanchor.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/micanchor.sh'
#
# Redundant with BootReceiver, but this device's kiosk launcher has been
# unreliable about delivering BOOT_COMPLETED. Starting twice is harmless --
# the service ignores a duplicate start.

PKG="com.micanchor"
SVC="$PKG/.MicAnchorService"
LOG="/data/local/tmp/micanchor-boot.log"

# Wait for the framework to finish coming up.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

# Give the launcher and audio HAL a moment to settle.
sleep 25

echo "$(date): starting $SVC" >> "$LOG"

# API 26+ path first, fall back to the legacy one.
am start-foreground-service -n "$SVC" >> "$LOG" 2>&1 \
    || am startservice -n "$SVC" >> "$LOG" 2>&1

exit 0
