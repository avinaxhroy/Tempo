package me.avinas.tempo.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.data.repository.AppPreferenceRepository
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class AppPreferenceUiState(
    val preinstalledApps: List<AppPreference> = emptyList(),
    val userAddedApps: List<AppPreference> = emptyList(),
    val blockedApps: List<AppPreference> = emptyList(),
    val installedPackageNames: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

@HiltViewModel
class AppPreferenceViewModel @Inject constructor(
    private val repository: AppPreferenceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    
    private val _searchQuery = MutableStateFlow("")
    
    private val _uiState = MutableStateFlow(AppPreferenceUiState())
    val uiState: StateFlow<AppPreferenceUiState> = _uiState.asStateFlow()
    
    init {
        // Seed default apps for fresh installs (no-op if already seeded)
        viewModelScope.launch(Dispatchers.IO) {
            repository.seedDefaultAppsIfNeeded()
        }
        
        // Combine all app lists with search filtering
        viewModelScope.launch {
            combine(
                repository.getPreinstalledApps(),
                repository.getUserAddedApps(),
                repository.getBlockedApps(),
                _searchQuery
            ) { preinstalled, userAdded, blocked, query ->
                // Get installed packages
                val installedPackages = try {
                    val pm = context.packageManager
                    pm.getInstalledPackages(0)
                        .map { it.packageName }
                        .toSet()
                } catch (e: Exception) {
                    emptySet()
                }

                val filteredPreinstalled = if (query.isBlank()) preinstalled 
                    else preinstalled.filter { 
                        it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                    }
                
                // Sort preinstalled apps: Installed first, then Alphabetical
                val sortedPreinstalled = filteredPreinstalled.sortedWith(
                    compareByDescending<AppPreference> { it.packageName in installedPackages }
                        .thenBy { it.displayName }
                )

                val filteredUserAdded = if (query.isBlank()) userAdded
                    else userAdded.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                    }
                
                // Sort user added apps too
                val sortedUserAdded = filteredUserAdded.sortedWith(
                    compareByDescending<AppPreference> { it.packageName in installedPackages }
                        .thenBy { it.displayName }
                )

                val filteredBlocked = if (query.isBlank()) blocked
                    else blocked.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                    }
                
                AppPreferenceUiState(
                    preinstalledApps = sortedPreinstalled,
                    userAddedApps = sortedUserAdded,
                    blockedApps = filteredBlocked,
                    installedPackageNames = installedPackages,
                    searchQuery = query,
                    isLoading = false
                )
            }
            .flowOn(Dispatchers.Default)
            .collect { state ->
                _uiState.value = state
            }
        }
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun toggleAppEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setAppEnabled(packageName, enabled)
        }
    }
    
    fun blockApp(packageName: String) {
        viewModelScope.launch {
            repository.setAppBlocked(packageName, true)
        }
    }
    
    fun unblockApp(packageName: String) {
        viewModelScope.launch {
            repository.setAppBlocked(packageName, false)
        }
    }
    
    fun addCustomApp(packageName: String, displayName: String) {
        viewModelScope.launch {
            repository.addCustomApp(packageName, displayName)
        }
    }
    
    fun removeApp(packageName: String) {
        viewModelScope.launch {
            repository.removeApp(packageName)
        }
    }
}
