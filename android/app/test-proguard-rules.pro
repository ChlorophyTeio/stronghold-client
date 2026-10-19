# Platform-only test runner; no extra Kotlin runtime shared with a minified app.
-keep class top.ppparty.stronghold_client.NativeSmokeInstrumentation { *; }

-keep class top.ppparty.stronghold_client.UpdateSmoke { *; }

-keep class top.ppparty.stronghold_client.UpgradeSmoke { *; }
