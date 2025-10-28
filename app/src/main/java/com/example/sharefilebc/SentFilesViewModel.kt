package com.example.sharefilebc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.sharefilebc.data.SharedFolderDao
import com.example.sharefilebc.data.UserDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class SentFileItemUi(
    val id: Int,
    val fileName: String,
    val uploadedAt: String,
    val deleteAt: String
)

data class SentFileGroupUi(
    val recipientName: String,
    val recipientEmail: String?,
    val files: List<SentFileItemUi>
)

class SentFilesViewModel(
    private val sharedFolderDao: SharedFolderDao,
    private val userDao: UserDao
) : ViewModel() {

    private val _sentFileGroups = MutableStateFlow<List<SentFileGroupUi>>(emptyList())
    val sentFileGroups: StateFlow<List<SentFileGroupUi>> = _sentFileGroups.asStateFlow()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).apply {
        timeZone = TimeZone.getTimeZone("Asia/Tokyo")
    }

    init {
        viewModelScope.launch {
            combine(sharedFolderDao.getAll(), userDao.getAll()) { sentFiles, users ->
                val emailMap = users.associate { it.name to it.email }
                sentFiles
                    .groupBy { it.recipientName }
                    .map { (recipientName, files) ->
                        val sortedFiles = files.sortedByDescending { parseDate(it.date)?.time ?: 0L }
                        SentFileGroupUi(
                            recipientName = recipientName,
                            recipientEmail = emailMap[recipientName],
                            files = sortedFiles.map { file ->
                                val uploadedAt = file.date
                                SentFileItemUi(
                                    id = file.id,
                                    fileName = file.fileName,
                                    uploadedAt = uploadedAt,
                                    deleteAt = calculateDeleteAt(uploadedAt, file.deleteDateTime)
                                )
                            }
                        )
                    }
                    .sortedByDescending { group ->
                        group.files.maxOfOrNull { parseDate(it.uploadedAt)?.time ?: 0L } ?: 0L
                    }
            }.collect { grouped ->
                _sentFileGroups.value = grouped
            }
        }
    }

    private fun parseDate(raw: String): java.util.Date? = try {
        dateFormatter.parse(raw)
    } catch (_: Exception) {
        null
    }

    private fun calculateDeleteAt(uploadedAt: String, storedDelete: String): String {
        if (storedDelete.isNotBlank()) {
            return storedDelete
        }
        val uploadedDate = parseDate(uploadedAt) ?: return ""
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            time = uploadedDate
            add(Calendar.DAY_OF_YEAR, 7)
        }
        return dateFormatter.format(calendar.time)
    }
}

class SentFilesViewModelFactory(
    private val sharedFolderDao: SharedFolderDao,
    private val userDao: UserDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SentFilesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SentFilesViewModel(sharedFolderDao, userDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}