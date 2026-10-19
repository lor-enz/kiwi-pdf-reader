package kiwi.lorenz.pdfreader.recent

import org.json.JSONObject

/** How [RecentFilesStore] saves an entry in its index. New fields need a default when read. */
internal fun RecentFile.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("uri", uri)
    .put("name", name)
    .put("isCopy", isCopy)
    .put("contentHash", contentHash)
    .put("sizeBytes", sizeBytes)
    .put("modifiedMillis", modifiedMillis)
    .put("lastOpenedMillis", lastOpenedMillis)
    .put("pageCount", pageCount)
    .put("lastPage", lastPage)
    .put("hasThumbnail", hasThumbnail)
    .put("isProtected", isProtected)
    .put("encryptedPassword", encryptedPassword)
    .put("filledFromHash", filledFromHash)
    .put("filledBytes", filledBytes)
    .put("title", title)
    .put("isTitleRead", isTitleRead)

internal fun JSONObject.toRecentFile(): RecentFile {
    val encryptedPassword = optString("encryptedPassword").ifEmpty { null }
    return RecentFile(
        id = getString("id"),
        uri = getString("uri"),
        name = getString("name"),
        isCopy = getBoolean("isCopy"),
        contentHash = optString("contentHash").ifEmpty { null },
        sizeBytes = optLong("sizeBytes"),
        modifiedMillis = if (has("modifiedMillis")) getLong("modifiedMillis") else null,
        lastOpenedMillis = getLong("lastOpenedMillis"),
        pageCount = optInt("pageCount"),
        lastPage = optInt("lastPage"),
        hasThumbnail = optBoolean("hasThumbnail"),
        // Saved before the flag existed: a remembered password says it's protected.
        isProtected = optBoolean("isProtected", encryptedPassword != null),
        encryptedPassword = encryptedPassword,
        filledFromHash = optString("filledFromHash").ifEmpty { null },
        filledBytes = optLong("filledBytes"),
        title = optString("title").ifEmpty { null },
        // Saved before titles were: read once, see RecentFilesStore.readMissingTitles.
        isTitleRead = optBoolean("isTitleRead", false),
    )
}
