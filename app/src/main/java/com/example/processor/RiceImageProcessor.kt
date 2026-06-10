package com.example.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

enum class GrainType {
    WHOLE, BROKEN, CLUSTER
}

data class RiceBlob(
    val area: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val centerX: Int,
    val centerY: Int,
    var type: GrainType = GrainType.BROKEN,
    var estimatedCount: Int = 1
)

data class ProcessorConfig(
    val thresholdValue: Int = 110,
    val isDarkBackground: Boolean = true, // Rice is light, background dark
    val minAreaSize: Int = 100,
    val wholeGrainArea: Int = 400,
    val clusterArea: Int = 1000
)

data class RiceAnalysisResult(
    val totalEnteros: Int,
    val totalPartidos: Int,
    val totalCumulos: Int,
    val totalGrainsEstimated: Int,
    val blobs: List<RiceBlob>,
    val annotatedBitmap: Bitmap,
    val thresholdedBitmap: Bitmap
)

object RiceImageProcessor {

    fun analyzeImage(
        originalBitmap: Bitmap,
        config: ProcessorConfig
    ): RiceAnalysisResult {
        // 1. Scale down to a maximum dimension of 640 for real-time high-performance processing
        val scale = 640f / Math.max(originalBitmap.width, originalBitmap.height).toFloat()
        val width: Int
        val height: Int
        
        val workingBitmap = if (scale < 1.0f) {
            width = (originalBitmap.width * scale).toInt()
            height = (originalBitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(originalBitmap, width, height, true)
        } else {
            width = originalBitmap.width
            height = originalBitmap.height
            originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        // 2. Extract pixels to process pixel-by-pixel efficiently
        val pixels = IntArray(width * height)
        workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 3. Apply binary thresholding based on average/calculated luminance
        val thresholded = BooleanArray(width * height)
        val thresholdVal = config.thresholdValue

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            // Compute luminance (grayscale conversion)
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b).toInt()

            thresholded[i] = if (config.isDarkBackground) {
                luminance > thresholdVal
            } else {
                luminance < thresholdVal
            }
        }

        // 4. Create the Thresholded binary Bitmap for diagnostics/visual calibration
        val thresholdedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val binPixels = IntArray(width * height)
        for (i in thresholded.indices) {
            binPixels[i] = if (thresholded[i]) Color.WHITE else Color.BLACK
        }
        thresholdedBitmap.setPixels(binPixels, 0, width, 0, 0, width, height)

        // 5. Connect Component Labeling (CCL) using BFS in local arrays
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var queueHead: Int
        var queueTail: Int

        val blobs = mutableListOf<RiceBlob>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (thresholded[idx] && !visited[idx]) {
                    // New blob component found
                    var area = 0
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y
                    var sumX = 0L
                    var sumY = 0L

                    queueHead = 0
                    queueTail = 0
                    queue[queueTail++] = idx
                    visited[idx] = true

                    while (queueHead < queueTail) {
                        val currentIdx = queue[queueHead++]
                        val cx = currentIdx % width
                        val cy = currentIdx / width

                        area++
                        sumX += cx
                        sumY += cy

                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        // Crawl 8 neighbors
                        for (ny in -1..1) {
                            for (nx in -1..1) {
                                if (nx == 0 && ny == 0) continue
                                val neighborX = cx + nx
                                val neighborY = cy + ny

                                if (neighborX in 0 until width && neighborY in 0 until height) {
                                    val neighborIdx = neighborY * width + neighborX
                                    if (thresholded[neighborIdx] && !visited[neighborIdx]) {
                                        visited[neighborIdx] = true
                                        if (queueTail < queue.size) {
                                            queue[queueTail++] = neighborIdx
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (area >= config.minAreaSize) {
                        blobs.add(
                            RiceBlob(
                                area = area,
                                minX = minX,
                                minY = minY,
                                maxX = maxX,
                                maxY = maxY,
                                centerX = (sumX / area).toInt(),
                                centerY = (sumY / area).toInt()
                            )
                        )
                    }
                }
            }
        }

        // 6. Classification & Statistics accumulation (following Python script business rules)
        var totalEnteros = 0
        var totalPartidos = 0
        var totalCumulos = 0

        val classifiedBlobs = blobs.map { blob ->
            val area = blob.area
            when {
                area > config.clusterArea -> {
                    // Cúmulo: estimamos cantidad de granos enteros según división por área típica
                    val estimated = Math.round(area.toFloat() / config.wholeGrainArea.toFloat()).coerceAtLeast(2)
                    totalEnteros += estimated
                    totalCumulos++
                    blob.copy(type = GrainType.CLUSTER, estimatedCount = estimated)
                }
                area > config.wholeGrainArea -> {
                    totalEnteros++
                    blob.copy(type = GrainType.WHOLE, estimatedCount = 1)
                }
                else -> {
                    totalPartidos++
                    blob.copy(type = GrainType.BROKEN, estimatedCount = 1)
                }
            }
        }

        // 7. Paint Overlay / Annotation on the workingBitmap returning gorgeous visual feedback
        val annotatedBitmap = workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotatedBitmap)
        
        val paintWhole = Paint().apply {
            color = Color.rgb(46, 204, 113) // Fresh green
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val paintBroken = Paint().apply {
            color = Color.rgb(230, 126, 34) // Warm orange/coral
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val paintCluster = Paint().apply {
            color = Color.rgb(231, 76, 60) // Red/Crimson
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 10f
            isFakeBoldText = true
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val textBgPaint = Paint().apply {
            style = Paint.Style.FILL
        }

        classifiedBlobs.forEach { blob ->
            val paintToUse = when (blob.type) {
                GrainType.WHOLE -> paintWhole
                GrainType.BROKEN -> paintBroken
                GrainType.CLUSTER -> paintCluster
            }

            // Draw bounding rect
            val rect = Rect(blob.minX, blob.minY, blob.maxX, blob.maxY)
            canvas.drawRect(rect, paintToUse)

            // Dynamic text content
            val label = when (blob.type) {
                GrainType.WHOLE -> "Ent."
                GrainType.BROKEN -> "Part."
                GrainType.CLUSTER -> "Cúm. (${blob.estimatedCount})"
            }

            // Draw clean background pill under text for high visibility
            val textWidth = textPaint.measureText(label)
            val textHeight = 10f
            val tx = (blob.centerX - textWidth / 2).coerceAtLeast(4f)
            val ty = (blob.centerY + textHeight / 2).coerceAtLeast(12f)

            textBgPaint.color = when (blob.type) {
                GrainType.WHOLE -> Color.argb(190, 39, 174, 96)
                GrainType.BROKEN -> Color.argb(190, 211, 84, 0)
                GrainType.CLUSTER -> Color.argb(190, 192, 57, 43)
            }
            
            canvas.drawRoundRect(
                tx - 3f,
                ty - textHeight - 2f,
                tx + textWidth + 3f,
                ty + 3f,
                4f,
                4f,
                textBgPaint
            )
            canvas.drawText(label, tx, ty, textPaint)
        }

        // Draw general text summary in the header of the image if required
        val headerPaint = Paint().apply {
            color = Color.rgb(46, 204, 113) // Safe green background
            isAntiAlias = true
        }
        val headerTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = 14f
            isFakeBoldText = true
            isAntiAlias = true
        }

        canvas.drawRect(0f, 0f, width.toFloat(), 25f, Paint().apply { color = Color.argb(180, 0, 0, 0) })
        canvas.drawText(
            "Enteros: $totalEnteros | Partidos: $totalPartidos | Cúmulos: $totalCumulos",
            10f,
            18f,
            textPaint.apply { textSize = 11f }
        )

        val totalEstimated = totalEnteros + totalPartidos

        return RiceAnalysisResult(
            totalEnteros = totalEnteros,
            totalPartidos = totalPartidos,
            totalCumulos = totalCumulos,
            totalGrainsEstimated = totalEstimated,
            blobs = classifiedBlobs,
            annotatedBitmap = annotatedBitmap,
            thresholdedBitmap = thresholdedBitmap
        )
    }

    fun generateSyntheticRiceBitmap(): Bitmap {
        val width = 640
        val height = 480
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background: Dark charcoal-gray slate texture
        canvas.drawColor(Color.rgb(24, 30, 26))
        
        val paint = Paint().apply {
            color = Color.rgb(250, 248, 240) // Creamy high-contrast rice color
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        
        val random = java.util.Random(42) // Constant seed for reproducible sample
        
        // Draw Whole grains (roughly 12-18px wide, 25-35px tall, rotated)
        for (i in 0 until 18) {
            val cx = random.nextInt(width - 150) + 75
            val cy = random.nextInt(height - 150) + 75
            
            canvas.save()
            canvas.translate(cx.toFloat(), cy.toFloat())
            canvas.rotate(random.nextFloat() * 180f)
            canvas.drawOval(-22f, -8f, 22f, 8f, paint)
            canvas.restore()
        }
        
        // Draw Broken grains (smaller, rounder, or cut short)
        for (i in 0 until 12) {
            val cx = random.nextInt(width - 150) + 75
            val cy = random.nextInt(height - 150) + 75
            
            canvas.save()
            canvas.translate(cx.toFloat(), cy.toFloat())
            canvas.rotate(random.nextFloat() * 180f)
            canvas.drawOval(-11f, -7f, 11f, 7f, paint)
            canvas.restore()
        }
        
        // Draw Clusters (overlapping grains close to each other, forming larger blobs)
        for (i in 0 until 4) {
            val cx = random.nextInt(width - 150) + 75
            val cy = random.nextInt(height - 150) + 75
            
            canvas.save()
            canvas.translate(cx.toFloat(), cy.toFloat())
            
            // Draw 3 overlapping ellipses to build an actual cluster blob
            canvas.drawOval(-24f, -9f, 24f, 9f, paint)
            canvas.drawOval(-12f, -14f, 16f, 6f, paint)
            canvas.drawOval(-4f, -3f, 20f, 14f, paint)
            
            canvas.restore()
        }
        
        return bitmap
    }
}
