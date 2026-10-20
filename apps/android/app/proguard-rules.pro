# Filament : classes appelées depuis le code natif (JNI)
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
# Kotlin (dépendance de filament-utils)
-dontwarn kotlin.**
-dontwarn kotlinx.**
# Noyau métier : records utilisés par réflexion nulle part, mais conservés pour des traces lisibles
-keepattributes SourceFile,LineNumberTable
