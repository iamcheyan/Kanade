# Custom gradle parameters

You can use these parameters by specifying them inside the `gradle.properties` file as 
`systemProp.<name>=<value>` or passing them through the CLI with `-D<name>=<value>`.

## packageSuffix
This allows you to specify a suffix, which will be added on release builds to the application id, 
the `app_name` string and the apk file.  
No validation is made, so make sure to pass in a valid value.

## skipFormatKtlint
This allows you to skip the `formatKtLint` task.  
No value is needed.   
It is used for CI in order to check for badly formatted files.

## Building and installing the Android debug app with Nix

When Java 21 is not on `PATH`, run Gradle inside a temporary Nix shell that provides JDK 21:

```sh
nix-shell -p jdk21 --run './gradlew :app:assembleDebug'
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected Android device and launch Kanade:

```sh
nix-shell -p jdk21 --run './gradlew :app:installDebug'
adb shell monkey -p com.jm21.player.debug 1
```

Check connected devices first with `adb devices -l`. When more than one device is connected, select one explicitly with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`; the debug application ID is `com.jm21.player.debug`.
