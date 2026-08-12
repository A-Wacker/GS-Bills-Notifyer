package com.awacker.billsnotifier.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.awacker.billsnotifier.ui.detail.BillDetailScreen
import com.awacker.billsnotifier.ui.edit.BillEditScreen
import com.awacker.billsnotifier.ui.home.HomeScreen
import com.awacker.billsnotifier.ui.settings.SettingsScreen
import com.awacker.billsnotifier.ui.theme.BillsTheme

class MainActivity : ComponentActivity() {

    /**
     * Android 13+ needs this granted before any notification is shown — and the morning
     * digest is the point of the app, so it is asked for on first launch rather than being
     * deferred behind some later action.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            BillsTheme {
                Surface(
                    modifier = Modifier,
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BillsNavHost()
                }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{billId}"
    const val EDIT = "edit/{billId}"

    fun detail(billId: String) = "detail/$billId"
    fun edit(billId: String) = "edit/$billId"

    /** Sentinel id meaning "creating a plan" rather than editing an existing one. */
    const val NEW_PLAN = "new"
}

@Composable
private fun BillsNavHost(viewModel: BillsViewModel = viewModel()) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        toast?.let { current ->
            val result = snackbarHostState.showSnackbar(
                message = current.message,
                actionLabel = current.actionLabel,
                // An undo the user never sees is no undo at all, so give it the longer
                // dismissal window rather than the default few seconds.
                duration = if (current.onAction == null) {
                    SnackbarDuration.Short
                } else {
                    SnackbarDuration.Long
                },
            )
            if (result == SnackbarResult.ActionPerformed) current.onAction?.invoke()
            viewModel.clearToast()
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onOpenPlan = { navController.navigate(Routes.detail(it)) },
                onAddPlan = { navController.navigate(Routes.edit(Routes.NEW_PLAN)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DETAIL) { entry ->
            BillDetailScreen(
                billId = entry.arguments?.getString("billId").orEmpty(),
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.edit(it)) },
            )
        }
        composable(Routes.EDIT) { entry ->
            BillEditScreen(
                billId = entry.arguments?.getString("billId").orEmpty(),
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
