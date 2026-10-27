package com.bcctesda.attendancesummary

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bcctesda.attendancesummary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/* ================= Reusable trainee form (add + edit) ================= */
@Composable
fun TraineeForm(initial: Trainee?, classId: Int, submitLabel: String, onSubmit: (Trainee) -> Unit) {
    var last by remember { mutableStateOf(initial?.lastName ?: "") }
    var first by remember { mutableStateOf(initial?.firstName ?: "") }
    var middle by remember { mutableStateOf(initial?.middleName ?: "") }
    var birthday by remember { mutableStateOf(initial?.birthday ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var photo by remember { mutableStateOf(initial?.photoPath) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) { savePhoto(ctx, uri) }
            if (saved == null) {
                Toast.makeText(ctx, "Couldn't use that image.", Toast.LENGTH_SHORT).show()
            } else {
                photo?.takeIf { it != initial?.photoPath }?.let { File(it).delete() }
                photo = saved
            }
        }
    }
    val birthdayValid = birthday.isBlank() || runCatching { LocalDate.parse(birthday.trim()) }.isSuccess

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(photo, 96.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (photo == null) "Add photo" else "Change photo") }
                if (photo != null) TextButton(onClick = { photo = null }) { Text("Remove photo") }
            }
        }
        OutlinedTextField(last, { last = it }, label = @Composable { Text("Last name *") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(first, { first = it }, label = @Composable { Text("First name *") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(middle, { middle = it }, label = @Composable { Text("Middle name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(birthday, { birthday = it }, label = @Composable { Text("Birthday (YYYY-MM-DD, optional)") },
            isError = !birthdayValid, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = @Composable { Text("Email (optional)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(phone, { phone = it }, label = @Composable { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(address, { address = it }, label = @Composable { Text("Address (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = last.isNotBlank() && first.isNotBlank() && birthdayValid, onClick = {
            onSubmit((initial ?: Trainee(classId = classId, lastName = "", firstName = "")).copy(
                lastName = last.trim().uppercase(), firstName = first.trim().uppercase(),
                middleName = middle.trim().uppercase(),
                birthday = birthday.trim().ifBlank { null }, email = email.trim().ifBlank { null },
                phone = phone.trim().ifBlank { null }, address = address.trim().ifBlank { null },
                photoPath = photo))
        }) { Text(submitLabel) }
    }
}

/* ================= Edit screen ================= */
@Composable
fun EditTraineeScreen(vm: AttendanceViewModel, id: Int, onBack: () -> Unit) {
    val t by remember(id) { vm.trainee(id) }.collectAsState(null)
    Page("Edit trainee", onBack) { pad ->
        val trainee = t ?: return@Page
        Column(modifier = Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            key(trainee.id) {
                TraineeForm(trainee, trainee.classId, "Save changes") { updated ->
                    if (updated.photoPath != trainee.photoPath) trainee.photoPath?.let { File(it).delete() }
                    vm.updateTrainee(updated); onBack()
                }
            }
        }
    }
}
