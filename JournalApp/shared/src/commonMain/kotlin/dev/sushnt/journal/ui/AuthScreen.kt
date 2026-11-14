package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/*
 * Sign in / create account (design: "Sign in & create account" on the canvas).
 *  - Wide windows (desktop): one centred card.
 *  - Phones: a welcome screen with the logo and a bottom sheet (Google / email);
 *    "Continue with email" opens the email form in the sheet.
 * Forgot password uses the same card / sheet.
 */

private val SheetColor = Color(0xFF10152A)
private val FieldColor = JournalColors.Card
private val ButtonLight = Color(0xFFF2F3F8)

/** Phone flow: the welcome sheet, or the email form. */
private enum class PhoneStep { Welcome, Email }

@Composable
fun AuthScreen(vm: AuthViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    // Kept up here so what you typed survives switching between steps and layouts.
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val form = FormValues(email, password, name, { email = it }, { password = it }, { name = it })
    val google = rememberGoogleAction(vm)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(JournalColors.Background)
            .topGlow()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (maxWidth >= 640.dp) DesktopAuth(vm, state, form, google) else PhoneAuth(vm, state, form, google)
    }
}

/** What's typed in the form, plus how to change it. */
private class FormValues(
    val email: String,
    val password: String,
    val name: String,
    val onEmail: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onName: (String) -> Unit,
)

// ---------------------------------------------------------------- desktop

