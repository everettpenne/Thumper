# kotlinx.serialization keeps its generated serializers via its own consumer rules.
-keep class com.everettpenne.thumper.data.model.** { *; }

# Music library snapshots and Jellyfin downloads are kotlinx.serialization models too.
-keep class com.everettpenne.thumper.music.data.** { *; }
