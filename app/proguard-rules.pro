# MindSoul ProGuard 混淆规则
# 保护意识核心数据结构不被混淆

# 保持数据模型类
-keep class com.kkgo.mindsoul.model.** { *; }
-keep class com.kkgo.mindsoul.brain.BrainFileFormat$** { *; }

# 保持序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    void readObjectNoData();
}

# 保持数据库辅助类
-keep class com.kkgo.mindsoul.consciousness.layer3.MindSoulDatabase { *; }

# Kotlin 相关
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# 忽略警告
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
