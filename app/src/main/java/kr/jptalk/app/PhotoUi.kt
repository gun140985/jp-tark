package kr.jptalk.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.min

private val R12 = RoundedCornerShape(12.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoScreen(s: AppState, sess: PhotoSession) {
    val c = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<PBlock?>(null) }
    BackHandler { s.closePhoto() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { s.closePhoto() }) { Text("← 닫기", fontSize = 15.sp) }
            Text("사진 번역", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { s.photoToChat() }, enabled = sess.blocks.isNotEmpty() && !sess.processing) {
                Text("대화에 넣기", fontSize = 15.sp)
            }
        }

        // 사진 (두 손가락으로 확대·이동, 번호 상자를 누르면 해당 결과로 이동)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(0.42f).background(c.surface).clipToBounds()
        ) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val imgW = sess.image.width.toFloat()
            val imgH = sess.image.height.toFloat()
            val k = min(boxW / imgW, boxH / imgH)
            val dx = (boxW - imgW * k) / 2f
            val dy = (boxH - imgH * k) / 2f
            val accent = c.primary
            val labelBg = c.primary.toArgb()
            val labelFg = c.onPrimary.toArgb()

            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .pointerInput(sess.blocks.size, boxW, boxH) {
                        detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) { pos ->
                            // 화면 좌표 → 사진 좌표
                            val cx = boxW / 2f; val cy = boxH / 2f
                            val px = (pos.x - offset.x - cx) / scale + cx
                            val py = (pos.y - offset.y - cy) / scale + cy
                            val ix = (px - dx) / k; val iy = (py - dy) / k
                            val hit = sess.blocks.indexOfFirst { b -> b.rect?.contains(ix.toInt(), iy.toInt()) == true }
                            if (hit >= 0) {
                                selected = sess.blocks[hit].no
                                scope.launch { listState.animateScrollToItem(hit) }
                            }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            ) {
                Image(sess.image, contentDescription = "찍은 사진", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val paintBg = android.graphics.Paint().apply { color = labelBg; isAntiAlias = true }
                    val paintFg = android.graphics.Paint().apply {
                        color = labelFg; textSize = 13.sp.toPx(); isAntiAlias = true; isFakeBoldText = true
                    }
                    sess.blocks.forEach { b ->
                        val r = b.rect ?: return@forEach
                        val left = dx + r.left * k; val top = dy + r.top * k
                        val w = r.width() * k; val h = r.height() * k
                        drawRect(
                            color = accent,
                            topLeft = Offset(left, top), size = Size(w, h),
                            style = Stroke(width = (if (b.no == selected) 3.dp else 1.5.dp).toPx())
                        )
                        val label = b.no.toString()
                        val tw = paintFg.measureText(label) + 10.dp.toPx()
                        val th = 18.dp.toPx()
                        drawContext.canvas.nativeCanvas.apply {
                            drawRoundRect(left, top - th, left + tw, top, 6f, 6f, paintBg)
                            drawText(label, left + 5.dp.toPx(), top - 5.dp.toPx(), paintFg)
                        }
                    }
                }
            }
        }

        if (sess.processing) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (sess.blocks.isEmpty()) "글자를 읽는 중…" else "번역하는 중… (${sess.blocks.size}개 완료)", fontSize = 15.sp)
            }
        }
        sess.error?.let { Banner(it) }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(0.58f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sess.blocks, key = { it.no }) { b ->
                BlockCard(b, s, selected = b.no == selected, onEdit = { editing = b })
            }
        }
    }

    editing?.let { b ->
        var text by remember(b.no) { mutableStateOf(b.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${b.no}번 글자 고치기") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("사진에서 잘못 읽은 글자를 고친 뒤 '다시 번역'을 누르세요. 줄은 그대로 두어도 됩니다.", fontSize = 14.sp)
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { s.editBlock(b, text); editing = null }) { Text("다시 번역") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockCard(b: PBlock, s: AppState, selected: Boolean, onEdit: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = R12, color = c.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.background(c.primary, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp)
                ) { Text(b.no.toString(), color = c.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (b.kind == BlockKind.PROSE) "문장 (문단으로 번역)" else "목록 (줄별 번역)",
                    fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { s.toggleKind(b) }) {
                    Text(if (b.kind == BlockKind.PROSE) "줄별로 보기" else "문단으로 보기", fontSize = 13.sp)
                }
            }

            if (b.kind == BlockKind.PROSE) {
                Text(b.proseKo, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { s.speakKo(b.proseKo) }) { Text("한국어로 듣기") }
                    OutlinedButton(onClick = { s.speakJa(PhotoText.joinLines(b.text)) }) { Text("일본어로 읽기") }
                    OutlinedButton(onClick = { s.toggleExpand(b) }) { Text(if (b.expanded) "원문·발음 접기" else "원문·발음 보기") }
                    TextButton(onClick = onEdit) { Text("수정") }
                }
                if (b.expanded) {
                    HorizontalDivider(color = c.outline)
                    b.sentences.forEach { st ->
                        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(st.ja, fontSize = 16.sp)
                            Text(st.pron, fontSize = 14.sp, color = c.onSurfaceVariant)
                            Text(st.ko, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                b.rows.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = c.outline)
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.ja, fontSize = 16.sp)
                        Text(r.pron, fontSize = 14.sp, color = c.onSurfaceVariant)
                        Text(r.ko, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                        Row {
                            TextButton(onClick = { s.speakJa(r.ja) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("일본어로 읽기", fontSize = 13.sp) }
                            TextButton(onClick = { s.speakKo(r.ko) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("한국어로 듣기", fontSize = 13.sp) }
                        }
                    }
                }
                TextButton(onClick = onEdit) { Text("수정") }
            }
        }
    }
}

@Composable
fun PhotoChoiceDialog(onCamera: () -> Unit, onGallery: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("사진 번역") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("일본어 메뉴판, 안내문, 라벨을 찍으면 한국어 번역과 한글 발음으로 보여줍니다. 글자가 화면에 꽉 차게, 밝은 곳에서 찍어 주세요.", fontSize = 14.sp, lineHeight = 21.sp)
                Button(onClick = onCamera, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = R12) { Text("카메라로 찍기", fontSize = 16.sp) }
                OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = R12) { Text("갤러리에서 고르기", fontSize = 16.sp) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("취소") } }
    )
}
