#!/system/bin/sh

package_name="ka.tile.scrnoff"
main_class="ka.tile.scrnoff.ScreenController"

pm grant "$package_name" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true

apk_path="$(pm path "$package_name" | sed -n '1s/^package://p')"
if [ -z "$apk_path" ]; then
    exit 1
fi

export CLASSPATH="$apk_path"
nohup app_process /system/bin "$main_class" >/dev/null 2>&1 &
