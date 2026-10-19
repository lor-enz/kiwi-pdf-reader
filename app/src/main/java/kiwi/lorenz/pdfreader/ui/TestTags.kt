package kiwi.lorenz.pdfreader.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Shows test tags to adb-driven testing (uiautomator) as resource ids. Set on each activity's
 * root (see [setAppContent]) and again on sheets and dialogs, which are windows of their own.
 */
fun Modifier.exposeTestTags() = semantics { testTagsAsResourceId = true }

/**
 * Stable ids for UI tests and adb-driven testing. They show up as resource ids in
 * `uiautomator dump`, because the root sets `testTagsAsResourceId`.
 */
object TestTags {
    const val OPEN_PDF = "open_pdf"
    const val RECENT_FILE = "recent_file"
    const val RECENT_TITLE = "recent_title"
    const val RECENT_FILE_NAME = "recent_file_name"
    const val RECENT_LOCKED = "recent_locked"
    const val RECENT_UNLOCKED = "recent_unlocked"
    const val RECENT_SHEET_TITLE = "recent_sheet_title"
    const val SHARE_RECENT = "share_recent"
    const val RECENT_INFO = "recent_info"
    const val FORGET_PASSWORD = "forget_password"
    const val REMOVE_RECENT = "remove_recent"
    const val SETTINGS = "settings"

    const val FORGET_ALL_PASSWORDS = "forget_all_passwords"
    const val CONFIRM_FORGET_ALL_PASSWORDS = "confirm_forget_all_passwords"
    const val CLEAR_RECENT_FILES = "clear_recent_files"
    const val CONFIRM_CLEAR_RECENT_FILES = "confirm_clear_recent_files"
    const val VERSION = "version"
    const val SOURCE_CODE = "source_code"
    const val REPORT_PROBLEM = "report_problem"
    const val PRIVACY_POLICY = "privacy_policy"
    const val LICENSES = "licenses"
    const val LICENSE_TEXT = "license_text"

    const val BACK = "back"
    const val TOP_BAR_TITLE = "top_bar_title"
    const val TOP_BAR_FILE_NAME = "top_bar_file_name"
    const val MORE_OPTIONS = "more_options"
    const val DOCUMENT = "document"
    const val TOOLS_SHEET_TITLE = "tools_sheet_title"
    const val TOOL_SEARCH = "tool_search"
    const val TOOL_PRINT = "tool_print"
    const val TOOL_SHARE = "tool_share"
    const val TOOL_SAVE_COPY = "tool_save_copy"
    const val TOOL_INFO = "tool_info"
    const val TOOL_CLEAR_FILLED = "tool_clear_filled"
    const val CONFIRM_CLEAR_FILLED = "confirm_clear_filled"

    const val INFO_SHEET_TITLE = "info_sheet_title"
    /** The file name below a sheet's heading, when the document has a title. */
    const val HEADING_FILE_NAME = "heading_file_name"
    const val INFO_AUTHOR = "info_author"
    const val INFO_SUBJECT = "info_subject"
    const val INFO_PAGES = "info_pages"
    const val INFO_PAGE_SIZE = "info_page_size"
    const val INFO_FILE_SIZE = "info_file_size"
    const val INFO_CREATED = "info_created"
    const val INFO_MODIFIED = "info_modified"
    const val INFO_MADE_WITH = "info_made_with"

    const val SEARCH_FIELD = "search_field"
    const val CLOSE_SEARCH = "close_search"
    const val MATCH_COUNTER = "match_counter"
    const val PREVIOUS_MATCH = "previous_match"
    const val NEXT_MATCH = "next_match"
    const val NO_TEXT_TO_SEARCH = "no_text_to_search"

    const val PASSWORD_LOCK = "password_lock"
    const val PASSWORD_FILE_NAME = "password_file_name"
    const val PASSWORD_FIELD = "password_field"
    const val REMEMBER_PASSWORD = "remember_password"
    const val SUBMIT_PASSWORD = "submit_password"

    const val FAILURE_TITLE = "failure_title"
    const val FAILURE_MESSAGE = "failure_message"
    const val OPEN_ANOTHER = "open_another"
    const val TRY_AGAIN = "try_again"
}
