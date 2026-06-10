package com.example

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import com.example.data.AppDatabase
import com.example.data.RiceScan
import com.example.data.RiceScanRepository
import com.example.processor.ProcessorConfig
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.RiceViewModel
import com.example.viewmodel.RiceViewModelFactory
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize Database and Repository
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = RiceScanRepository(database.riceScanDao())
        val viewModelFactory = RiceViewModelFactory(repository)
        
        setContent {
            MyApplicationTheme {
                val viewModel: RiceViewModel = ViewModelProvider(this, viewModelFactory)[RiceViewModel::class.java]
                RiceCounterMainScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RiceCounterMainScreen(viewModel: RiceViewModel) {
    val context = LocalContext.current
    
    // Core States
    val originalBitmap by viewModel.originalBitmap.collectAsStateWithLifecycle()
    val result by viewModel.analysisResult.collectAsStateWithLifecycle()
    val showMask by viewModel.showThresholdMask.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    
    // UI Helpers
    var sampleTitle by remember { mutableStateOf("") }
    var showConfigPopup by remember { mutableStateOf(false) }
    var activeCameraFeed by remember { mutableStateOf(false) }
    
    // Camera Permissions
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    
    // Gallery Image Picker Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        viewModel.setImage(bitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_rice_logo),
                            contentDescription = "Logo",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Text(
                            text = "Analizador de Arroz",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                    }
                },
                actions = {
                    if (originalBitmap != null) {
                        IconButton(
                            onClick = { viewModel.toggleThresholdMask() },
                            modifier = Modifier.testTag("toggle_mask")
                        ) {
                            Icon(
                                imageVector = if (showMask) Icons.Filled.Visibility else Icons.Outlined.Visibility,
                                contentDescription = "Ver Umbrales"
                            )
                        }
                    }
                    IconButton(
                        onClick = { showConfigPopup = !showConfigPopup },
                        modifier = Modifier.testTag("config_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (showConfigPopup) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Calibración"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // PANEL PRINCIPAL: Visualización o Cámara Live
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    
                    if (originalBitmap != null) {
                        // MODO ANÁLISIS ACTIVO
                        val displayBitmap = if (showMask) result?.thresholdedBitmap else result?.annotatedBitmap
                        if (displayBitmap != null) {
                            Image(
                                bitmap = displayBitmap.asImageBitmap(),
                                contentDescription = "Imagen de arroz procesada",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Image(
                                bitmap = originalBitmap!!.asImageBitmap(),
                                contentDescription = "Imagen de arroz original",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        
                        // Cargando indicador
                        if (isAnalyzing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        
                        // Botón flotante para cerrar imagen
                        IconButton(
                            onClick = { viewModel.setImage(null) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .testTag("close_analysis_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cerrar",
                                tint = Color.White
                            )
                        }

                        // Indicador de modo
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (showMask) "VISTA: BINARIZADA" else "VISTA: DETECCIONES EN VIVO",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                    } else if (activeCameraFeed) {
                        // MODO CÁMARA ACTIVO
                        if (cameraPermissionState.status.isGranted) {
                            CameraPreviewWidget(
                                modifier = Modifier.fillMaxSize(),
                                onImageCaptured = { capturedImage ->
                                    viewModel.setImage(capturedImage)
                                    activeCameraFeed = false
                                }
                            )
                            
                            IconButton(
                                onClick = { activeCameraFeed = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Cerrar Cámara",
                                    tint = Color.White
                                )
                            }
                        } else {
                            // Sin permiso cargando
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraEnhance,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Permiso de cámara requerido para escanear en vivo.",
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { cameraPermissionState.launchPermissionRequest() }
                                ) {
                                    Text("Conceder Permiso")
                                }
                            }
                        }
                    } else {
                        // MODO MANUAL / VACÍO - BIENVENIDA
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_rice_logo),
                                contentDescription = "Logo Analizador de Arroz",
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Captura tu muestra",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Toma una foto de los granos de arroz distribuidos en un fondo contrastante para analizarlos instantáneamente.",
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(20.dp))
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (cameraPermissionState.status.isGranted) {
                                            activeCameraFeed = true
                                        } else {
                                            cameraPermissionState.launchPermissionRequest()
                                            if (cameraPermissionState.status.isGranted) {
                                                activeCameraFeed = true
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier.testTag("camera_btn")
                                ) {
                                    Icon(imageVector = Icons.Filled.CameraAlt, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cámara", fontSize = 13.sp)
                                }
                                
                                FilledTonalButton(
                                    onClick = { galleryLauncher.launch("image/*") },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    modifier = Modifier.testTag("gallery_btn")
                                ) {
                                    Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Galería", fontSize = 13.sp)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            TextButton(
                                onClick = { viewModel.loadSyntheticSample() },
                                modifier = Modifier.testTag("test_sample_btn")
                            ) {
                                Icon(imageVector = Icons.Filled.Autorenew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cargar Simulación de Prueba", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            
            // PANEL DE METRICAS DEL ANÁLISIS ACTIVO
            AnimatedVisibility(
                visible = originalBitmap != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    
                    // Tarjetas de Resultados
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Resultado de Evaluación",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Granos totales estimados
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${result?.totalGrainsEstimated ?: 0}",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Granos Estimados",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                            Spacer(modifier = Modifier.height(14.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                // Enteros
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color(0xFF2ECC71), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Enteros",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = "${result?.totalEnteros ?: 0}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                
                                // Partidos
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color(0xFFE67E22), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Partidos",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = "${result?.totalPartidos ?: 0}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                
                                // Cúmulos
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color(0xFFE74C3C), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Cúmulos",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        text = "${result?.totalCumulos ?: 0}",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            RiceDistributionChart(
                                enteros = result?.totalEnteros ?: 0,
                                partidos = result?.totalPartidos ?: 0,
                                cumulos = result?.totalCumulos ?: 0,
                                modifier = Modifier.testTag("rice_chart")
                            )
                        }
                    }
                    
                    // GUARDAR FORMULARIO
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Guardar Registro",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            
                            OutlinedTextField(
                                value = sampleTitle,
                                onValueChange = { sampleTitle = it },
                                placeholder = { Text("Ej. Lote Especial A-1", fontSize = 14.sp) },
                                label = { Text("Etiqueta o Nombre de Muestra", fontSize = 12.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sample_title_input"),
                                singleLine = true
                            )
                            
                            Button(
                                onClick = {
                                    viewModel.saveCurrentScan(context, sampleTitle)
                                    sampleTitle = ""
                                    viewModel.setImage(null) // Clear and return
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("save_analysis_btn")
                            ) {
                                Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Guardar en Historial", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // ACORDEÓN DE CALIBRACIÓN EN VIVO (Muestra si se clickea o si el usuario quiere refinar)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (showConfigPopup) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showConfigPopup = !showConfigPopup },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Calibración y Ajustes de Umbral",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Icon(
                            imageVector = if (showConfigPopup) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Desplegar"
                        )
                    }

                    AnimatedVisibility(visible = showConfigPopup) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            HorizontalDivider()
                            
                            // 1. Selector de contraste de fondo
                            Column {
                                Text(
                                    text = "Coloración de la Base / Fondo",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = { viewModel.updateBackgroundType(true) },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (config.isDarkBackground) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            contentColor = if (config.isDarkBackground) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Fondo Oscuro", fontSize = 11.sp)
                                    }
                                    
                                    FilledTonalButton(
                                        onClick = { viewModel.updateBackgroundType(false) },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (!config.isDarkBackground) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                            contentColor = if (!config.isDarkBackground) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Fondo Claro", fontSize = 11.sp)
                                    }
                                }
                                Text(
                                    text = if (config.isDarkBackground) 
                                        "Calibrado para granos de arroz claros sobre superficie oscura (ej. mesa negra, tela oscura)." 
                                        else "Calibrado para granos de arroz sobre superficie brillante clara (ej. plato blanco, papel).",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            
                            // 2. Umbral de Sensibilidad (Slider)
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Sensibilidad de Contraste (Umbral)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${config.thresholdValue}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = config.thresholdValue.toFloat(),
                                    onValueChange = { viewModel.updateThreshold(it.toInt()) },
                                    valueRange = 10f..240f,
                                    modifier = Modifier.testTag("threshold_slider")
                                )
                                Text(
                                    text = "Controla el recorte binarizado. Un valor más bajo detecta más contornos, pero puede agarrar reflejos de luz.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            
                            // 3. Área Grano Entero (Slider)
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Área Mínima Grano Entero",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${config.wholeGrainArea} px²",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = config.wholeGrainArea.toFloat(),
                                    onValueChange = { viewModel.updateWholeArea(it.toInt()) },
                                    valueRange = 150f..800f,
                                    modifier = Modifier.testTag("whole_area_slider")
                                )
                                Text(
                                    text = "Cualquier objeto con área superior a este valor se considerará entero. Menor a esto se considerará partido.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            
                            // 4. Área Cúmulos (Slider)
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Umbral de Cúmulo (Gramos Encimados)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${config.clusterArea} px²",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = config.clusterArea.toFloat(),
                                    onValueChange = { viewModel.updateClusterArea(it.toInt()) },
                                    valueRange = 600f..2500f,
                                    modifier = Modifier.testTag("cluster_area_slider")
                                )
                                Text(
                                    text = "Área a partir de la cual se asume que un conjunto es un cúmulo de granos pegados, estimando su conteo dividiendo el área total.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            
                            // 5. Filtro de Ruido (Área Mínima)
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Filtro de Ruido (Área Mínima)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${config.minAreaSize} px²",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = config.minAreaSize.toFloat(),
                                    onValueChange = { viewModel.updateMinArea(it.toInt()) },
                                    valueRange = 20f..300f,
                                    modifier = Modifier.testTag("min_area_slider")
                                )
                                Text(
                                    text = "Ignora cualquier partícula brillante más pequeña que este tamaño para evitar contar pelusas o suciedad.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // PANEL HISTORIAL DE ESCANEOS GUARDADOS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Historial Guardado (${historyList.size})",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (historyList.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearHistory() },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Borrar Todo", fontSize = 12.sp)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (historyList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HistoryToggleOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No hay registros históricos.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        // Un set scrolleable local limitado dentro del scroll vertical principal
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            historyList.forEach { scan ->
                                HistoryScanItem(
                                    scan = scan,
                                    onDelete = { viewModel.deleteScan(scan) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScanItem(
    scan: RiceScan,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val dateString = remember(scan.timestamp) { dateFormat.format(Date(scan.timestamp)) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Miniatura local guardada
            if (scan.imagePath != null) {
                AsyncImage(
                    model = File(scan.imagePath),
                    contentDescription = "Miniatura",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🌾", fontSize = 24.sp)
                }
            }
            
            Spacer(modifier = Modifier.width(10.dp))
            
            // Textos descriptivos
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scan.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    text = dateString,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Pequeños indicadores de conteo
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Ent: ${scan.totalEnteros}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF27AE60)
                    )
                    Text(
                        text = "Part: ${scan.totalPartidos}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD35400)
                    )
                    Text(
                        text = "Cúm: ${scan.totalCumulos}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC0392B)
                    )
                }

                // Compact Proportional Distribution Bar (Mini Segmented Chart)
                val totalH = scan.totalEnteros + scan.totalPartidos + scan.totalCumulos
                if (totalH > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    ) {
                        val weightEnteros = (scan.totalEnteros.toFloat() / totalH).coerceAtLeast(0f)
                        val weightPartidos = (scan.totalPartidos.toFloat() / totalH).coerceAtLeast(0f)
                        val weightCumulos = (scan.totalCumulos.toFloat() / totalH).coerceAtLeast(0f)
                        
                        if (weightEnteros > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(weightEnteros)
                                    .background(Color(0xFF2ECC71))
                            )
                        }
                        if (weightPartidos > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(weightPartidos)
                                    .background(Color(0xFFE67E22))
                            )
                        }
                        if (weightCumulos > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(weightCumulos)
                                    .background(Color(0xFFE74C3C))
                            )
                        }
                    }
                }
            }
            
            // Botón de eliminar
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Eliminar Registro",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun CameraPreviewWidget(
    modifier: Modifier = Modifier,
    onImageCaptured: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    
    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    Box(modifier = modifier) {
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
        
        // Botón de disparo flotante
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Button(
                onClick = {
                    val executor = ContextCompat.getMainExecutor(context)
                    imageCapture.takePicture(
                        executor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    val bitmap = image.toBitmap()
                                    image.close()
                                    onImageCaptured(bitmap)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    image.close()
                                }
                            }
                            
                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                            }
                        }
                    )
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .size(68.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .testTag("camera_shutter_btn")
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = "Disparador",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

// Convert core ImageProxy container to Bitmap securely mapping color dimensions
fun ImageProxy.toBitmap(): Bitmap {
    val buffer: ByteBuffer = planes[0].buffer
    buffer.rewind()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Composable
fun RiceDistributionChart(
    enteros: Int,
    partidos: Int,
    cumulos: Int,
    modifier: Modifier = Modifier
) {
    val total = enteros + partidos + cumulos
    val safeTotal = if (total > 0) total else 1
    
    val percentEnteros = enteros.toFloat() / safeTotal
    val percentPartidos = partidos.toFloat() / safeTotal
    val percentCumulos = cumulos.toFloat() / safeTotal

    // Animate widths to grow smoothly on load
    val animatedEnteros by animateFloatAsState(
        targetValue = percentEnteros,
        animationSpec = spring(stiffness = 150f),
        label = "enteros"
    )
    val animatedPartidos by animateFloatAsState(
        targetValue = percentPartidos,
        animationSpec = spring(stiffness = 150f),
        label = "partidos"
    )
    val animatedCumulos by animateFloatAsState(
        targetValue = percentCumulos,
        animationSpec = spring(stiffness = 150f),
        label = "cumulos"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Distribución de Muestra (Gráfico Dinámico)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )

        // Enteros Row
        ChartRow(
            label = "Enteros",
            count = enteros,
            percentage = percentEnteros,
            animatedProgress = animatedEnteros,
            color = Color(0xFF2ECC71)
        )

        // Partidos Row
        ChartRow(
            label = "Partidos",
            count = partidos,
            percentage = percentPartidos,
            animatedProgress = animatedPartidos,
            color = Color(0xFFE67E22)
        )

        // Cúmulos Row
        ChartRow(
            label = "Cúmulos (Estimación)",
            count = cumulos,
            percentage = percentCumulos,
            animatedProgress = animatedCumulos,
            color = Color(0xFFE74C3C)
        )
    }
}

@Composable
fun ChartRow(
    label: String,
    count: Int,
    percentage: Float,
    animatedProgress: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "$count granos (${(percentage * 100).toInt()}%)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
        
        // Progress Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = animatedProgress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

