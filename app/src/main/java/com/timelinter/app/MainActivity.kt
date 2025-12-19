package com.timelinter.app

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.timelinter.app.ui.components.AppTopBar
import com.timelinter.app.ui.components.NavigationActions
import com.timelinter.app.ui.components.TopNavigationMenu
import com.timelinter.app.ui.components.ScrollableTextFieldWithScrollbar
import com.timelinter.app.AppCategoriesScreen
import kotlinx.coroutines.launch
class MainActivity : ComponentActivity() {

    private var showUsageAccessDialog by mutableStateOf(false)
    private var showNotificationPermissionRationale by mutableStateOf(false)
    private var showHeadsUpInfoDialog by mutableStateOf(false)
    private var isMonitoringActive by mutableStateOf(false)
    private var apiKeyPresent by mutableStateOf(false)
    private var hasBackendToken by mutableStateOf(false)
    private var aiMode by mutableStateOf(SettingsManager.AI_MODE_BACKEND)
    private var userNotes by mutableStateOf("")
    private var coachName by mutableStateOf("Adam")
    private var isSigningIn by mutableStateOf(false)

    // Service binding for better lifecycle management
    private var boundService: AppUsageMonitorService? = null
    private var isServiceBound = false

    private val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as AppUsageMonitorService.LocalBinder
                    boundService = binder.getService()
                    isServiceBound = true
                    Log.d("MainActivity", "Service bound successfully")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    boundService = null
                    isServiceBound = false
                    Log.d("MainActivity", "Service disconnected")
                }
            }

    private fun refreshAuthState() {
        aiMode = SettingsManager.getAIMode(this)
        apiKeyPresent = ApiKeyManager.hasKey(this)
        hasBackendToken = ApiKeyManager.hasGoogleIdToken(this)
    }

    fun refreshAuthStateForTests() {
        refreshAuthState()
    }

    private fun hasRequiredCredentials(): Boolean {
        return if (aiMode == SettingsManager.AI_MODE_BACKEND) {
            hasBackendToken
        } else {
            apiKeyPresent
        }
    }

    // Launcher for Notification Permission
    private val requestNotificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                    isGranted: Boolean ->
                if (isGranted) {
                    // Permission is granted. Now check for Usage Access.
                    checkAndRequestUsageAccess()
                } else {
                    // Explain to the user that the feature is unavailable because the
                    // feature requires a permission that the user has denied.
                    showNotificationPermissionRationale = true
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge mode to properly handle system bars
        enableEdgeToEdge()

        // Ensure window insets are handled properly (especially for Samsung devices)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        refreshAuthState()
        EventLogStore.configurePersistenceIfNeeded(
            context = this,
            retentionDaysOverride = SettingsManager.getLogRetentionDays(this)
        )
        // Load user notes
        userNotes = ApiKeyManager.getUserNotes(this)
        // Load coach name
        coachName = ApiKeyManager.getCoachName(this)
        Log.d("MainActivity", "Coach name loaded: $coachName")

        setContent {
            MaterialTheme {
                val context = LocalContext.current

                // Check if this is first boot (no apps selected and tutorial not shown)
                val isFirstBoot = remember {
                    val assigned = AppCategoryConfigManager(context).getAppAssignments()
                    val tutorialShown = ApiKeyManager.hasFirstBootTutorialBeenShown(context)
                    assigned.isEmpty() && !tutorialShown
                }

                var showTutorialScreen by remember { mutableStateOf(isFirstBoot) }
                var activeScreen by rememberSaveable { mutableStateOf(MainScreen.Home) }

                val navigationActions =
                        NavigationActions(
                                onGoHome = {
                                    ApiKeyManager.setFirstBootTutorialShown(context)
                                    activeScreen = MainScreen.Home
                                },
                                onOpenCategories = {
                                    ApiKeyManager.setFirstBootTutorialShown(context)
                                    activeScreen = MainScreen.AppCategories
                                },
                                onOpenTimers = {
                                    ApiKeyManager.setFirstBootTutorialShown(context)
                                    activeScreen = MainScreen.TimerSettings
                                },
                                onOpenLog = {
                                    ApiKeyManager.setFirstBootTutorialShown(context)
                                    activeScreen = MainScreen.AILog
                                },
                                onOpenAIConfig = {
                                    ApiKeyManager.setFirstBootTutorialShown(context)
                                    activeScreen = MainScreen.AIConfig
                                }
                        )

                if (showTutorialScreen) {
                    FirstBootTutorialScreen(
                            onNavigateToAppSelection = {
                                showTutorialScreen = false
                                activeScreen = MainScreen.AppCategories
                            },
                            onSkip = {
                                ApiKeyManager.setFirstBootTutorialShown(context)
                                showTutorialScreen = false
                            }
                    )
                } else {
                    when (activeScreen) {
                        MainScreen.Home ->
                                TimeLinterApp(
                                        isMonitoring = isMonitoringActive,
                                        aiMode = aiMode,
                                        hasBackendToken = hasBackendToken,
                                        isSigningIn = isSigningIn,
                                        onToggleMonitoring = { attemptStartMonitoring() },
                                        onLogout = { logout() },
                                        showUsageAccessDialog = showUsageAccessDialog,
                                        onDismissUsageAccessDialog = { showUsageAccessDialog = false },
                                        onGoToUsageAccessSettings = { openUsageAccessSettings() },
                                        showNotificationPermissionRationale =
                                                showNotificationPermissionRationale,
                                        onDismissNotificationPermissionRationale = {
                                            showNotificationPermissionRationale = false
                                        },
                                        onRequestNotificationPermissionAgain = {
                                            requestNotificationPermission()
                                        },
                                        apiKeyPresent = apiKeyPresent,
                                        onGoogleSignIn = { triggerGoogleSignIn() },
                                        onGoogleSignOut = { clearGoogleSignIn() },
                                        onSaveApiKey = {
                                            ApiKeyManager.saveKey(this, it)
                                            apiKeyPresent = ApiKeyManager.hasKey(this)
                                        },
                                        showHeadsUpInfoDialog = showHeadsUpInfoDialog,
                                        onDismissHeadsUpInfoDialog = {
                                            showHeadsUpInfoDialog = false
                                            startMonitoringServiceIfPermitted()
                                        },
                                        onGoToChannelSettings = { openNotificationChannelSettings() },
                                        navigationActions = navigationActions,
                                        userNotes = userNotes,
                                        onSaveUserNotes = {
                                            ApiKeyManager.saveUserNotes(this, it)
                                            userNotes = it
                                        },
                                        coachName = coachName,
                                        onSaveCoachName = {
                                            ApiKeyManager.saveCoachName(this, it)
                                            coachName = it
                                        }
                                )
                        MainScreen.AppCategories ->
                                AppCategoriesScreen(
                                        navigationActions = navigationActions,
                                        monitoringActive = isMonitoringActive,
                                        onNavigateBack = { activeScreen = MainScreen.Home }
                                )
                        MainScreen.TimerSettings ->
                                TimerSettingsScreen(
                                        navigationActions = navigationActions,
                                        monitoringActive = isMonitoringActive,
                                        onNavigateBack = { activeScreen = MainScreen.Home }
                                )
                        MainScreen.AILog ->
                                AILogScreen(
                                        navigationActions = navigationActions,
                                        monitoringActive = isMonitoringActive,
                                        onNavigateBack = { activeScreen = MainScreen.Home }
                                )
                        MainScreen.AIConfig ->
                                Scaffold(
                                        topBar = {
                                            AppTopBar(
                                                    title = "AI Configuration",
                                                    monitoringActive = isMonitoringActive,
                                                    navigationIcon = {
                                                        IconButton(
                                                                onClick = {
                                                                    activeScreen =
                                                                            MainScreen.Home
                                                                }
                                                        ) {
                                                            Icon(
                                                                    Icons.AutoMirrored.Filled.ArrowBack,
                                                                    contentDescription = "Back"
                                                            )
                                                        }
                                                    },
                                                    actions = { TopNavigationMenu(navigationActions) }
                                            )
                                        }
                                ) { padding ->
                                    Box(modifier = Modifier.padding(padding)) { AIConfigScreen() }
                                }
                    }
                }
            }
        }
        // Update monitoring state based on service status
        isMonitoringActive = isServiceRunning()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up service binding
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
                isServiceBound = false
                boundService = null
            } catch (e: Exception) {
                Log.w("MainActivity", "Error unbinding service on destroy", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAuthState()
        maybeRefreshBackendToken()
        // Re-load user notes on resume
        userNotes = ApiKeyManager.getUserNotes(this)
        // Re-load coach name on resume
        coachName = ApiKeyManager.getCoachName(this)
        Log.d("MainActivity", "Coach name reloaded on resume: $coachName")
        // Re-check permissions if monitoring was supposed to be active
        if (isMonitoringActive && (!hasNotificationPermission() || !hasUsageStatsPermission())) {
            isMonitoringActive = false // Stop monitoring if permission revoked
            stopMonitoringService()
        }
    }

    private fun triggerGoogleSignIn() {
        if (isSigningIn) return
        isSigningIn = true
        lifecycleScope.launch {
            val token = AuthManager.signIn(this@MainActivity)
            isSigningIn = false
            hasBackendToken = !token.isNullOrEmpty()
            if (hasBackendToken) {
                verifyBackendStatusOrClear()
            }
        }
    }

    private fun clearGoogleSignIn() {
        ApiKeyManager.clearGoogleIdToken(this)
        hasBackendToken = false
    }

    private fun logout() {
        if (isMonitoringActive) {
            stopMonitoringService()
            isMonitoringActive = false
        }
        clearGoogleSignIn()
    }

    private fun maybeRefreshBackendToken() {
        if (aiMode != SettingsManager.AI_MODE_BACKEND) return
        if (isSigningIn) return
        val token = ApiKeyManager.getGoogleIdToken(this)
        val lastRefresh = ApiKeyManager.getGoogleIdTokenLastRefresh(this)
        val now = System.currentTimeMillis()
        val shouldRefresh =
                token.isNullOrEmpty() ||
                        lastRefresh == null ||
                        now - lastRefresh >= BackendAuthHelper.AUTO_REFRESH_INTERVAL_MS
        if (!shouldRefresh) return
        lifecycleScope.launch {
            isSigningIn = true
            try {
                val helper = BackendAuthHelper(
                        signIn = { AuthManager.signIn(this@MainActivity) },
                        getStoredToken = { ApiKeyManager.getGoogleIdToken(this@MainActivity) },
                        saveTokenWithTimestamp = { token, time ->
                            ApiKeyManager.saveGoogleIdToken(this@MainActivity, token, time)
                        },
                        clearToken = { ApiKeyManager.clearGoogleIdToken(this@MainActivity) },
                        backend = RealBackendGateway(),
                        getLastRefreshTimeMs = { ApiKeyManager.getGoogleIdTokenLastRefresh(this@MainActivity) },
                        timeProviderMs = { System.currentTimeMillis() }
                )
                val refreshed = helper.ensureFreshTokenIfExpired()
                hasBackendToken = !refreshed.isNullOrEmpty()
            } finally {
                isSigningIn = false
            }
        }
    }

    private suspend fun verifyBackendStatusOrClear() {
        val token = ApiKeyManager.getGoogleIdToken(this@MainActivity)
        if (token.isNullOrEmpty()) return
        try {
            withContext(Dispatchers.IO) {
                BackendClient.checkAuthStatus(token)
            }
        } catch (e: BackendHttpException) {
            clearGoogleSignIn()
            hasBackendToken = false
            val message = when (e.code) {
                BackendAccessCode.PENDING_APPROVAL ->
                    "Your account is pending approval. Please wait until it is activated."
                BackendAccessCode.ACCESS_REFUSED ->
                    "Access has been refused for this account. Please contact support."
                else -> "Sign-in failed: HTTP ${e.statusCode}"
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "Sign-in check failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun attemptStartMonitoring() {
        refreshAuthState()
        if (!isMonitoringActive) {
            if (!hasRequiredCredentials()) {
                Log.w("MainActivity", "Credentials not found. Cannot start monitoring.")
                return
            }
            // 1. Check Notification Permission first (Android 13+)
            if (hasNotificationPermission()) {
                // 2. If Notification permission granted, check Usage Access
                checkAndRequestUsageAccess()
            } else {
                // Request Notification Permission
                requestNotificationPermission()
            }
        } else {
            stopMonitoringService()
            isMonitoringActive = false
        }
    }

    private fun checkAndRequestUsageAccess() {
        if (hasUsageStatsPermission()) {
            handlePermissionSuccess()
        } else {
            showUsageAccessDialog = true
        }
    }

    private fun handlePermissionSuccess() {
        val channelImportance = ConversationChannelHelper.getChannelImportance(this)
        val shouldShowHeadsUpInfo =
                HeadsUpInfoDecider.shouldShowHeadsUpPrompt(
                        headsUpInfoAlreadyShown = ApiKeyManager.hasHeadsUpInfoBeenShown(this),
                        channelImportance = channelImportance
                )

        if (shouldShowHeadsUpInfo) {
            showHeadsUpInfoDialog = true
            ApiKeyManager.setHeadsUpInfoShown(this)
        } else {
            Log.d("MainActivity", "Heads-up info not required; starting service.")
            startMonitoringServiceIfPermitted()
        }
    }

    private fun startMonitoringServiceIfPermitted() {
        refreshAuthState()
        if (hasNotificationPermission() && hasUsageStatsPermission() && hasRequiredCredentials()) {
            Log.i(
                    "MainActivity",
                    "All permissions and credentials present. Starting Monitoring Service."
            )
            startMonitoringService() // The actual service start call
            isMonitoringActive = true
        } else {
            Log.w("MainActivity", "Attempted to start service without all permissions/credentials.")
            isMonitoringActive = false // Ensure state is correct
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU = API 33
            return ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            return true // Automatically granted on older versions
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Show rationale if needed (user denied previously without 'never ask again')
                showNotificationPermissionRationale = true
            } else {
                // Request the permission
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode =
                appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(),
                        packageName
                )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageAccessSettings() {
        showUsageAccessDialog = false // Dismiss dialog before opening settings
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun openNotificationChannelSettings() {
        val intent =
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, AppUsageMonitorService.CHANNEL_ID)
                }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening channel settings", e)
            openAppNotificationSettings()
        }
        showHeadsUpInfoDialog = false
        startMonitoringServiceIfPermitted()
    }

    private fun openAppNotificationSettings() {
        val intent =
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening app notification settings", e)
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, AppUsageMonitorService::class.java)
        startForegroundService(intent)

        // Also bind to the service for better lifecycle management
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun stopMonitoringService() {
        // Unbind first if bound
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            boundService = null
        }

        // Then stop the service
        val intent = Intent(this, AppUsageMonitorService::class.java)
        stopService(intent)
    }

    // Helper to check if our specific service is running
    // Modern approach: uses service binding and static instance tracking
    private fun isServiceRunning(): Boolean {
        // First check if we have a bound service that's ready
        if (isServiceBound && boundService?.isServiceReady() == true) {
            return true
        }

        // Modern fallback: check the static service instance
        // This is the recommended replacement for the deprecated getRunningServices()
        return AppUsageMonitorService.isServiceRunning()
    }
}

