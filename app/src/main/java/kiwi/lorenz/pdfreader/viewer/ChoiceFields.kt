package kiwi.lorenz.pdfreader.viewer

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.models.FormEditInfo
import androidx.pdf.models.FormWidgetInfo
import androidx.pdf.view.PdfView
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.ui.ActionSheet
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.util.runCatchingCancellable
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/** How many options show around the selected one when a long list opens. */
private const val SELECTED_MARGIN_ROWS = 2f

private const val CHOICE_TYPES =
    PdfDocument.FORM_WIDGET_INCLUDE_COMBOBOX_TYPE or PdfDocument.FORM_WIDGET_INCLUDE_LISTBOX_TYPE

/**
 * Opens the document's drop-downs and list boxes in a sheet of the app's own, instead of the
 * viewer's dialog: that one needs a second tap to confirm, drops the choice when tapped beside,
 * doesn't say which field it's for, and (beta01) doesn't show which option is selected.
 *
 * `PdfView` has no hook for this, so a quick tap on such a field is taken from it: it gets a
 * cancel instead of the lifted finger, and never sees a tap. Everything else (scrolling, zooming,
 * selecting text, other fields) still goes to it. The choice is applied like the view's own edits,
 * through [onEdit].
 */
// See ChoiceTapListener.onTouch for why the listener doesn't call performClick.
@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalPdfApi::class)
@Composable
internal fun ChoiceFields(
    document: PdfDocument,
    viewerState: PdfViewerState,
    isEnabled: Boolean,
    onEdit: (FormEditInfo) -> Unit,
) {
    val root = LocalView.current
    val scope = rememberCoroutineScope()
    // The fields of the pages seen so far, to find the one under a finger as it touches down. Only
    // their place and type are used from here, which edits don't change.
    val pageFields = remember(document) { mutableMapOf<Int, List<FormWidgetInfo>>() }
    var openPage by rememberSaveable { mutableIntStateOf(0) }
    var openField by rememberSaveable { mutableStateOf<FormWidgetInfo?>(null) }
    val currentIsEnabled by rememberUpdatedState(isEnabled)

    LaunchedEffect(document, viewerState) {
        snapshotFlow { viewerState.firstVisiblePage until viewerState.firstVisiblePage + viewerState.visiblePagesCount }
            .collect { pages ->
                for (page in pages) {
                    if (page in pageFields) continue
                    pageFields[page] = runCatchingCancellable { document.getFormWidgetInfos(page, CHOICE_TYPES) }
                        .getOrNull() ?: continue
                }
            }
    }

    DisposableEffect(root, document) {
        var pdfView: PdfView? = null
        val listener = ChoiceTapListener(
            findField = { view, x, y ->
                if (!currentIsEnabled) return@ChoiceTapListener null
                val point = view.viewToPdfPoint(x, y) ?: return@ChoiceTapListener null
                pageFields[point.pageNum]
                    ?.firstOrNull {
                        !it.isReadOnly && it.widgetRect.contains(point.x.roundToInt(), point.y.roundToInt())
                    }
                    ?.let { point.pageNum to it }
            },
            onTap = { (page, field) ->
                scope.launch {
                    // Read again for the current value: the one seen first may have been changed since.
                    val current = runCatchingCancellable {
                        document.getFormWidgetInfos(page, CHOICE_TYPES).firstOrNull { it.widgetIndex == field.widgetIndex }
                    }.getOrNull()
                    openPage = page
                    openField = current ?: field
                }
            },
        )
        // Posted, so the view exists.
        val attach = Runnable {
            pdfView = root.findPdfView()?.apply { setOnTouchListener(listener) }
        }
        root.post(attach)
        onDispose {
            root.removeCallbacks(attach)
            pdfView?.setOnTouchListener(null)
        }
    }

    openField?.let { field ->
        ChoiceSheet(page = openPage, field = field, onEdit = onEdit, onDismiss = { openField = null })
    }
}

/**
 * Takes a quick tap on a field [findField] finds (in the view's coordinates) from the view, and
 * hands it to [onTap]. A long press, a drag or a second finger is left to the view.
 */
private class ChoiceTapListener(
    private val findField: (PdfView, Float, Float) -> Pair<Int, FormWidgetInfo>?,
    private val onTap: (Pair<Int, FormWidgetInfo>) -> Unit,
) : View.OnTouchListener {
    private var tapped: Pair<Int, FormWidgetInfo>? = null
    private var downX = 0f
    private var downY = 0f

    // A tap needs no performClick: it opens the sheet, and screen readers go through the view's own
    // nodes for its fields.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapped = findField(view as PdfView, event.x, event.y)
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (hypot(event.x - downX, event.y - downY) > slop) tapped = null
            }
            MotionEvent.ACTION_UP -> {
                val field = tapped ?: return false
                tapped = null
                if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) return false
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                view.onTouchEvent(cancel)
                cancel.recycle()
                // The view would close a text field being edited on a tap; its text is applied
                // already, as it's typed. "Done" closes it the view's own way.
                (view.findFocus() as? TextView)?.onEditorAction(EditorInfo.IME_ACTION_DONE)
                onTap(field)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapped = null
        }
        return false
    }
}

