package kr.jptalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(applicationContext, lifecycleScope)
        setContent { JpTheme { App(state) } }
    }

    override fun onDestroy() {
        state.shutdown()
        super.onDestroy()
    }
}

// ---------- 색 ----------
private val LightColors = lightColorScheme(
    primary = Color(0xFF0E6A87), onPrimary = Color.White,
    background = Color(0xFFF2F5F6), onBackground = Color(0xFF14202A),
    surface = Color(0xFFFBFCFC), onSurface = Color(0xFF14202A),
    surfaceVariant = Color(0xFFE2EFF3), onSurfaceVariant = Color(0xFF566470),
    outline = Color(0xFFD9E1E5), error = Color(0xFF9A4A12), errorContainer = Color(0xFFFBEFE4),
    onErrorContainer = Color(0xFF9A4A12)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF58B6D3), onPrimary = Color(0xFF04212B),
    background = Color(0xFF0E1418), onBackground = Color(0xFFE6EDF0),
    surface = Color(0xFF162027), onSurface = Color(0xFFE6EDF0),
    surfaceVariant = Color(0xFF123039), onSurfaceVariant = Color(0xFF9AA9B3),
    outline = Color(0xFF27343C), error = Color(0xFFF0B183), errorContainer = Color(0xFF33231A),
    onErrorContainer = Color(0xFFF0B183)
)

@Composable
fun JpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

private val R14 = RoundedCornerShape(14.dp)

// ---------- 화면 ----------
@Composable
fun App(s: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true   // 대화 중 화면 꺼짐 방지
        onDispose { view.keepScreenOn = false }
    }

    var showHelp by remember { mutableStateOf(s.firstRun) }
    var showSettings by remember { mutableStateOf(false) }
    var showInput by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Mode?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = pending
        pending = null
        if (ok && m != null) s.tap(m)
        if (!ok) s.notice = "마이크 권한을 허용해야 말을 들을 수 있습니다. 직접 입력은 위의 '입력'으로 할 수 있습니다."
    }
    fun listen(m: Mode) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) s.tap(m)
        else { pending = m; permLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    var showPhotoChoice by remember { mutableStateOf(false) }
    val shotFile = remember { File(ctx.cacheDir, "photos/shot.jpg").also { it.parentFile?.mkdirs() } }
    val shotUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shotFile) }
    val takePic = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) s.openPhoto(shotUri, shotFile) else shotFile.delete()
    }
    val pickPic = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) s.openPhoto(uri)
    }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.toast) {
        s.toast?.let { snack.showSnackbar(it); s.toast = null }
    }

    val photoSess = s.photo
    if (photoSess != null) PhotoScreen(s, photoSess) else Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // 상단
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("한일 통역", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showInput = true }) { Text("입력", fontSize = 14.sp) }
                TextButton(onClick = { showPhotoChoice = true }) { Text("사진", fontSize = 14.sp) }
                TextButton(onClick = { s.newConversation() }) { Text("새 대화", fontSize = 14.sp) }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "설정") }
            }
            Text(
                s.routeText(), fontSize = 13.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider(Modifier.padding(top = 8.dp), color = colors.outline)

            // 안내 띠
            if (s.tr.downloading) Banner("번역 데이터를 내려받는 중입니다 (처음 1번, 와이파이 권장)")
            s.tr.error?.let { e ->
                if (!s.tr.downloading) Banner(e, action = "다시 시도") { s.retryDownload() }
            }
            if (s.jaVoiceMissing) Banner("일본어 읽기 음성이 없습니다. 설치하면 일본어를 소리로 들려줄 수 있습니다.", action = "설치하기") { s.installVoices() }
            s.notice?.let { Banner(it, action = "닫기") { s.notice = null } }

            // 대화 기록
            val listState = rememberLazyListState()
            LaunchedEffect(s.messages.size, s.messages.lastOrNull()?.state) {
                if (s.messages.isNotEmpty()) listState.animateScrollToItem(s.messages.size - 1)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.messages.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("아래 버튼을 눌러 대화를 시작하세요", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "상대 말은 휴대폰을 상대 쪽으로 내밀고 '상대 말 듣기'를,\n내 말은 '내 말 하기'를 누르고 한국어로 말하면 됩니다.",
                            fontSize = 14.sp, color = colors.onSurfaceVariant, lineHeight = 21.sp
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(s.messages, key = { it.id }) { m -> Bubble(m, s) }
                }
            }

            // 아래 영역
            Column(
                Modifier.fillMaxWidth().background(colors.background).padding(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val mode = s.listening
                if (mode != null && mode != Mode.ADD) ListenPanel(mode, s)

                // 자주 쓰는 말
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("자주 쓰는 말", fontSize = 14.sp, color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (s.custom.isNotEmpty()) {
                        TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집", fontSize = 14.sp) }
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.custom, key = { "c" + it.label + it.ja }) { p ->
                        Chip(if (editing) "× ${p.label}" else p.label, warn = editing) {
                            if (editing) { s.removePhrase(p); if (s.custom.isEmpty()) editing = false } else s.usePhrase(p)
                        }
                    }
                    items(BASE_PHRASES, key = { "b" + it.label }) { p -> Chip(p.label) { s.usePhrase(p) } }
                    item { Chip("+ 추가", accent = true) { editing = false; showAdd = true } }
                }

                // 큰 버튼 2개
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(
                        title = if (mode == Mode.THEM) "듣는 중 · 누르면 멈춤" else "상대 말 듣기",
                        sub = "일본어 → 한국어",
                        bg = colors.onBackground, fg = colors.background,
                        active = mode == Mode.THEM, modifier = Modifier.weight(1f)
                    ) { listen(Mode.THEM) }
                    BigButton(
                        title = if (mode == Mode.ME) "듣는 중 · 누르면 멈춤" else "내 말 하기",
                        sub = "한국어 → 일본어",
                        bg = colors.primary, fg = colors.onPrimary,
                        active = mode == Mode.ME, modifier = Modifier.weight(1f)
                    ) { listen(Mode.ME) }
                }
            }
        }
    }

    if (showPhotoChoice) PhotoChoiceDialog(
        onCamera = {
            showPhotoChoice = false
            try { takePic.launch(shotUri) } catch (e: Exception) { s.toast = "카메라 앱을 열 수 없습니다. 갤러리에서 골라 주세요." }
        },
        onGallery = {
            showPhotoChoice = false
            pickPic.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onClose = { showPhotoChoice = false }
    )
    if (showHelp) HelpDialog { showHelp = false; s.firstRun = false }
    if (showSettings) SettingsDialog(s, onHelp = { showSettings = false; showHelp = true }) { showSettings = false }
    if (showInput) InputDialog(s) { showInput = false }
    if (showAdd) AddDialog(s, onListen = { listen(Mode.ADD) }) {
        if (s.listening == Mode.ADD) s.cancelListening()
        showAdd = false
    }
}

