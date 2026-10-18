package kiwi.lorenz.pdfreader.ui

/**
 * Stable ids for UI tests and adb-driven testing. They show up as resource ids in
 * `uiautomator dump`, because the root sets `testTagsAsResourceId`.
 */
object TestTags {
    const val OPEN_PDF = "open_pdf"
    const val RECENT_FILE = "recent_file"
    const val RECENT_SHEET_TITLE = "recent_sheet_title"
    const val REMOVE_RECENT = "remove_recent"

    const val BACK = "back"
    const val MORE_OPTIONS = "more_options"
    const val DOCUMENT = "document"
    const val TOOL_SEARCH = "tool_search"
    const val TOOL_PRINT = "tool_print"
    const val TOOL_SHARE = "tool_share"

    const val SEARCH_FIELD = "search_field"
    const val CLOSE_SEARCH = "close_search"
    const val MATCH_COUNTER = "match_counter"
    const val PREVIOUS_MATCH = "previous_match"
    const val NEXT_MATCH = "next_match"
    const val NO_TEXT_TO_SEARCH = "no_text_to_search"

    const val PASSWORD_FIELD = "password_field"
    const val REMEMBER_PASSWORD = "remember_password"
    const val SUBMIT_PASSWORD = "submit_password"

    const val FAILURE_MESSAGE = "failure_message"
    const val OPEN_ANOTHER = "open_another"
}
