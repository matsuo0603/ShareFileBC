package com.example.sharefilebc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sharefilebc.data.SharedFolderDao
import com.example.sharefilebc.data.SharedFolderEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SentFilesViewModel(private val dao: SharedFolderDao) : ViewModel() {

    private val _sentFiles = MutableStateFlow<List<SharedFolderEntity>>(emptyList())
    val sentFiles: StateFlow<List<SharedFolderEntity>> = _sentFiles.asStateFlow()

    init {
        loadSentFiles()
    }

    private fun loadSentFiles() {
        viewModelScope.launch {
            dao.getAll().collect { files ->
                _sentFiles.value = files
            }
        }
    }
}

class SentFilesViewModelFactory(private val dao: SharedFolderDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SentFilesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SentFilesViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}