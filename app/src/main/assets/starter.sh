#!/system/bin/sh

package_name="ka.tile.scrnoff"
main_class="ka.tile.scrnoff.ScreenController"
log_path="/data/local/tmp/screenoff.log"

pm grant "$package_name" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true

apk_path="$(pm path "$package_name" | sed -n '1s/^package://p')"
if [ -z "$apk_path" ]; then
    echo "ScreenOff: unable to locate APK for $package_name" >> "$log_path"
    exit 1
fi

export CLASSPATH="$apk_path"
nohup app_process /system/bin "$main_class" >> "$log_path" 2>&1 &