@Composable
fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(c.errorContainer, R14).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = c.onErrorContainer, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = c.onErrorContainer, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun ListenPanel(mode: Mode, s: AppState) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(c.surface, R14).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (mode == Mode.THEM) "상대방 말을 듣는 중 (일본어)" else "내 말을 듣는 중 (한국어)",
            fontSize = 14.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold
        )
        Text(s.partial.ifBlank { "말씀하세요…" }, fontSize = 17.sp)
        LinearProgressIndicator(
            progress = { s.level },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = c.primary, trackColor = c.outline
        )
    }
}

@Composable
fun Bubble(m: Msg, s: AppState) {
    val c = MaterialTheme.colorScheme
    if (m.side == Side.PHOTO) { PhotoBubble(m, s); return }
    val mine = m.side == Side.ME
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = R14,
            color = if (mine) c.surfaceVariant else c.surface,
            border = if (mine) null else BorderStroke(1.dp, c.outline),
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (mine) "나" else "상대방", fontSize = 13.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                when (m.state) {
                    MsgState.PENDING -> {
                        Text(m.src, fontSize = if (mine) 15.sp else 17.sp, color = if (mine) c.onSurfaceVariant else c.onSurface)
                        Text("번역 중…", fontSize = 15.sp, color = c.onSurfaceVariant)
                    }
                    MsgState.ERROR -> {
                        Text(m.src, fontSize = 15.sp)
                        Text(m.error, fontSize = 15.sp, color = c.error)
                        OutlinedButton(onClick = { s.retry(m) }) { Text("다시 번역") }
                    }
                    MsgState.DONE -> if (!mine) {
                        Text(m.ja, fontSize = 17.sp)
                        Text(m.pron, fontSize = 15.sp, color = c.onSurfaceVariant)
                        Text(m.ko, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                        OutlinedButton(onClick = { s.speakKo(m.ko) }, modifier = Modifier.padding(top = 4.dp)) { Text("한국어로 다시 듣기") }
                    } else {
                        Text(m.ko, fontSize = 15.sp, color = c.onSurfaceVariant)
                        Text(m.ja, fontSize = 17.sp)
                        Text(m.pron, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                        OutlinedButton(onClick = { s.speakJa(m.ja) }, modifier = Modifier.padding(top = 4.dp)) { Text("일본어로 읽기") }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoBubble(m: Msg, s: AppState) {
    val c = MaterialTheme.colorScheme
    Surface(shape = R14, color = c.surface, border = BorderStroke(1.dp, c.outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("사진 번역", fontSize = 13.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            m.thumb?.let {
                Image(it, contentDescription = "번역한 사진", contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 140.dp).fillMaxWidth())
            }
            Text(m.ko, fontSize = 17.sp, lineHeight = 26.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { s.speakKo(m.ko) }) { Text("한국어로 듣기") }
                OutlinedButton(onClick = { s.speakJa(m.ja) }) { Text("일본어로 읽기") }
            }
        }
    }
}

@Composable
fun Chip(text: String, accent: Boolean = false, warn: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (accent) c.primary else if (warn) c.error else c.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = c.surface,
            contentColor = if (accent) c.primary else if (warn) c.error else c.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.heightIn(min = 44.dp)
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun BigButton(title: String, sub: String, bg: Color, fg: Color, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        shape = R14,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        border = if (active) BorderStroke(3.dp, if (bg == c.primary) c.onBackground else c.primary) else null,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.heightIn(min = 84.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 24.sp)
            Text(sub, fontSize = 14.sp)
        }
    }
}

// ---------- 대화상자 ----------
@Composable
fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("확인") } },
        title = { Text("사용법") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. 상대 말 듣기: 버튼을 누르고 휴대폰을 상대 쪽으로 내미세요. 말이 끝나면 자동으로 멈추고 한국어로 번역해 읽어줍니다.")
                Text("2. 내 말 하기: 버튼을 누르고 한국어로 말하면 일본어와 큰 한글 발음이 나옵니다. 보고 읽거나 '일본어로 읽기'를 눌러 들려주세요.")
                Text("3. 에어팟·이어폰은 '듣는 용'으로 쓰세요. 상대 목소리는 휴대폰 본체 마이크가 더 정확합니다 (설정에서 바꿀 수 있음).")
                Text("4. 처음 한 번은 번역 데이터를 내려받느라 인터넷이 필요합니다. 그다음부터 번역은 인터넷 없이도 됩니다. 음성인식은 인터넷이 필요합니다.")
                Text("5. 자주 쓰는 말은 누르면 바로 나오고, '+ 추가'로 내 문장을 저장할 수 있습니다.")
                Text("6. 사진: 위의 '사진'을 눌러 일본어 메뉴판·안내문을 찍으면 번역과 한글 발음이 나옵니다. 긴 글은 문단째 자연스럽게 번역하고, '원문·발음 보기'로 문장별 원문을 볼 수 있습니다.")
                Text("7. 사용법은 설정(톱니바퀴) 안에서 다시 볼 수 있습니다.")
            }
        }
    )
}