private enum class MainScreen {
    Home,
    AppCategories,
    TimerSettings,
    AILog,
    AIConfig
}

@Composable
fun TimeLinterApp(
        isMonitoring: Boolean,
        aiMode: String,
        hasBackendToken: Boolean,
        isSigningIn: Boolean,
        onToggleMonitoring: () -> Unit,
        onLogout: () -> Unit,
        showUsageAccessDialog: Boolean,
        onDismissUsageAccessDialog: () -> Unit,
        onGoToUsageAccessSettings: () -> Unit,
        showNotificationPermissionRationale: Boolean,
        onDismissNotificationPermissionRationale: () -> Unit,
        onRequestNotificationPermissionAgain: () -> Unit,
        apiKeyPresent: Boolean,
        onGoogleSignIn: () -> Unit,
        onGoogleSignOut: () -> Unit,
        onSaveApiKey: (String) -> Unit,
        showHeadsUpInfoDialog: Boolean,
        onDismissHeadsUpInfoDialog: () -> Unit,
        onGoToChannelSettings: () -> Unit,
        navigationActions: NavigationActions,
        userNotes: String,
        onSaveUserNotes: (String) -> Unit,
        coachName: String,
        onSaveCoachName: (String) -> Unit
) {
    var apiKeyInput by rememberSaveable { mutableStateOf("") }
    var userNotesInput by rememberSaveable { mutableStateOf(userNotes) }
    var coachNameInput by rememberSaveable { mutableStateOf(coachName) }
    val credentialsReady =
            if (aiMode == SettingsManager.AI_MODE_BACKEND) {
                hasBackendToken
            } else {
                apiKeyPresent
            }

    // Update local state when props change
    LaunchedEffect(userNotes) { userNotesInput = userNotes }
    LaunchedEffect(coachName) { coachNameInput = coachName }

    Scaffold(
            topBar = {
                AppTopBar(
                        title = stringResource(id = R.string.app_name),
                        monitoringActive = isMonitoring,
                        actions = { TopNavigationMenu(navigationActions) }
                )
            }
    ) { padding ->
        Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
        ) {
            // --- Coach Name Greeting Section ---
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Hi I'm ", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                        value = coachNameInput,
                        onValueChange = { coachNameInput = it },
                        label = { Text("Name") },
                        placeholder = { Text("Adam") },
                        singleLine = true,
                        modifier = Modifier.width(120.dp)
                )
            }
            if (coachNameInput != coachName && coachNameInput.isNotBlank()) {
                Button(
                        onClick = { onSaveCoachName(coachNameInput) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("Save") }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // --- Authentication Section ---
            if (aiMode == SettingsManager.AI_MODE_BACKEND) {
                if (!hasBackendToken) {
                    val backgroundColor = MaterialTheme.colorScheme.errorContainer
                    Box(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .background(
                                                    backgroundColor,
                                                    shape = RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .testTag("googleSignInCard"),
                            contentAlignment = Alignment.Center
                    ) {
                        Card(
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                        CardDefaults.cardColors(
                                                containerColor =
                                                        MaterialTheme.colorScheme.errorContainer
                                        ),
                                modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                    modifier =
                                            Modifier.fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                        text = "Google Sign-In Required",
                                        style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                        text = "Sign in to use your subscription-backed AI access.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                        onClick = onGoogleSignIn,
                                        enabled = !isSigningIn,
                                        modifier = Modifier.testTag("googleSignInButton")
                                ) {
                                    Text(
                                            if (isSigningIn) "Signing in..."
                                            else "Sign in with Google"
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            } else if (!apiKeyPresent) {
                val backgroundColor = MaterialTheme.colorScheme.errorContainer
                Box(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .background(
                                                backgroundColor,
                                                shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                ) {
                    val context = LocalContext.current

                    Card(
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                    CardDefaults.cardColors(
                                            containerColor =
                                                    MaterialTheme.colorScheme.errorContainer
                                    ),
                            modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                    text = "Gemini API Key Required",
                                    style = MaterialTheme.typography.titleMedium
                            )

                            Button(
                                    onClick = {
                                        val intent =
                                                Intent(
                                                        Intent.ACTION_VIEW,
                                                        "https://aistudio.google.com/apikey".toUri()
                                                )
                                        context.startActivity(intent)
                                    }
                            ) { Text("Get API Key") }

                            OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = { apiKeyInput = it },
                                    label = { Text("Enter API Key") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                    onClick = { onSaveApiKey(apiKeyInput) },
                                    enabled = apiKeyInput.isNotBlank()
                            ) { Text("Save API Key") }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp)) // Add space after key section
            }

            // --- Status recap section ---
            Spacer(modifier = Modifier.height(16.dp))

        val statusText: String
        val backgroundColor: Color
        val textColor: Color
        val showActionButtonInStatus: Boolean
        val statusActionLabel: String
        val statusActionColor: Color

            when {
                aiMode == SettingsManager.AI_MODE_BACKEND && !hasBackendToken -> {
                statusText = ""
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                showActionButtonInStatus = true
                statusActionLabel = "Start Monitoring"
                statusActionColor = MaterialTheme.colorScheme.primary
                }
                aiMode == SettingsManager.AI_MODE_DIRECT && !apiKeyPresent -> {
                statusText = ""
                    backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                showActionButtonInStatus = true
                statusActionLabel = "Start Monitoring"
                statusActionColor = MaterialTheme.colorScheme.primary
                }
                isMonitoring -> {
                statusText = ""
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer
                showActionButtonInStatus = true
                statusActionLabel = "Stop Monitoring"
                statusActionColor = MaterialTheme.colorScheme.error
                }
                else -> {
                statusText = ""
                backgroundColor = MaterialTheme.colorScheme.errorContainer
                textColor = MaterialTheme.colorScheme.onErrorContainer
                showActionButtonInStatus = true
                statusActionLabel = "Start Monitoring"
                statusActionColor = MaterialTheme.colorScheme.primary
                }
            }

            Box(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .background(backgroundColor, shape = RoundedCornerShape(8.dp))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
            ) {
                if (showActionButtonInStatus) {
                    Button(
                            onClick = onToggleMonitoring,
                            enabled = credentialsReady,
                            colors =
                                    ButtonDefaults.buttonColors(
                                            containerColor = statusActionColor
                                    )
                    ) {
                        Text(statusActionLabel)
                    }
                } else {
                    Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- User Notes Section ---
            Text(text = "Personal Notes for AI", style = MaterialTheme.typography.titleMedium)
            ScrollableTextFieldWithScrollbar(
                    value = userNotesInput,
                    onValueChange = { userNotesInput = it },
                    label = "Add context or goals for the AI...",
                    // placeholder = { Text("e.g., I'm trying to focus on work, help me stay
                    // productive") },
                    modifier = Modifier.fillMaxWidth().weight(1f), // Make it expand
                    // minLines = 8 // Increase default size, allows it to grow beyond this too
                    )
            Button(
                    onClick = { onSaveUserNotes(userNotesInput) },
                    enabled = userNotesInput != userNotes // Enable only if text has changed
            ) { Text("Save Notes") }
            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
            ) {
                Button(
                        onClick = onLogout,
                        enabled = apiKeyPresent || hasBackendToken,
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor =
                                                if (apiKeyPresent || hasBackendToken) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                }
                                )
                ) {
                    Text("Logout")
                }
            }

            // Dialog for Usage Access Permission
            if (showUsageAccessDialog) {
                AlertDialog(
                        onDismissRequest = onDismissUsageAccessDialog,
                        title = { Text("Usage Access Required") },
                        text = {
                            Text(
                                    "Time Linter needs Usage Access permission to monitor app usage. Please grant it in the system settings."
                            )
                        },
                        confirmButton = {
                            Button(onClick = onGoToUsageAccessSettings) { Text("Go to Settings") }
                        },
                        dismissButton = {
                            Button(onClick = onDismissUsageAccessDialog) { Text("Cancel") }
                        }
                )
            }

            // Dialog for Notification Permission Rationale
            if (showNotificationPermissionRationale) {
                AlertDialog(
                        onDismissRequest = onDismissNotificationPermissionRationale,
                        title = { Text("Notification Permission Needed") },
                        text = {
                            Text(
                                    "Notifications are required for Time Linter to function correctly, especially the persistent status notification. Please grant the permission to enable monitoring."
                            )
                        },
                        confirmButton = {
                            Button(onClick = onRequestNotificationPermissionAgain) {
                                Text("Request Again")
                            }
                        },
                        dismissButton = {
                            Button(onClick = onDismissNotificationPermissionRationale) {
                                Text("Cancel")
                            }
                        }
                )
            }

            // Dialog for Heads-Up/Pop-on-screen Info
            if (showHeadsUpInfoDialog) {
                AlertDialog(
                        onDismissRequest = onDismissHeadsUpInfoDialog,
                        title = { Text("Notification Style Suggestion") },
                        text = {
                            Text(
                                    "For the best experience, allow Time Linter's 'Conversation' notifications to 'Pop on screen'. This lets the AI reply appear immediately over other apps. You can check this in the channel settings."
                            )
                        },
                        confirmButton = {
                            Button(onClick = onGoToChannelSettings) { Text("Open Settings") }
                        },
                        dismissButton = {
                            Button(onClick = onDismissHeadsUpInfoDialog) { Text("Maybe Later") }
                        }
                )
            }
        }
    }
}
