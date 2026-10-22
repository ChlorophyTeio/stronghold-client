# Pigeon/JNI-generated Dart bindings use literal JVM class and method signatures.
# R8 cannot infer the Continuation descriptor embedded in native method lookups.
-keep interface kotlin.coroutines.Continuation { *; }
-keep interface kotlin.coroutines.CoroutineContext { *; }
-keep class kotlin.Unit { *; }

# Stable interfaces used by the platform-only release instrumentation APK.
# Keep only its public entry points; implementation internals remain optimized.
-keep class top.ppparty.stronghold_client.UpdateStore { public *; }
-keep class top.ppparty.stronghold_client.UpdateStore$Release { public *; }
-keep interface top.ppparty.stronghold_client.UpdateDownload { *; }
-keep class top.ppparty.stronghold_client.UpdateTransport { public *; }
-keep class top.ppparty.stronghold_client.TransferProgress { public *; }
-keep interface kotlin.jvm.functions.Function1 { *; }
