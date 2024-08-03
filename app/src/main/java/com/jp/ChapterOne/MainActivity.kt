package com.jp.ChapterOne

import android.app.usage.UsageStatsManager
import android.content.Context
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.EventDateTime
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.calendar.Calendar
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTubeScopes
import com.jp.ChapterOne.ui.theme.ChapterOneTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    private lateinit var googleSignInClient: GoogleSignInClient
    private var signedInAccount: GoogleSignInAccount? by mutableStateOf(null)
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val mutex = Mutex()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        }
    }

    private val authResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            retryPendingTasks()
        } else {
            Log.e("Authorization", "ユーザーが承認を拒否しました。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupGoogleSignIn()
        loadAccountInfo()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        if (signedInAccount == null) {
            signIn()
        } else {
            checkAndLogUsageStats()
        }
    }

    private var currentApp: String? = null
    private var currentStartTime: Long = 0
    private var currentEventId: String? = null

    private var lastLoggedApp: String? = null
    private var lastLoggedStartTime: Long = 0
    private var lastLoggedEndTime: Long = 0

    private var pendingTask: (() -> Unit)? = null

    private fun retryPendingTasks() {
        pendingTask?.invoke()
        pendingTask = null
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = applicationContext.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun logEventToCalendar(appName: String, packageName: String, startTime: Long, endTime: Long) {
        Log.d("CalendarInsert", "イベントの挿入/更新を試みています: $appName, $packageName, start: $startTime, end: $endTime")
        if (signedInAccount == null) {
            Log.d("CalendarInsert", "サインインされたアカウントがないため、イベントを挿入/更新できません。")
            return
        }

        if (endTime <= startTime) {
            Log.e("CalendarInsert", "終了時間は開始時間の後でなければなりません: startTime=$startTime, endTime=$endTime")
            return
        }

        val credential = GoogleAccountCredential.usingOAuth2(
            this, listOf(CalendarScopes.CALENDAR)
        ).setSelectedAccount(signedInAccount?.account)

        val calendarService = Calendar.Builder(
            com.google.api.client.http.javanet.NetHttpTransport(),
            JacksonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ChapterOne").build()

        val eventSummary = "$appName を使用しました"
        val eventDescription = "パッケージ: $packageName"

        val startDateTime = com.google.api.client.util.DateTime(startTime)
        val endDateTime = com.google.api.client.util.DateTime(endTime)

        coroutineScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    if (currentEventId.isNullOrEmpty()) {
                        val newEvent = com.google.api.services.calendar.model.Event().apply {
                            summary = eventSummary
                            description = eventDescription
                            start = EventDateTime().setDateTime(startDateTime)
                            end = EventDateTime().setDateTime(endDateTime)
                        }
                        val createdEvent = calendarService.events().insert("primary", newEvent).execute()
                        currentEventId = createdEvent.id
                        Log.d("CalendarInsert", "イベントが作成されました: ${createdEvent.htmlLink}")
                    } else {
                        try {
                            val existingEvent = calendarService.events().get("primary", currentEventId).execute()
                            existingEvent.end = EventDateTime().setDateTime(endDateTime)
                            calendarService.events().update("primary", existingEvent.id, existingEvent).execute()
                            Log.d("CalendarInsert", "イベントが更新されました: ${existingEvent.summary} : ${existingEvent.start} : ${existingEvent.end}")
                        } catch (e: GoogleJsonResponseException) {
                            if (e.statusCode == 404) {
                                Log.w("CalendarInsert", "イベントが見つかりません。新しいイベントを作成します。")
                                currentEventId = null
                                logEventToCalendar(appName, packageName, startTime, endTime)
                            } else {
                                throw e
                            }
                        }
                    }
                } catch (e: UserRecoverableAuthException) {
                    pendingTask = { logEventToCalendar(appName, packageName, startTime, endTime) }
                    authResultLauncher.launch(e.intent)
                } catch (e: Exception) {
                    Log.e("CalendarInsert", "カレンダーイベントの挿入/更新に失敗しました: ${e.localizedMessage}", e)
                }
            }
        }
    }

    private fun checkAndLogUsageStats() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        if (checkUsageStatsPermission()) {
            Log.d("UsageStats", "権限が付与されています")
            val currentTime = System.currentTimeMillis()
            val twoHoursAgo = currentTime - 1000 * 60 * 60 * 2
            val usageEvents = usageStatsManager.queryEvents(twoHoursAgo, currentTime)
            val usageEvent = UsageEvents.Event()

            val excludedPackages = listOf(
                "com.google.android.gms",
                "com.android.providers.media",
                "com.google.android.googlequicksearchbox",
                "com.android.systemui"
            )

            val userApps = mutableListOf<String>()
            val packages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            for (packageInfo in packages) {
                if (!isSystemPackage(packageInfo) && !excludedPackages.contains(packageInfo.packageName)) {
                    userApps.add(packageInfo.packageName)
                }
            }

            var previousApp: String? = null
            var previousStartTime: Long = 0
            var previousEventType: Int = -1

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(usageEvent)
                // すべてのイベントをログに記録
                Log.d("AllEvents", "パッケージ: ${usageEvent.packageName}, 時刻: ${usageEvent.timeStamp}, イベントタイプ: ${usageEvent.eventType}")
                if (userApps.contains(usageEvent.packageName)) {
                    val appName = getAppName(usageEvent.packageName)
                    Log.d("UsageEvent", "イベント: $appName, 時刻: ${usageEvent.timeStamp}, イベントタイプ: ${usageEvent.eventType}")

                    if (usageEvent.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        Log.d("UsageEvent", "$appName がフォアグラウンドに移動しました: ${usageEvent.packageName}")
                        if (previousApp != null && previousApp == usageEvent.packageName && previousEventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                            previousEventType = UsageEvents.Event.MOVE_TO_FOREGROUND
                        } else {
                            if (previousApp != null && previousEventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                logEventToCalendar(getAppName(previousApp), previousApp, previousStartTime, usageEvent.timeStamp)
                            }
                            previousApp = usageEvent.packageName
                            previousStartTime = usageEvent.timeStamp
                            previousEventType = UsageEvents.Event.MOVE_TO_FOREGROUND
                        }
                    } else if (usageEvent.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                        Log.d("UsageEvent", "$appName がバックグラウンドに移動しました: ${usageEvent.packageName}")
                        if (previousApp != null && previousApp == usageEvent.packageName && previousEventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            logEventToCalendar(getAppName(previousApp), previousApp, previousStartTime, usageEvent.timeStamp)
                            previousEventType = UsageEvents.Event.MOVE_TO_BACKGROUND
                        }
                    }
                }
            }
            if (previousApp != null && previousEventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                logEventToCalendar(getAppName(previousApp), previousApp, previousStartTime, currentTime)
            }
        } else {
            Log.d("UsageStats", "権限が付与されていません")
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).also { startActivity(it) }
        }
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        val isGranted = mode == AppOpsManager.MODE_ALLOWED
        Log.d("PermissionCheck", "Usage stats permission granted: $isGranted")
        return isGranted
    }

    private fun requestUsageStatsPermission() {
        if (!checkUsageStatsPermission()) {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun isSystemPackage(packageInfo: PackageInfo): Boolean {
        return (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun saveAccountInfo(account: GoogleSignInAccount?) {
        val sharedPreferences = getSharedPreferences("ChapterOnePrefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("accountEmail", account?.email)
        editor.putString("accountIdToken", account?.idToken)
        editor.apply()
    }

    private fun loadAccountInfo() {
        val sharedPreferences = getSharedPreferences("ChapterOnePrefs", Context.MODE_PRIVATE)
        val idToken = sharedPreferences.getString("accountIdToken", null)
        if (idToken != null) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build()

            googleSignInClient = GoogleSignIn.getClient(this, gso)
            googleSignInClient.silentSignIn().addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val account = task.result
                    signedInAccount = account
                    updateUI()
                } else {
                    signIn()
                }
            }
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(Scope(CalendarScopes.CALENDAR), Scope(YouTubeScopes.YOUTUBE_READONLY))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            signedInAccount = account
            saveAccountInfo(account)
            Log.d("SignInSuccess", "ログインしました: ${account.email}")
        } catch (e: ApiException) {
            Log.e("SignInError", "ログインに失敗しました: ${e.statusCode} ${e.localizedMessage}")
        }
        updateUI()
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener(this) {
            signedInAccount = null
            updateUI()
        }
    }

    private fun updateUI() {
        setContent {
            ChapterOneTheme {
                MyApp(
                    account = signedInAccount,
                    onSignInOrOutButtonClicked = {
                        if (signedInAccount == null) signIn() else signOut()
                    },
                    onLogEventButtonClicked = {
                        val appName = getString(R.string.app_name)
                        val packageName = "com.example.app"
                        val startTime = System.currentTimeMillis()
                        val endTime = startTime + 1000 * 60
                        logEventToCalendar(appName, packageName, startTime, endTime)
                    }
                )
            }
        }
    }

    @Composable
    fun MyApp(account: GoogleSignInAccount?, onSignInOrOutButtonClicked: () -> Unit, onLogEventButtonClicked: () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(16.dp)) {
                account?.let {
                    Text(text = "名前: ${account.displayName}", style = MaterialTheme.typography.bodyLarge)
                    Text(text = "メール: ${account.email}", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSignInOrOutButtonClicked) {
                    Text(text = if (account == null) "Googleでサインイン" else "サインアウト")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { checkAndLogUsageStats() }) {
                    Text(text = "アプリ使用状況のチェック")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onLogEventButtonClicked) {
                    Text(text = "イベントをカレンダーにログ")
                }
            }
        }
    }

    @Preview(showBackground = true)
    @Composable
    fun DefaultPreview() {
        ChapterOneTheme {
            MyApp(null, {}, {})
        }
    }
}