@Composable
private fun DesktopAuth(vm: AuthViewModel, state: AuthUiState, form: FormValues, google: GoogleAction?) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .shadow(40.dp, shape)
                .clip(shape)
                .background(SheetColor)
                .border(1.dp, JournalColors.BorderStrong, shape)
                .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LogoTile(64.dp, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(4.dp))
            AnimatedContent((state.resetStep == null) to state.isSignup, label = "auth card") { (normal, signup) ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (normal) {
                        Title(if (signup) "Start your journal" else "Welcome back", centered = true)
                        SwitchModeLine(signup, vm, centered = true, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
                        if (google != null) {
                            GoogleButton(google, signup, state.loading)
                            OrDivider(if (signup) "or with email" else "or sign in with email")
                        }
                        CredentialFields(vm, state, form, signup)
                        Messages(state)
                        PrimaryButton(if (signup) "Create account" else "Sign in", state.loading) {
                            vm.submit(form.email, form.password, form.name)
                        }
                        Terms()
                    } else {
                        ResetContent(vm, state, form, centered = true)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- phone

@Composable
private fun PhoneAuth(vm: AuthViewModel, state: AuthUiState, form: FormValues, google: GoogleAction?) {
    var step by rememberSaveable { mutableStateOf(PhoneStep.Welcome) }
    val inForm = step == PhoneStep.Email || state.resetStep != null
    PlatformBackHandler(enabled = inForm) {
        if (state.resetStep != null) vm.cancelReset() else step = PhoneStep.Welcome
    }

    Column(Modifier.fillMaxSize()) {
        // Top: the logo, big on the welcome screen, smaller in the form (more room for fields).
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (inForm) {
                Box(
                    Modifier
                        .padding(14.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(JournalColors.Raised)
                        .clickable(role = Role.Button) {
                            if (state.resetStep != null) vm.cancelReset() else step = PhoneStep.Welcome
                        }
                        .semantics { contentDescription = "Back" },
                    contentAlignment = Alignment.Center,
                ) { LineIcon(Glyph.Back, JournalColors.Text) }
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                LogoTile(if (inForm) 64.dp else 84.dp)
                AnimatedVisibility(!inForm, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
                    Text(
                        "Journal",
                        color = JournalColors.TextStrong,
                        fontFamily = writingFont(true),
                        fontSize = 26.sp,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }

        // Bottom sheet
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(30.dp, shape)
                .clip(shape)
                .background(SheetColor)
                .border(1.dp, JournalColors.BorderStrong, shape)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp)
                    .size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Color(0xFF2A3147)),
            )
            AnimatedContent(Triple(step, state.resetStep == null, state.isSignup), label = "auth sheet") { (shownStep, normal, signup) ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        !normal -> ResetContent(vm, state, form, centered = false)

                        shownStep == PhoneStep.Welcome -> {
                            Title("Write first. Sync later.", centered = true)
                            Text(
                                "Sign in to keep your journal safe\nand on every device.",
                                color = JournalColors.Muted,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            )
                            if (google != null) GoogleButton(google, signup = false, loading = state.loading)
                            Messages(state)
                            PrimaryButton("Continue with email", false, glyph = Glyph.Mail) {
                                if (signup) vm.toggleMode()
                                step = PhoneStep.Email
                            }
                            LinkLine("New here? ", "Create an account", centered = true) {
                                if (!signup) vm.toggleMode()
                                step = PhoneStep.Email
                            }
                            Terms()
                        }

                        else -> {
                            Title(if (signup) "Start your journal" else "Welcome back", centered = false)
                            Text(
                                if (signup) "Takes a few seconds. Free." else "Sign in with your email.",
                                color = JournalColors.Muted,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            CredentialFields(vm, state, form, signup)
                            Messages(state)
                            PrimaryButton(if (signup) "Create account" else "Sign in", state.loading) {
                                vm.submit(form.email, form.password, form.name)
                            }
                            if (google != null) {
                                OrDivider("or")
                                GoogleButton(google, signup, state.loading)
                            }
                            SwitchModeLine(signup, vm, centered = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared form pieces

@Composable
private fun CredentialFields(vm: AuthViewModel, state: AuthUiState, form: FormValues, signup: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (signup) AuthField(form.name, form.onName, "Your name (optional)", Glyph.Person, KeyboardType.Text)
        AuthField(form.email, form.onEmail, "Email", Glyph.Mail, KeyboardType.Email)
        AuthField(
            form.password,
            form.onPassword,
            if (signup) "Create a password" else "Password",
            Glyph.Lock,
            KeyboardType.Password,
            isPassword = true,
            hint = if (signup) "At least 8 characters" else null,
        )
        if (!signup) {
            Text(
                "Forgot password?",
                color = JournalColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !state.loading, role = Role.Button, onClick = vm::startReset)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ResetContent(vm: AuthViewModel, state: AuthUiState, form: FormValues, centered: Boolean) {
    var code by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    PlatformBackHandler(enabled = true, onBack = vm::cancelReset)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.resetStep) {
            ResetStep.EnterCode -> {
                Title("Check your email", centered)
                Messages(state)
                AuthField(code, { input -> code = input.filter { it.isDigit() }.take(6) }, "6-digit code", Glyph.Mail, KeyboardType.NumberPassword)
                AuthField(
                    newPassword, { newPassword = it }, "New password", Glyph.Lock, KeyboardType.Password,
                    isPassword = true, hint = "At least 8 characters. Other devices will be signed out.",
                )
                PrimaryButton("Set new password", state.loading) { vm.submitReset(form.email, code, newPassword) }
                LinkLine("Didn't get it? ", "Send a new code", centered = true) { vm.sendResetCode(form.email) }
            }
            else -> {
                Title("Reset password", centered)
                Text(
                    "Enter your account's email and we'll send you a 6-digit code.",
                    color = JournalColors.Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                AuthField(form.email, form.onEmail, "Email", Glyph.Mail, KeyboardType.Email)
                Messages(state)
                PrimaryButton("Send code", state.loading) { vm.sendResetCode(form.email) }
            }
        }
        LinkLine("", "Back to sign in", centered = true, onClick = vm::cancelReset)
    }
}

@Composable
private fun Title(text: String, centered: Boolean) {
    Text(
        text,
        color = JournalColors.TextStrong,
        fontFamily = writingFont(true),
        fontSize = 28.sp,
        fontWeight = FontWeight.Medium,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchModeLine(signup: Boolean, vm: AuthViewModel, centered: Boolean, modifier: Modifier = Modifier) {
    if (signup) LinkLine("Already have an account? ", "Sign in", centered, modifier, vm::toggleMode)
    else LinkLine("New here? ", "Create an account", centered, modifier, vm::toggleMode)
}

/** "New here? Create an account": grey text with a violet link at the end. */
@Composable
private fun LinkLine(
    lead: String,
    link: String,
    centered: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Box(modifier, contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
        Text(
            buildAnnotatedString {
                append(lead)
                withStyle(SpanStyle(color = Color(0xFFC4B5FD), fontWeight = FontWeight.SemiBold)) { append(link) }
            },
            color = JournalColors.Muted,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

/** A dark rounded field with an icon in front; passwords get a show/hide eye. */
@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    glyph: Glyph,
    keyboard: KeyboardType,
    isPassword: Boolean = false,
    hint: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = JournalColors.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(JournalColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            decorationBox = { field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(shape)
                        .background(FieldColor)
                        .border(1.dp, JournalColors.BorderStrong, shape)
                        .padding(start = 14.dp, end = if (isPassword) 4.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineIcon(glyph, JournalColors.Muted, size = 17.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, color = Color(0xFF6B7389), fontSize = 15.sp)
                        field()
                    }
                    if (isPassword) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) { visible = !visible }
                                .semantics { contentDescription = if (visible) "Hide password" else "Show password" },
                            contentAlignment = Alignment.Center,
                        ) { LineIcon(if (visible) Glyph.EyeOff else Glyph.Eye, JournalColors.Muted, size = 18.dp) }
                    }
                }
            },
        )
        if (hint != null) {
            Text(hint, color = Color(0xFF6B7389), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

/** The main action: a light button with dark text. */
@Composable
private fun PrimaryButton(text: String, loading: Boolean, glyph: Glyph? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(ButtonLight)
            .clickable(enabled = !loading, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = JournalColors.Background, strokeWidth = 2.dp)
        } else {
            if (glyph != null) {
                LineIcon(glyph, JournalColors.Background, size = 17.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = JournalColors.Background, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun OrDivider(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(JournalColors.Border))
        Text(text, color = Color(0xFF6B7389), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(JournalColors.Border))
    }
}

@Composable
private fun Terms() {
    Text(
        "By continuing you agree to our Terms and Privacy Policy.\nYour entries stay private to your account.",
        color = Color(0xFF6B7389),
        fontSize = 11.5.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
private fun Messages(state: AuthUiState) {
    state.info?.let { Text(it, color = JournalColors.Preview, fontSize = 13.sp, lineHeight = 19.sp) }
    state.error?.let { Text(it, color = JournalColors.Danger, fontSize = 13.sp, lineHeight = 19.sp) }
}

/** The logo on a soft glowing tile. */
@Composable
private fun LogoTile(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF231B45), Color(0xFF0F1222))))
            .background(Brush.radialGradient(listOf(JournalColors.Accent.copy(alpha = 0.28f), Color.Transparent)))
            .border(1.dp, Color(0xFF2C2558), shape),
        contentAlignment = Alignment.Center,
    ) { BrandMark(size * 0.62f) }
}

// ---------------------------------------------------------------- Google

/** Google sign-in, wrapped so both layouts share one running attempt. */
private class GoogleAction(val waiting: Boolean, val onClick: () -> Unit)

@Composable
private fun rememberGoogleAction(vm: AuthViewModel): GoogleAction? {
    val google = rememberGoogleSignIn() ?: return null
    val scope = rememberCoroutineScope()
    // On desktop it waits for the browser, so the button turns into "Cancel" meanwhile.
    var job by remember { mutableStateOf<Job?>(null) }
    return GoogleAction(waiting = job != null) {
        val running = job
        if (running != null) {
            running.cancel()
            job = null
        } else {
            job = scope.launch {
                try {
                    when (val r = google.requestIdToken()) {
                        is GoogleTokenResult.Token -> vm.signInWithGoogle(r.idToken)
                        is GoogleTokenResult.Error -> vm.showError(r.message)
                        GoogleTokenResult.Cancelled -> {}
                    }
                } finally {
                    if (job == coroutineContext[Job]) job = null
                }
            }
        }
    }
}

@Composable
private fun GoogleButton(action: GoogleAction, signup: Boolean, loading: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(FieldColor)
            .border(1.dp, Color(0xFF262E45), shape)
            .clickable(enabled = !loading, role = Role.Button, onClick = action.onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(rememberVectorPainter(GoogleG), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                action.waiting -> "Waiting for Google… (tap to cancel)"
                signup -> "Sign up with Google"
                else -> "Continue with Google"
            },
            color = JournalColors.Text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Google's four-colour "G", as their sign-in guidelines ask. */
private val GoogleG: ImageVector by lazy {
    ImageVector.Builder("GoogleG", 18.dp, 18.dp, 48f, 48f).apply {
        addPath(addPathNodes("M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"), fill = SolidColor(Color(0xFFFFC107)))
        addPath(addPathNodes("M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"), fill = SolidColor(Color(0xFFFF3D00)))
        addPath(addPathNodes("M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"), fill = SolidColor(Color(0xFF4CAF50)))
        addPath(addPathNodes("M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"), fill = SolidColor(Color(0xFF1976D2)))
    }.build()
}
