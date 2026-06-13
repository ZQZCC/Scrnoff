# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Release APK should not expose Java/Kotlin debug metadata in decompiled code.
-keepattributes !SourceFile,!SourceDebugExtension,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,!MethodParameters,*

# Remove logging and console diagnostics from optimized release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int e(...);
    public static int i(...);
    public static int v(...);
    public static int w(...);
    public static int wtf(...);
}

-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
    public java.io.PrintStream printf(...);
    public java.io.PrintStream format(...);
}

-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
    public void printStackTrace(java.io.PrintStream);
    public void printStackTrace(java.io.PrintWriter);
}

-keep,allowoptimization class ka.tile.scrnoff.ScreenController { *; }
-keep,allowoptimization class ka.tile.scrnoff.BinderContainer { *; }
-keep,allowoptimization class ka.tile.scrnoff.BinderContainer$* { *; }

-keep,allowoptimization class ka.tile.scrnoff.IScreenOff { *; }
-keep,allowoptimization class ka.tile.scrnoff.IScreenOff$* { *; }
-keep,allowoptimization class android.app.IApplicationThread { *; }
-keep,allowoptimization class android.app.IApplicationThread$* { *; }
-keep,allowoptimization class android.content.IIntentReceiver { *; }
-keep,allowoptimization class android.content.IIntentReceiver$* { *; }

-keep class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
