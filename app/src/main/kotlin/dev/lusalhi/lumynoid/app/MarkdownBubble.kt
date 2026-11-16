package dev.lusalhi.lumynoid.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import org.intellij.markdown.ast.findChildOfType
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/**
 * The bubble's annotator (AD-9): inline HTML is kept as literal text and an image becomes a link, so nothing
 * is rendered as HTML and no image slot is ever created. Every other node keeps the library's handling.
 */
fun safeMarkdownAnnotator(
    referenceLinks: ReferenceLinkHandler,
    linkStyle: SpanStyle,
    imageTemplate: String,
): MarkdownAnnotator = markdownAnnotator(annotate = { content, child ->
    when (child.type) {
        MarkdownTokenTypes.HTML_TAG -> {
            append(child.getTextInNode(content).toString())
            true
        }
        MarkdownElementTypes.IMAGE -> {
            val link = imageLinkText(content, child, referenceLinks, linkStyle, imageTemplate)
            if (link != null) append(link) else append(child.getTextInNode(content).toString())
            true
        }
        else -> false
    }
})

internal fun imageLinkText(
    content: String,
    node: ASTNode,
    referenceLinks: ReferenceLinkHandler,
    linkStyle: SpanStyle,
    imageTemplate: String,
): AnnotatedString? {
    val url = node.resolveImageLink(content, referenceLinks) ?: return null
    val label = imageLinkLabel(node.resolveImageAlt(content), url, imageTemplate)
    return buildAnnotatedString {
        // No listener: a tap goes to LocalUriHandler, which AssistantMarkdown routes to the dialog.
        withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) { append(label) }
    }
}

@Composable
fun AssistantMarkdown(text: String, modifier: Modifier = Modifier) {
    val platformHandler = LocalUriHandler.current
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    val dialogHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                pendingUrl = uri
            }
        }
    }

    val referenceLinks = remember { ReferenceLinkHandlerImpl() }
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    val imageTemplate = stringResource(R.string.link_image_label)
    val annotator = remember(linkStyle, imageTemplate) { safeMarkdownAnnotator(referenceLinks, linkStyle, imageTemplate) }
    val components = remember(referenceLinks, linkStyle, imageTemplate) {
        val imageComponent: MarkdownComponent = { model ->
            imageLinkText(model.content, model.node, referenceLinks, linkStyle, imageTemplate)?.let {
                Text(it, style = model.typography.text)
            }
        }
        markdownComponents(
            image = imageComponent,
            inlineImage = imageComponent,
            codeFence = { model -> CodeFence(model) },
            custom = { type, model ->
                // Raw HTML blocks show as literal source. Any other unhandled node type has nothing to show.
                if (type == MarkdownElementTypes.HTML_BLOCK) {
                    Text(model.node.getTextInNode(model.content).toString(), style = model.typography.text)
                }
            },
        )
    }

    val state = rememberMarkdownState(
        content = text,
        retainState = true,
        immediate = true,
        referenceLinkHandler = referenceLinks,
    )
    val textColor = LocalContentColor.current
    CompositionLocalProvider(LocalUriHandler provides dialogHandler) {
        Markdown(
            markdownState = state,
            colors = markdownColor(text = textColor, codeBackground = textColor.copy(alpha = 0.1f)),
            modifier = modifier,
            annotator = annotator,
            components = components,
        )
    }

    pendingUrl?.let { url ->
        LinkDialog(url = url, platformHandler = platformHandler, onDismiss = { pendingUrl = null })
    }
}

/** The library cuts the last content line off a fence that has no closing ```, so the end is found here. */
@Composable
private fun CodeFence(model: MarkdownComponentModel) {
    val children = model.node.children
    val first = children.indexOfFirst { it.type == MarkdownTokenTypes.EOL } + 1
    val last = children.indexOfLast { it.type != MarkdownTokenTypes.CODE_FENCE_END }
    if (first !in 1..last) return
    val code = model.content.substring(children[first].startOffset, children[last].endOffset).replaceIndent()
    val language = model.node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(model.content)?.toString()
    CodeBlock(code, language, model.typography.code)
}

@Composable
private fun CodeBlock(code: String, language: String?, style: TextStyle) {
    val shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize)
    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = shape,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = AnnotatedString(code),
            style = style,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

@Composable
private fun LinkDialog(url: String, platformHandler: UriHandler, onDismiss: () -> Unit) {
    val openable = openableUri(url)
    var failed by remember(url) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(url) }
                if (openable == null) {
                    Text(stringResource(R.string.link_cannot_open), color = MaterialTheme.colorScheme.error)
                } else if (failed) {
                    Text(stringResource(R.string.link_no_app), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (openable != null) {
                TextButton(onClick = {
                    try {
                        platformHandler.openUri(openable)
                        onDismiss()
                    } catch (_: Exception) {
                        failed = true
                    }
                }) { Text(stringResource(R.string.link_open)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.link_cancel)) } },
    )
}
