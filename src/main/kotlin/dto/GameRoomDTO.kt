package dto

import kotlinx.serialization.Serializable
import model.RoomState

@Serializable
data class GameRoomDTO(
    val id: String,
    val playerCount: Int,
    val roomState: RoomState,
    val players: List<String> // oyuncu isimleri
)