/**
 * The options of a drop-down or list box. One option is chosen by tapping it, which closes the
 * sheet; in a list box that takes several, each tap ticks or unticks one right away. A drop-down
 * that takes text of its own has a field for it below the options.
 */
@Composable
private fun ChoiceSheet(page: Int, field: FormWidgetInfo, onEdit: (FormEditInfo) -> Unit, onDismiss: () -> Unit) {
    val items = field.listItems
    val initial = remember(field) {
        items.indices.filter { items[it].isSelected }
            .ifEmpty { listOf(items.indexOfFirst { it.label == field.textValue }).filter { it >= 0 } }
            .toSet()
    }
    var selected by remember(field) { mutableStateOf(initial) }
    val selectedRequester = remember { BringIntoViewRequester() }
    var selectedSize by remember { mutableStateOf(IntSize.Zero) }

    ActionSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
            Text(
                text = field.label() ?: stringResource(R.string.choice_untitled),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(TestTags.CHOICE_SHEET_TITLE),
            )
            if (field.isMultiSelect) {
                Text(
                    text = stringResource(R.string.choice_multiple),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items.forEachIndexed { index, item ->
            val isSelected = index in selected
            val rowModifier = Modifier
                .fillMaxWidth()
                .then(
                    if (index == initial.firstOrNull()) {
                        Modifier.bringIntoViewRequester(selectedRequester).onSizeChanged { selectedSize = it }
                    } else Modifier
                )
            if (field.isMultiSelect) {
                ListItem(
                    headlineContent = { Text(item.label) },
                    // Decorative: the row is the toggle.
                    leadingContent = { Checkbox(checked = isSelected, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rowModifier
                        .toggleable(value = isSelected, role = Role.Checkbox) {
                            selected = if (isSelected) selected - index else selected + index
                            onEdit(FormEditInfo.createSetIndices(page, field.widgetIndex, selected.sorted().toIntArray()))
                        }
                        .testTag(TestTags.CHOICE_OPTION)
                        .padding(horizontal = 8.dp),
                )
            } else {
                val color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified
                ListItem(
                    headlineContent = { Text(item.label, color = color) },
                    trailingContent = if (isSelected) {
                        { Icon(painterResource(R.drawable.ic_selected), contentDescription = null, tint = color) }
                    } else null,
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = rowModifier
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            // Applied right away, so the field shows it as the sheet slides off it.
                            if (!isSelected) onEdit(FormEditInfo.createSetIndices(page, field.widgetIndex, intArrayOf(index)))
                            close()
                        }
                        .testTag(TestTags.CHOICE_OPTION)
                        .padding(horizontal = 8.dp),
                )
            }
        }
        // Below the options, like "Other: ___" on paper: picking one is the usual way.
        if (field.isEditableText) {
            OtherField(
                initial = field.textValue?.takeIf { text -> items.none { it.label == text } }.orEmpty(),
                onDone = { text ->
                    if (text.isNotBlank()) onEdit(FormEditInfo.createSetText(page, field.widgetIndex, text))
                    close()
                },
            )
        }
    }

    // A long list (countries) opens where the selected option is, with a few options around it: on
    // its own it only just came into view, at the sheet's bottom edge.
    LaunchedEffect(field, selectedSize) {
        if (selectedSize == IntSize.Zero) return@LaunchedEffect
        val margin = selectedSize.height * SELECTED_MARGIN_ROWS
        selectedRequester.bringIntoView(Rect(0f, -margin, selectedSize.width.toFloat(), selectedSize.height + margin))
    }
}

@Composable
private fun OtherField(initial: String, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.choice_other)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(TestTags.CHOICE_OTHER),
    )
}

/**
 * What the field is called: its description for screen readers, or else its name. A generated
 * name like `form1[0].page1[0].country[0]` is cut to its last part, `Country`.
 */
internal fun FormWidgetInfo.label(): String? {
    val label = accessibilityLabel?.trim().orEmpty()
    if (label.any { it.isWhitespace() }) return label
    return label.substringAfterLast('.').replace(Regex("""\[\d+]$"""), "")
        .replaceFirstChar { it.uppercase() }
        .ifEmpty { null }
}
