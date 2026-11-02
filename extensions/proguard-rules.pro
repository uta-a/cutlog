# extension は setlog のプロセスへ注入されるため、難読化せず最小限のコードだけを残す。
-dontobfuscate
-dontoptimize
-keepattributes *

# パッチが smali から参照するクラスを保持する。
-keep class dev.utaa.cutlog.extension.** {
  *;
}

# extension は Java のみで実装し、Kotlin stdlib は注入しない。
-dontwarn kotlin.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier
