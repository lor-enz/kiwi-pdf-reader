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
    .put("encryptedPassword", encryptedPassword)
    .put("filledFromHash", filledFromHash)
    .put("filledBytes", filledBytes)

internal fun JSONObject.toRecentFile() = RecentFile(
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
    encryptedPassword = optString("encryptedPassword").ifEmpty { null },
    filledFromHash = optString("filledFromHash").ifEmpty { null },
    filledBytes = optLong("filledBytes"),
)
