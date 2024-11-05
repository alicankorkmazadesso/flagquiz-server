package service

import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import models.*
import java.util.*

/**
 * @author guvencenanguvenal
 */
class RoomManagerService private constructor() {
    companion object {

        val INSTANCE: RoomManagerService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { RoomManagerService() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val gameScope = CoroutineScope(Dispatchers.Default + Job())

    private val rooms = mutableMapOf<String, GameRoom>()

    private val playerToRoom = mutableMapOf<String, String>()

    private val roundAnswers = mutableMapOf<String, MutableMap<String, String>>()

    private val roundTimers = mutableMapOf<String, Job>()  // Her odanın zamanlayıcısı

    private val disconnectedPlayers = mutableMapOf<String, DisconnectedPlayer>()

    fun getRoomIdFromPlayerId(playerId: String): String {
        return playerToRoom[playerId]!!
    }

    fun createRoom(playerId: String, playerName: String): String {
        val roomId = UUID.randomUUID().toString()
        val player = Player(playerId, playerName)
        val room = GameRoom(roomId)
        room.players.add(player)
        rooms[roomId] = room
        playerToRoom[playerId] = roomId
        println("Room $roomId created by player $playerId")
        return roomId
    }

    fun joinRoom(playerId: String, roomId: String, playerName: String): Boolean {
        val room = rooms[roomId] ?: return false
        //TODO odaya istedigi kadar kisi katilabilecek
        if (room.players.size >= 2) return false

        val player = Player(playerId, playerName)
        room.players.add(player)
        playerToRoom[playerId] = roomId
        println("Player $playerId joined room $roomId")
        return true
    }

    suspend fun startGame(roomId: String) {
        val room = rooms[roomId] ?: return
        //default resistanceGame start
        val game = ResistanceGame(roomId, room.players)
        room.game = game

        println("Starting game for room $roomId with ${room.players.size} players")
        if (room.players.size != room.game!!.maxPlayerCount()) return

        gameScope.launch {
            println("Starting countdown for room $roomId")
            // Geri sayım başlat
            room.roomState = RoomState.COUNTDOWN
            broadcastRoomState(roomId)

            println("Waiting 3 seconds...")
            // 3 saniye bekle
            delay(3000)

            println("Starting actual game for room $roomId")
            // Oyunu başlat
            room.roomState = RoomState.PLAYING
            nextQuestion(roomId)
        }
    }

    private suspend fun broadcastRoomState(roomId: String) {
        println("Broadcasting game state for room $roomId")
        val room = rooms[roomId] ?: return

        val gameUpdate = GameMessage.GameUpdate(
            roomState = room.roomState,
            cursorPosition = room.cursorPosition,
            currentQuestion = room.game!!.currentQuestion?.toClientQuestion()
        )
        broadcastToRoom(roomId, gameUpdate)
    }

    private suspend fun nextQuestion(roomId: String) {
        val room = rooms[roomId]!!
        val question = room.game!!.nextQuestion()
        roundAnswers[roomId]?.clear()
        //TODO: gameleri yoneten bir yapi kurulmali
        val resistanceGame = rooms[roomId]?.game as ResistanceGame?

        val gameUpdate = GameMessage.GameUpdate(
            roomState = RoomState.PLAYING,
            cursorPosition = resistanceGame?.cursorPosition ?: 0.5f,
            timeRemaining = room.game!!.getRoundTime(),
            currentQuestion = question.toClientQuestion()
        )

        broadcastToRoom(roomId, gameUpdate)
        startRoundTimer(roomId)
    }

    private fun startRoundTimer(roomId: String) {
        val room = rooms[roomId]!!
        // Önceki timer'ı iptal et
        roundTimers[roomId]?.cancel()
        // Yeni timer başlat
        roundTimers[roomId] = CoroutineScope(Dispatchers.Default).launch {
            try {
                for (timeLeft in room.game!!.getRoundTime() - 1 downTo 1) {
                    delay(1000)
                    val timeUpdate = GameMessage.TimeUpdate(timeRemaining = timeLeft)
                    broadcastToRoom(roomId, timeUpdate)
                }
                delay(1000)
                // Süre doldu
                handleRoundEnd(roomId)
            } catch (e: CancellationException) {
                // Timer iptal edildi
            }
        }
    }

    private fun cleanupRoom(roomId: String) {
        val room = rooms[roomId] ?: return

        // Odadaki oyunculara bildir
        room.players.forEach { player ->
            SessionManagerService.INSTANCE.getPlayerSession(player.id)?.let { session ->
                CoroutineScope(Dispatchers.IO).launch {
                    val message = GameMessage.RoomClosed(reason = "Player disconnected for too long")
                    session.send(Frame.Text(json.encodeToString(message)))
                }
            }

            // Oyuncu verilerini temizle
            SessionManagerService.INSTANCE.removePlayerSession(player.id)
            disconnectedPlayers.remove(player.id)
        }

        // Oda verilerini temizle
        rooms.remove(roomId)
        roundAnswers.remove(roomId)
        roundTimers[roomId]?.cancel()
        roundTimers.remove(roomId)
    }

    private suspend fun broadcastToRoom(roomId: String, message: GameMessage) {
        println("Broadcasting message to room $roomId: $message")
        val room = rooms[roomId] ?: return
        val playerIds = room.players.map(Player::id).toMutableList()
        SessionManagerService.INSTANCE.broadcastToPlayers(playerIds, message)
    }

    fun getActiveRooms(): List<ActiveRoom> {
        return rooms.map { (id, room) ->
            ActiveRoom(
                id = id,
                playerCount = room.players.size,
                roomState = room.roomState,
                players = room.players.map { it.name }
            )
        }
    }

    suspend fun handlePlayerAnswer(roomId: String, playerId: String, answer: String) {
        val room = rooms[roomId] ?: return
        val question = room.game!!.currentQuestion ?: return
        val player = room.players.find { it.id == playerId } ?: return

        // Cevabı kaydet
        roundAnswers.getOrPut(roomId) { mutableMapOf() }[playerId] = answer

        // Cevap sonucunu bildir
        val answerResult = GameMessage.AnswerResult(
            playerName = player.name,
            answer = answer,
            correct = answer == question.correctAnswer
        )

        broadcastToRoom(roomId, answerResult)

        // Doğru cevap verildiyse eli hemen sonlandır
        if (answer == question.correctAnswer) {
            roundTimers[roomId]?.cancel()  // Timer'ı iptal et
            handleRoundEnd(roomId)
        }
    }

    private suspend fun handleRoundEnd(roomId: String) {
        val room = rooms[roomId] ?: return
        val question = room.game!!.currentQuestion ?: return
        val answers = roundAnswers[roomId] ?: mutableMapOf()

        // Süre doldu mesajı
        val timeUpMessage = GameMessage.TimeUp(correctAnswer = question.correctAnswer)
        broadcastToRoom(roomId, timeUpMessage)

        // Doğru cevap veren oyuncuyu bul
        val correctPlayer = room.players.find { p ->
            answers[p.id] == question.correctAnswer
        }

        if (correctPlayer != null) {
            // İmleç pozisyonunu güncelle,
            val currentPosition = room.cursorPosition
            val movement = if (room.players.indexOf(correctPlayer) == 0) -0.1f else 0.1f
            val newPosition = currentPosition + movement
            room.cursorPosition = when {
                newPosition <= 0.1f -> 0f  // Sol limit
                newPosition >= 0.9f -> 1f  // Sağ limit
                else -> newPosition
            }

            if (room.cursorPosition <= 0f || room.cursorPosition >= 1f) {
                room.roomState = RoomState.FINISHED
                val gameOverMessage = GameMessage.GameOver(winner = correctPlayer.name)
                broadcastToRoom(roomId, gameOverMessage)

                // Odayı temizle
                delay(5000)
                cleanupRoom(roomId)
            } else {
                // Yeni soruya geç
                delay(1500)
                nextQuestion(roomId)
            }
        } else {
            // Kimse doğru cevap veremediyse yeni soru
            delay(1500)
            nextQuestion(roomId)
        }
    }


    suspend fun handleReconnect(playerId: String, session: DefaultWebSocketSession): Boolean {
        val disconnectedPlayer = disconnectedPlayers[playerId]
        if (disconnectedPlayer != null) {
            val room = rooms[disconnectedPlayer.roomId]
            if (room != null) {
                // Oyuncuyu yeniden bağla
                SessionManagerService.INSTANCE.addPlayerToSession(playerId, session)
                playerToRoom[playerId] = disconnectedPlayer.roomId
                disconnectedPlayers.remove(playerId)

                // Diğer oyuncuya bildir
                val reconnectMessage = GameMessage.PlayerReconnected(playerName = disconnectedPlayer.playerName)
                SessionManagerService.INSTANCE.broadcastToPlayers(
                    room.players.filter { it.id != playerId }.map(Player::id).toMutableList(),
                    reconnectMessage)

                // Oyunu devam ettir
                if (room.roomState == RoomState.PAUSED) {
                    room.roomState = RoomState.PLAYING
                    nextQuestion(disconnectedPlayer.roomId)
                }

                return true
            }
        }
        return false
    }


    suspend fun handleDisconnect(playerId: String) {
        val roomId = rooms.entries.find { entry ->
            entry.value.players.any { it.id == playerId }
        }?.key

        if (roomId != null) {
            val room = rooms[roomId]
            if (room != null) {
                val player = room.players.find { it.id == playerId }
                if (player != null) {
                    // Disconnected players listesine ekle
                    disconnectedPlayers[playerId] = DisconnectedPlayer(
                        playerId = playerId,
                        playerName = player.name,
                        roomId = roomId
                    )

                    // Diğer oyuncuya bildir
                    val disconnectMessage = GameMessage.PlayerDisconnected(playerName = player.name)
                    SessionManagerService.INSTANCE.broadcastToPlayers(
                        room.players.filter { it.id != playerId }.map(Player::id).toMutableList(),
                        disconnectMessage)

                    // Oyunu duraklatmak için GameState'i gncelle
                    room.roomState = RoomState.PAUSED
                    roundTimers[roomId]?.cancel() // Timer'ı durdur

                    // 30 saniye bekle ve oyuncu geri bağlanmazsa odayı temizle
                    CoroutineScope(Dispatchers.Default).launch {
                        delay(30000)
                        if (disconnectedPlayers.containsKey(playerId)) {
                            println("Player $playerId did not reconnect within 30 seconds, cleaning up room $roomId")
                            cleanupRoom(roomId)
                        }
                    }
                }
            }
        }

        SessionManagerService.INSTANCE.removePlayerSession(playerId)
    }
}