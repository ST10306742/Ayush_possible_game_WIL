package com.example.donationdeliverygame

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.concurrent.thread
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var gameThread: Thread? = null
    @Volatile private var running = false
    private val paint = Paint()

    private var screenWidth = 0
    private var screenHeight = 0

    // Player truck properties
    private var truckX = 0f
    private val truckY get() = screenHeight - truckHeight - 50f
    private val truckWidth = 150f
    private val truckHeight = 300f

    // Hampers (items)
    private data class Hamper(var x: Float, var y: Float, val size: Float)
    private val hampers = mutableListOf<Hamper>()
    private val hamperSize = 80f

    // Obstacles
    private data class Obstacle(var x: Float, var y: Float, val width: Float, val height: Float)
    private val obstacles = mutableListOf<Obstacle>()
    private val obstacleWidth = 150f
    private val obstacleHeight = 150f

    // Speed and score
    private val baseSpeed = 8f
    private val speedIncreasePerHamper = 0.8f
    private var scrollSpeed = baseSpeed

    // Level system
    private var currentLevel = 1
    private val maxLevel = 10
    private val hamperGoal get() = currentLevel * 10
    private var hampersCollected = 0

    // Touch drag tracking
    private var dragPointerId = -1

    // Game states
    private enum class GameState { START, PLAYING, LEVEL_COMPLETE, GAME_OVER }
    private var gameState = GameState.START

    // Animation trackers
    private var successAnimProgress = 0f
    private val successAnimDuration = 90 // frames (~1.5 sec at 60fps)
    private var failAnimProgress = 0f
    private val failAnimDuration = 90

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenWidth = width
        screenHeight = height
        resetGame()
        running = true
        gameThread = thread(start = true) { gameLoop() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try {
            gameThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun resetGame() {
        truckX = (screenWidth / 2f) - (truckWidth / 2f)
        hampers.clear()
        obstacles.clear()
        hampersCollected = 0
        scrollSpeed = baseSpeed
        spawnInitialObjects()
        gameState = GameState.START
        successAnimProgress = 0f
        failAnimProgress = 0f
    }

    private fun spawnInitialObjects() {
        // Spawn initial hampers (enough to cover the screen, always available)
        for (i in 0 until 15) {
            val yPos = -i * 300f - 150f
            hampers.add(Hamper(randomX(), yPos, hamperSize))
        }

        // Spawn obstacles count depending on level difficulty (level 1 = fewer, maxLevel = more)
        val obstacleCount = (3 + currentLevel * 1.5).toInt().coerceAtMost(15) // max 15 obstacles
        for (i in 0 until obstacleCount) {
            val yPos = -i * 400f - 200f
            obstacles.add(Obstacle(randomX(), yPos, obstacleWidth, obstacleHeight))
        }
    }

    private fun randomX(): Float {
        val margin = 50f
        return Random.nextFloat() * (screenWidth - 2 * margin - hamperSize) + margin
    }

    private fun gameLoop() {
        while (running) {
            val startTime = System.currentTimeMillis()
            if (gameState == GameState.PLAYING) update()
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    synchronized(holder) {
                        drawGame(canvas)
                    }
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
            val frameTime = System.currentTimeMillis() - startTime
            val sleepTime = 16 - frameTime
            if (sleepTime > 0) Thread.sleep(sleepTime)
        }
    }

    private fun update() {
        val moveY = scrollSpeed

        // Update hampers (unlimited — respawn at top when offscreen)
        val hampersCopy = hampers.toList()
        for (hamper in hampersCopy) {
            hamper.y += moveY
            if (hamper.y > screenHeight) {
                hamper.x = randomX()
                hamper.y = -Random.nextInt(300, 900).toFloat()
            }
            if (isColliding(truckX, truckY, truckWidth, truckHeight, hamper.x, hamper.y, hamper.size, hamper.size)) {
                if (hampers.contains(hamper)) {
                    hampers.remove(hamper)
                    hampersCollected++
                    updateSpeed()
                    // Spawn one new hamper to keep items unlimited
                    hampers.add(Hamper(randomX(), -100f, hamperSize))
                    if (hampersCollected >= hamperGoal) {
                        gameState = GameState.LEVEL_COMPLETE
                    }
                }
            }
        }

        // Update obstacles (scaled by level difficulty)
        val obstaclesCopy = obstacles.toList()
        for (obstacle in obstaclesCopy) {
            obstacle.y += moveY
            if (obstacle.y > screenHeight) {
                obstacle.x = randomX()
                obstacle.y = -Random.nextInt(300, 900).toFloat()
            }
            if (isColliding(truckX, truckY, truckWidth, truckHeight, obstacle.x, obstacle.y, obstacle.width, obstacle.height)) {
                gameState = GameState.GAME_OVER
            }
        }
    }

    private fun updateSpeed() {
        scrollSpeed = baseSpeed + speedIncreasePerHamper * hampersCollected
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(30, 144, 255)) // blue sky background

        when (gameState) {
            GameState.START -> drawStartScreen(canvas)
            GameState.PLAYING -> drawPlayingScreen(canvas)
            GameState.LEVEL_COMPLETE -> drawLevelCompleteScreen(canvas)
            GameState.GAME_OVER -> drawGameOverScreen(canvas)
        }
    }

    private fun drawStartScreen(canvas: Canvas) {
        paint.color = Color.WHITE
        paint.textSize = 80f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Hamper Hero", screenWidth / 2f, screenHeight / 3f, paint)
        paint.textSize = 50f
        canvas.drawText("Tap to Start", screenWidth / 2f, screenHeight / 2f, paint)
    }

    private fun drawPlayingScreen(canvas: Canvas) {
        val roadMargin = 50f
        paint.color = Color.DKGRAY
        canvas.drawRect(roadMargin, 0f, screenWidth - roadMargin, screenHeight.toFloat(), paint)

        paint.color = Color.GREEN
        for (hamper in hampers) {
            canvas.drawOval(hamper.x, hamper.y, hamper.x + hamper.size, hamper.y + hamper.size, paint)
        }

        paint.color = Color.RED
        for (obstacle in obstacles) {
            canvas.drawRect(obstacle.x, obstacle.y, obstacle.x + obstacle.width, obstacle.y + obstacle.height, paint)
        }

        paint.color = Color.BLUE
        canvas.drawRect(truckX, truckY, truckX + truckWidth, truckY + truckHeight, paint)

        paint.color = Color.WHITE
        paint.textSize = 45f
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("Level: $currentLevel", 30f, 60f, paint)
        canvas.drawText("Hampers: $hampersCollected / $hamperGoal", 30f, 110f, paint)
        canvas.drawText("Speed: %.1f".format(scrollSpeed), 30f, 160f, paint)
    }

    private fun drawLevelCompleteScreen(canvas: Canvas) {
        paint.color = Color.GREEN
        paint.textSize = 80f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Level $currentLevel Complete!", screenWidth / 2f, screenHeight / 3f, paint)
        paint.textSize = 100f
        canvas.drawText("🎁 Delivered!", screenWidth / 2f, screenHeight / 2f, paint)

        successAnimProgress++
        if (successAnimProgress > successAnimDuration) {
            successAnimProgress = 0f
            advanceToNextLevel()
        }
    }

    private fun drawGameOverScreen(canvas: Canvas) {
        paint.color = Color.RED
        paint.textSize = 80f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Game Over!", screenWidth / 2f, screenHeight / 3f, paint)
        paint.textSize = 100f
        canvas.drawText("😢", screenWidth / 2f, screenHeight / 2f, paint)
        paint.textSize = 50f
        canvas.drawText("Tap to Retry", screenWidth / 2f, screenHeight / 2f + 80f, paint)

        failAnimProgress++
        if (failAnimProgress > failAnimDuration) {
            failAnimProgress = 0f
        }
    }

    private fun advanceToNextLevel() {
        currentLevel++
        if (currentLevel > maxLevel) {
            // Game complete, reset to start
            currentLevel = 1
            resetGame()
        } else {
            hampersCollected = 0
            scrollSpeed = baseSpeed
            hampers.clear()
            obstacles.clear()
            spawnInitialObjects()
            gameState = GameState.PLAYING
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gameState == GameState.START) {
                    gameState = GameState.PLAYING
                } else if (gameState == GameState.GAME_OVER) {
                    resetGame()
                    gameState = GameState.START
                } else if (gameState == GameState.LEVEL_COMPLETE) {
                    // Ignore taps during animation
                } else if (gameState == GameState.PLAYING) {
                    dragPointerId = event.getPointerId(0)
                    moveTruckHorizontal(event.x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(dragPointerId)
                if (pointerIndex != -1 && gameState == GameState.PLAYING) {
                    val x = event.getX(pointerIndex)
                    moveTruckHorizontal(x)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragPointerId = -1
            }
        }
        return true
    }

    private fun moveTruckHorizontal(x: Float) {
        val margin = 50f
        truckX = x - truckWidth / 2f
        if (truckX < margin) truckX = margin
        if (truckX + truckWidth > screenWidth - margin) truckX = screenWidth - margin - truckWidth
    }

    private fun isColliding(x1: Float, y1: Float, w1: Float, h1: Float,
                            x2: Float, y2: Float, w2: Float, h2: Float): Boolean {
        return x1 < x2 + w2 &&
                x1 + w1 > x2 &&
                y1 < y2 + h2 &&
                y1 + h1 > y2
    }

    fun pause() {
        running = false
        try {
            gameThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    fun resume() {
        if (!running) {
            running = true
            gameThread = thread(start = true) { gameLoop() }
        }
    }
}
