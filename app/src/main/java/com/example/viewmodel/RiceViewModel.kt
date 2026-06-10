package com.example.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.RiceScan
import com.example.data.RiceScanRepository
import com.example.processor.ProcessorConfig
import com.example.processor.RiceAnalysisResult
import com.example.processor.RiceImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class RiceViewModel(private val repository: RiceScanRepository) : ViewModel() {

    private val _config = MutableStateFlow(ProcessorConfig())
    val config = _config.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap = _originalBitmap.asStateFlow()

    private val _analysisResult = MutableStateFlow<RiceAnalysisResult?>(null)
    val analysisResult = _analysisResult.asStateFlow()

    private val _showThresholdMask = MutableStateFlow(false)
    val showThresholdMask = _showThresholdMask.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing = _isAnalyzing.asStateFlow()

    val historyList: StateFlow<List<RiceScan>> = repository.allScans
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateThreshold(value: Int) {
        _config.value = _config.value.copy(thresholdValue = value)
        runAnalysis()
    }

    fun updateBackgroundType(isDark: Boolean) {
        _config.value = _config.value.copy(isDarkBackground = isDark)
        runAnalysis()
    }

    fun updateMinArea(value: Int) {
        _config.value = _config.value.copy(minAreaSize = value)
        runAnalysis()
    }

    fun updateWholeArea(value: Int) {
        _config.value = _config.value.copy(wholeGrainArea = value)
        runAnalysis()
    }

    fun updateClusterArea(value: Int) {
        _config.value = _config.value.copy(clusterArea = value)
        runAnalysis()
    }

    fun toggleThresholdMask() {
        _showThresholdMask.value = !_showThresholdMask.value
    }

    fun setImage(bitmap: Bitmap?) {
        _originalBitmap.value = bitmap
        if (bitmap == null) {
            _analysisResult.value = null
        } else {
            runAnalysis()
        }
    }

    fun loadSyntheticSample() {
        val sample = RiceImageProcessor.generateSyntheticRiceBitmap()
        setImage(sample)
    }

    private fun runAnalysis() {
        val bitmap = _originalBitmap.value ?: return
        viewModelScope.launch {
            _isAnalyzing.value = true
            val result = withContext(Dispatchers.Default) {
                RiceImageProcessor.analyzeImage(bitmap, _config.value)
            }
            _analysisResult.value = result
            _isAnalyzing.value = false
        }
    }

    fun saveCurrentScan(context: Context, title: String) {
        val result = _analysisResult.value ?: return
        viewModelScope.launch {
            val imagePath = withContext(Dispatchers.IO) {
                val filename = "scan_annotated_${System.currentTimeMillis()}.jpg"
                val file = File(context.filesDir, filename)
                FileOutputStream(file).use { out ->
                    result.annotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                file.absolutePath
            }

            val scan = RiceScan(
                title = title.ifBlank { "Análisis de Arroz" },
                totalEnteros = result.totalEnteros,
                totalPartidos = result.totalPartidos,
                totalCumulos = result.totalCumulos,
                thresholdValue = _config.value.thresholdValue,
                minArea = _config.value.minAreaSize,
                wholeArea = _config.value.wholeGrainArea,
                clusterArea = _config.value.clusterArea,
                imagePath = imagePath
            )
            repository.insert(scan)
        }
    }

    fun deleteScan(scan: RiceScan) {
        viewModelScope.launch {
            scan.imagePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            repository.delete(scan.id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyList.value.forEach { scan ->
                scan.imagePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
            repository.clearAll()
        }
    }
}

class RiceViewModelFactory(private val repository: RiceScanRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RiceViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RiceViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