@Composable
fun InputDialog(s: AppState, onClose: () -> Unit) {
    var side by remember { mutableStateOf(Side.ME) }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("글자로 입력") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioRow("내 말 (한국어 → 일본어)", side == Side.ME) { side = Side.ME }
                RadioRow("상대 말 (일본어 → 한국어)", side == Side.THEM) { side = Side.THEM }
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(if (side == Side.ME) "한국어" else "일본어") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) { s.translate(side, text.trim()); onClose() }
            }) { Text("통역") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("닫기") } }
    )
}

@Composable
fun AddDialog(s: AppState, onListen: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("자주 쓰는 말 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("저장할 문장을 한국어로 입력하거나 말로 입력하세요. 일본어로 바꿔 저장합니다.", fontSize = 14.sp)
                OutlinedTextField(
                    value = s.addText, onValueChange = { s.addText = it },
                    label = { Text("한국어 문장") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
                OutlinedButton(onClick = onListen, enabled = s.listening == null) {
                    Text(if (s.listening == Mode.ADD) "듣는 중…" else "말로 입력")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { s.savePhrase(s.addText, onClose) }, enabled = !s.saving && s.addText.isNotBlank()) {
                Text(if (s.saving) "저장 중…" else "저장")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("취소") } }
    )
}

@Composable
fun SettingsDialog(s: AppState, onHelp: () -> Unit, onClose: () -> Unit) {
    val st = s.settings
    val outLabels = listOf(
        Out.EARPHONE to "이어폰 (없으면 스피커)",
        Out.SPEAKER to "휴대폰 스피커",
        Out.AUTO to "자동"
    )
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("닫기") } },
        title = { Text("마이크·스피커 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("사용법 보기") }
                Section("마이크")
                RadioRow("휴대폰 본체 마이크 (추천)", !st.btMic) { s.updateSettings(st.copy(btMic = false)) }
                RadioRow("이어폰·블루투스 마이크", st.btMic) { s.updateSettings(st.copy(btMic = true)) }
                Text("에어팟 마이크는 내 목소리만 잘 잡습니다. 블루투스 마이크를 쓰면 소리 품질이 통화 수준으로 떨어집니다.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Section("상대 말 번역(한국어) 소리")
                outLabels.forEach { (o, l) -> RadioRow(l, st.outThem == o) { s.updateSettings(st.copy(outThem = o)) } }

                Section("내 말 일본어 소리")
                outLabels.forEach { (o, l) -> RadioRow(l, st.outMe == o) { s.updateSettings(st.copy(outMe = o)) } }

                Section("한국어 읽는 속도")
                RATE_LABELS.forEachIndexed { i, l -> RadioRow(l, st.rateKo == i) { s.updateSettings(st.copy(rateKo = i)) } }
                Section("일본어 읽는 속도")
                RATE_LABELS.forEachIndexed { i, l -> RadioRow(l, st.rateJa == i) { s.updateSettings(st.copy(rateJa = i)) } }

                Section("자동 읽기")
                SwitchRow("상대 말 번역을 자동으로 읽기", st.autoRead) { s.updateSettings(st.copy(autoRead = it)) }
                SwitchRow("자주 쓰는 말을 누르면 일본어로 읽기", st.phraseRead) { s.updateSettings(st.copy(phraseRead = it)) }
            }
        }
    )
}

@Composable
fun Section(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

@Composable
fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
