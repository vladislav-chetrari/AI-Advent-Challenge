package desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography

// Компактная типографика для чата: база — 14sp (как обычный Text в пузыре).
// Дефолт библиотеки — displayLarge/Medium (~45-57sp), поэтому заголовки выглядели огромными.
// Шкала: h1 20 → h2 18 → h3 16 → h4 15 → h5/h6 14/13, всё Bold.
@Composable
fun chatMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    h2 = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    h3 = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    h4 = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    h5 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    h6 = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
    text = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    paragraph = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    ordered = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bullet = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    list = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    quote = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontStyle = FontStyle.Italic),
    code = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace),
    inlineCode = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace),
    table = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)
