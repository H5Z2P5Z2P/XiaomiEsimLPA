## MIUI eSIM Slot Hook

This module adds a slot chooser to the `Manage eSIM` entry in `com.android.phone`
and forces `MIUIEsimLPA` to use the selected slot end-to-end.

It hooks:

- `com.android.phone.MiuiPhoneUtils.enterEsimManager()`
- `com.miui.euicc.LpaApplication.c()`
- `k4.b.a()`
- `z3.b` logical channel open / transmit / close methods

The selected slot is persisted in `Settings.Secure` under
`openeuicc_manage_slot` and mirrored to `MIUIEsimLPA` shared preferences for
warm-process launches.

## Build

`./build.sh`

Default SDK root:

- `/Volumes/JZ/Android/sdk`

Override with `ANDROID_SDK_ROOT` or `ANDROID_HOME` if needed.

The build script outputs a signed debug APK at:

- `build/miui-esim-slot-hook-debug.apk`

For update-compatible local rebuilds, keep your generated `.signing/debug.keystore`
outside git.

## Runtime

Requires an Xposed-compatible framework such as LSPosed.

Recommended scope:

- `com.android.phone`
- `com.miui.euicc`
