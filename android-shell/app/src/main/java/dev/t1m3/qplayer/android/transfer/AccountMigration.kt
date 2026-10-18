package dev.t1m3.qplayer.android.transfer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.store.AccountTransfer
import kotlinx.coroutines.*
import java.security.GeneralSecurityException

/** Explicit SAF handoff: neither cookies nor the transfer password enter an Intent or clipboard. */
@Composable
fun AccountMigrationCard(exportAccounts: (() -> AccountTransfer.Accounts)? = null,
    importAccounts: (suspend (AccountTransfer.Accounts) -> String)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { pendingPassword?.fill('\u0000') } }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val secret = pendingPassword
        pendingPassword = null
        if (uri == null || secret == null) { secret?.fill('\u0000'); busy = false }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val accounts = requireNotNull(exportAccounts).invoke()
                    val encrypted = AccountTransfer.encrypt(accounts, secret)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) }
                        ?: error("无法写入迁移文件")
                }
                status = "已导出加密登录文件。请在新版「设置 → 关于 → 账号迁移」中导入。"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "导出失败：请确认旧版已登录，并检查文件是否可写。" }
            finally { secret.fill('\u0000'); busy = false }
        }
    }
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
        if (uri != null) { password = ""; mode = "import" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("账号迁移", style = MaterialTheme.typography.titleMedium)
            Text("迁移网易云和 B 站登录状态。迁移文件包含登录凭据，使用独立口令加密，请勿分享。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exportAccounts != null) OutlinedButton(enabled = !busy, onClick = {
                    password = ""; repeatPassword = ""; status = ""; mode = "export"
                }) { Text("导出登录信息") }
                if (importAccounts != null) Button(enabled = !busy, onClick = { chooseFile.launch(arrayOf("*/*")) }) {
                    Text("导入登录信息")
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (mode.isNotEmpty()) AlertDialog(onDismissRequest = {
        mode = ""; password = ""; repeatPassword = ""; importUri = null
    }, title = { Text(if (mode == "export") "设置迁移口令" else "导入旧版登录信息") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (mode == "export") "输入至少 8 个字符的口令，导入时需要同一口令。"
                else "输入导出时设置的口令。文件中的账号会替换对应平台的当前登录，其他平台保持原样。")
            OutlinedTextField(password, { if (it.length <= 256) password = it }, label = { Text("迁移口令") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (mode == "export") OutlinedTextField(repeatPassword, { if (it.length <= 256) repeatPassword = it },
                label = { Text("再次输入口令") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
    }, confirmButton = { TextButton(enabled = password.length >= 8 && (mode != "export" || password == repeatPassword),
        onClick = {
            val secret = password.toCharArray()
            val exporting = mode == "export"
            mode = ""; password = ""; repeatPassword = ""; status = ""; busy = true
            if (exporting) {
                pendingPassword = secret
                try { createFile.launch("QPlayer-accounts.qpa") }
                catch (_: Exception) { pendingPassword = null; secret.fill('\u0000'); busy = false; status = "无法打开文件保存窗口" }
            } else {
                val uri = importUri
                importUri = null
                scope.launch {
                    try {
                        val accounts = withContext(Dispatchers.IO) {
                            val input = uri?.let { context.contentResolver.openInputStream(it) } ?: error("无法读取文件")
                            val bytes = input.use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (out.size() + count > AccountTransfer.MAX_FILE_BYTES) error("文件过大")
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                            AccountTransfer.decrypt(bytes, secret)
                        }
                        status = requireNotNull(importAccounts).invoke(accounts)
                    } catch (e: CancellationException) { throw e }
                    catch (_: GeneralSecurityException) { status = "口令不正确或迁移文件已损坏，当前登录未改变。" }
                    catch (_: Exception) { status = "无法导入：请选择有效的 QPlayer 账号迁移文件，并检查网络连接。" }
                    finally { secret.fill('\u0000'); busy = false }
                }
            }
        }) { Text(if (mode == "export") "选择保存位置" else "验证并导入") }
    }, dismissButton = { TextButton(onClick = { mode = ""; password = ""; repeatPassword = ""; importUri = null }) { Text("取消") } })
}
