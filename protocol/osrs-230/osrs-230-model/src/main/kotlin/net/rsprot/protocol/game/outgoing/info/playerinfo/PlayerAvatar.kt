package net.rsprot.protocol.game.outgoing.info.playerinfo

import io.netty.buffer.ByteBufAllocator
import net.rsprot.compression.provider.HuffmanCodecProvider
import net.rsprot.protocol.game.outgoing.info.AvatarExtendedInfoWriter
import net.rsprot.protocol.game.outgoing.info.AvatarPriority
import net.rsprot.protocol.game.outgoing.info.filter.ExtendedInfoFilter
import net.rsprot.protocol.game.outgoing.info.util.Avatar
import net.rsprot.protocol.internal.checkCommunicationThread
import net.rsprot.protocol.internal.game.outgoing.info.CoordGrid
import net.rsprot.protocol.internal.game.outgoing.info.playerinfo.encoder.PlayerExtendedInfoEncoders

/**
 * The player avatar class represents an avatar for the purposes of player information packet.
 * Every player will have a respective avatar that contains basic information about that player,
 * such as their coordinates and how far to render other players.
 */
@Suppress("MemberVisibilityCanBePrivate")
public class PlayerAvatar internal constructor(
    allocator: ByteBufAllocator,
    localIndex: Int,
    extendedInfoFilter: ExtendedInfoFilter,
    extendedInfoWriters: List<AvatarExtendedInfoWriter<PlayerExtendedInfoEncoders, PlayerAvatarExtendedInfoBlocks>>,
    huffmanCodec: HuffmanCodecProvider,
) : Avatar {
    /**
     * The index of our local player.
     */
    public var localPlayerIndex: Int = localIndex
        internal set

    /**
     * The preferred resize range. The player information protocol will attempt to
     * add everyone within [preferredResizeRange] tiles to high resolution.
     * If [preferredResizeRange] is equal to [Int.MAX_VALUE], resizing will be disabled
     * and everyone will be put to high resolution. The extended information may be
     * disabled for these players as a result, to avoid buffer overflows.
     */
    internal var preferredResizeRange: Int = DEFAULT_RESIZE_RANGE

    /**
     * The current range at which other players can be observed.
     * By default, this value is equal to 15 game squares, however, it may dynamically
     * decrease if there are too many high resolution players nearby. It will naturally
     * restore back to the default size when the pressure starts to decrease.
     */
    internal var resizeRange: Int = preferredResizeRange

    /**
     * The current cycle counter for resizing logic.
     * Resizing by default will occur after every ten cycles. Once the
     * protocol begins decrementing the range, it will continue to do so
     * every cycle until it reaches a low enough pressure point.
     * Every 11th cycle from thereafter, it will attempt to increase it back.
     * If it succeeds, it will continue to do so every cycle, similarly to decreasing.
     * If it however fails, it will set the range lower by one tile and remain there
     * for the next ten cycles.
     */
    private var resizeCounter: Int = DEFAULT_RESIZE_INTERVAL

    /**
     * The maximum local player count that the protocol will try to stay under.
     * The default value is 250.
     */
    private var preferredPlayerCount: Int = DEFAULT_PREFERRED_PLAYER_COUNT

    /**
     * The interval in game cycles in which the resizing logic is re-attempted after failure.
     * The default value is 10 game cycles.
     */
    private var preferredResizeInterval: Int = DEFAULT_RESIZE_INTERVAL

    /**
     * The current known coordinate of the given player.
     * The coordinate property will need to be updated for all players prior to computing
     * player info packet for any of them.
     */
    public var currentCoord: CoordGrid = CoordGrid.INVALID
        private set

    /**
     * The current world that the player is on, by default the root world.
     * When a player moves onto a world entity (a ship), this value must be updated.
     */
    public var worldId: Int = PlayerInfo.ROOT_WORLD
        private set

    /**
     * The default priority for player avatars, defaulting to [AvatarPriority.LOW].
     * If set to [AvatarPriority.NORMAL], this avatar will be rendered to everyone
     * within the [preferredResizeRange], regardless of if [resizeRange] itself
     * has decreased. This is noticeable particularly in highly populated areas.
     * Developers can use this feature to give higher priority to staff members and
     * other important individuals, ensuring that even when there are >= 250 players
     * around, these important individuals will render to everyone.
     */
    internal var priority: AvatarPriority = AvatarPriority.LOW

    /**
     * The last known coordinate of this player. This property will be used in conjunction
     * with [currentCoord] to determine the coordinate delta, which is then transmitted
     * to the clients.
     */
    internal var lastCoord: CoordGrid = CoordGrid.INVALID

    /**
     * Extended info repository, commonly referred to as "masks", will track everything relevant
     * inside itself. Setting properties such as a spotanim would be done through this.
     * The [extendedInfo] is also responsible for caching the non-temporary blocks,
     * such as appearance and move speed.
     */
    public val extendedInfo: PlayerAvatarExtendedInfo =
        PlayerAvatarExtendedInfo(
            localIndex,
            extendedInfoFilter,
            extendedInfoWriters,
            allocator,
            huffmanCodec,
        )

    /**
     * Whether this avatar is completed hidden from everyone else. Note that this completely skips
     * sending any information to the client about this given avatar, it is not the same as soft
     * hiding via the appearance extended info.
     * The benefit to this function is that no plugins or RuneLite implementations can snoop on other
     * players that are meant to be hidden. The downside, however, is that because the client has no
     * knowledge of that specific avatar whatsoever, un-hiding while the player is moving is not as
     * smooth as with the appearance variant, since it first appears as if the player teleported in.
     */
    public var hidden: Boolean = false
        set(value) {
            checkCommunicationThread()
            field = value
        }

    /**
     * The [PlayerInfoProtocol.cycleCount] when this avatar was allocated.
     * We use this to determine whether to perform a re-synchronization of a player,
     * which can happen when a player is deallocated and reallocated on the same cycle,
     * which could result in other players not seeing any change take place. While rare,
     * this possibility exists, and it could result in some rather odd bugs.
     */
    internal var allocateCycle: Int = PlayerInfoProtocol.cycleCount

    /**
     * Resets all the properties of the given avatar to their default values.
     */
    internal fun reset() {
        preferredResizeRange = DEFAULT_RESIZE_RANGE
        resizeRange = preferredResizeRange
        resizeCounter = DEFAULT_RESIZE_INTERVAL
        preferredPlayerCount = DEFAULT_PREFERRED_PLAYER_COUNT
        preferredResizeInterval = DEFAULT_RESIZE_INTERVAL
        currentCoord = CoordGrid.INVALID
        lastCoord = CoordGrid.INVALID
        worldId = PlayerInfo.ROOT_WORLD
    }

    /**
     * Updates the current known coordinate of the given [PlayerAvatar].
     * This function must be called on each avatar before player info is computed.
     * @param level the current height level of the avatar.
     * @param x the x coordinate of the avatar.
     * @param z the z coordinate of the avatar (this is commonly referred to as 'y' coordinate).
     * @throws IllegalArgumentException if [level] is not in range of 0..<4, or [x]/[z] are
     * not in range of 0..<16384.
     */
    public fun updateCoord(
        level: Int,
        x: Int,
        z: Int,
    ) {
        checkCommunicationThread()
        this.currentCoord = CoordGrid(level, x, z)
    }

    /**
     * Updates the world id for a given player. Whether a player renders to you is determined
     * based on the player's distance to that world's render coord, as defined by [PlayerInfo].
     * @param worldId the new world that the player is on.
     */
    public fun updateWorld(worldId: Int) {
        checkCommunicationThread()
        require(worldId == PlayerInfo.ROOT_WORLD || worldId in 0..<PlayerInfoProtocol.PROTOCOL_CAPACITY) {
            "World id must be PlayerInfo.ROOT_WORLD, or in range of 0..<2048."
        }
        this.worldId = worldId
    }

    /**
     * Updates the previous cycle's coordinate to be the current cycle's coordinate.
     * This is called at the end of the cycle, to prepare for the next cycle.
     */
    override fun postUpdate() {
        this.lastCoord = currentCoord
    }

    /**
     * Sets the preferred resize range, effectively how far to render players from.
     * The preferred bit here means that it can resize down if there are too many
     * players around.
     * @param range the range from which to render other players.
     */
    public fun setPreferredResizeRange(range: Int) {
        checkCommunicationThread()
        this.preferredResizeRange = range
        this.resizeRange = range
    }

    /**
     * Forces the resize range to [range] while disabling the auto resizing feature.
     * @param range the range from which to render other players.
     */
    public fun forceResizeRange(range: Int) {
        checkCommunicationThread()
        this.resizeRange = range
        this.preferredResizeRange = Int.MAX_VALUE
    }

    /**
     * Sets the preferred player count limit. This is the maximum amount of players
     * that the protocol will try to render at any one time. It is a soft limit
     * and can be exceeded. The default value is 250.
     * @param limit the new preferred player count limit to assign.
     */
    public fun setPreferredPlayerCountLimit(limit: Int) {
        checkCommunicationThread()
        this.preferredPlayerCount = limit
    }

    /**
     * Resets the preferred player count limit to 250.
     */
    public fun resetPreferredPlayerCountLimit() {
        checkCommunicationThread()
        this.preferredPlayerCount = DEFAULT_PREFERRED_PLAYER_COUNT
    }

    /**
     * Sets the resize interval. This is after how many game cycles of inactivity the
     * protocol will try to expand out again, to render more players.
     * The default value is 10.
     * @param interval the interval in game cycles after how long to attempt to increase range.
     */
    public fun setResizeInterval(interval: Int) {
        checkCommunicationThread()
        this.preferredResizeInterval = interval
    }

    /**
     * Resets the resize interval to the default value of 10.
     */
    public fun resetResizeInterval() {
        checkCommunicationThread()
        this.preferredResizeInterval = DEFAULT_RESIZE_INTERVAL
    }

    /**
     * Gets the current resize range. This variable might change over time.
     */
    public fun getResizeRange(): Int = this.resizeRange

    /**
     * Gets the preferred resize range. This value represents the ideal number
     * that player info will strive towards. If the value is [Int.MAX_VALUE],
     * resizing is disabled and [getResizeRange] is what is used as a constant.
     */
    public fun getPreferredResizeRange(): Int = this.preferredResizeRange

    /**
     * Sets this avatar as high priority, meaning they will be rendered in large crowds
     * if the size of the crowd causes the view range to decrease below the preferred
     * range. As an example, a typical preferred range is 15 tiles, but if there's
     * a large crowd of people around, it might drop down to say 5 tiles. If there's
     * a player that has been marked as high priority 10 tiles away from us,
     * they will still render to us if marked as high priority.
     */
    public fun setHighPriority() {
        this.priority = AvatarPriority.NORMAL
    }

    /**
     * Sets the avatar as normal priority. This reverses the effects of [setHighPriority].
     */
    public fun setNormalPriority() {
        this.priority = AvatarPriority.LOW
    }

    /**
     * Resizes the view range according to the number of high resolution players currently observed.
     * This function will aim to keep the number of high resolution avatars at 250 or less.
     * It does so by checking if the number of high resolution avatars is greater than 250 every
     * 11 cycle interval. Once the condition is hit, every cycle thereafter, the range will decrement
     * by one, until the first cycle where the high resolution count is below the 250 threshold.
     * Once it reaches that state, it will remain there for another 11 cycles, before re-validating.
     * After those 11 cycles, if the count is less than 250, but our range is below the default of 15,
     * it will attempt to start increasing the range. It will continue to increase it by 1 tile every
     * cycle until the first cycle during which the high resolution count reaches 250+, or if the range
     * reaches the default value. If the high resolution count hits above 250 again, the cycle after that,
     * it will decrease the range back by 1 and remain there for the next 11 cycles.
     *
     * If the [preferredResizeRange] is set to [Int.MAX_VALUE], resizing is halted.
     * This is useful in cases such as heat maps, where we need all avatars to be in high resolution
     * in order for them to be drawn on the world map.
     * @param highResCount the number of avatars in high resolution view.
     */
    internal fun resize(highResCount: Int) {
        // Resizing is disabled if it is set to max int
        if (preferredResizeRange == Int.MAX_VALUE) {
            return
        }
        // If there are more than 250 avatars in high resolution,
        // the range decrements by 1 every cycle.
        if (highResCount >= preferredPlayerCount) {
            if (resizeRange > 0) {
                resizeRange--
            }
            resizeCounter = 0
            return
        }
        // If our resize counter gets high enough, the protocol will
        // try to increment the range by 1 if it's less than 15
        // otherwise, resets the counter.
        if (++resizeCounter >= preferredResizeInterval) {
            if (resizeRange < preferredResizeRange) {
                resizeRange++
            } else {
                resizeCounter = 0
            }
        }
    }

    override fun toString(): String {
        return "PlayerAvatar(" +
            "localPlayerIndex=$localPlayerIndex, " +
            "preferredResizeRange=$preferredResizeRange, " +
            "resizeRange=$resizeRange, " +
            "resizeCounter=$resizeCounter, " +
            "preferredPlayerCount=$preferredPlayerCount, " +
            "preferredResizeInterval=$preferredResizeInterval, " +
            "currentCoord=$currentCoord, " +
            "worldId=$worldId, " +
            "priority=$priority, " +
            "lastCoord=$lastCoord, " +
            "extendedInfo=$extendedInfo, " +
            "hidden=$hidden, " +
            "allocateCycle=$allocateCycle" +
            ")"
    }

    private companion object {
        /**
         * The default range of visibility of other players, in game tiles.
         */
        private const val DEFAULT_RESIZE_RANGE = 15

        /**
         * The default interval at which resizing will be checked, in game cycles.
         */
        private const val DEFAULT_RESIZE_INTERVAL = 10

        /**
         * The maximum preferred number of players in high resolution.
         * Exceeding this count will cause the view range to start lowering.
         */
        private const val DEFAULT_PREFERRED_PLAYER_COUNT = 250
    }
}
