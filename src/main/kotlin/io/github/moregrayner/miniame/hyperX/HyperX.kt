package io.github.moregrayner.miniame.hyperX

import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.*
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.*

class HyperX: JavaPlugin(), Listener {

    internal val sessions = mutableMapOf<UUID, HyperSession>()
    val playerToSession = mutableMapOf<UUID, HyperSession>()

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)

        object : BukkitRunnable() {
            override fun run() {
                sessions.values.forEach { it.updateViewpoints() }
            }
        }.runTaskTimer(this, 0L, 1L)
    }

    override fun onDisable() {
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        playerToSession.clear()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("hyperthread", ignoreCase = true)) {
            if (args.isNotEmpty() && args[0].equals("start", ignoreCase = true)) {
                if (args.size != 7) {
                    sender.sendMessage("§c사용법: /hyperthread start <mainPlayer> <headPlayer> <rightArmPlayer> <leftArmPlayer> <inventoryPlayer> <legsPlayer>")
                    return true
                }

                val playerNames = args.slice(1..6)
                val players = playerNames.mapNotNull { Bukkit.getPlayer(it) }

                if (players.size != 6) {
                    sender.sendMessage("§c모든 플레이어가 온라인 상태여야 합니다. 현재 온라인 플레이어: ${players.joinToString { it.name }}")
                    return true
                }

                val mainPlayer = players[0]
                val headPlayer = players[1]
                val rightArmPlayer = players[2]
                val leftArmPlayer = players[3]
                val inventoryPlayer = players[4]
                val legsPlayer = players[5]

                if (sessions.containsKey(mainPlayer.uniqueId)) {
                    sender.sendMessage("§c${mainPlayer.name} 플레이어는 이미 세션에 참여 중입니다.")
                    return true
                }

                try {
                    createSession(mainPlayer, headPlayer, rightArmPlayer, leftArmPlayer, inventoryPlayer, legsPlayer)
                    sender.sendMessage("세션이 성공적으로 시작되었습니다.")
                    sender.sendMessage("제작자: ${ChatColor.GOLD}[${ChatColor.WHITE}MoreGrayner${ChatColor.GOLD}]")
                } catch (e: Exception) {
                    sender.sendMessage("§c세션 시작 중 오류가 발생했습니다: ${e.message}")
                    e.printStackTrace()
                }
                return true
            } else if (args.isNotEmpty() && args[0].equals("stop", ignoreCase = true)) {
                if (args.size != 2) {
                    sender.sendMessage("§c사용법: /hyperthread stop <mainPlayer>")
                    return true
                }
                val mainPlayerName = args[1]
                val mainPlayer = Bukkit.getPlayer(mainPlayerName)

                if (mainPlayer == null || !sessions.containsKey(mainPlayer.uniqueId)) {
                    sender.sendMessage("§c${mainPlayerName} 세션 종료는 메인 플레이어만 가능합니다.")
                    return true
                }

                sessions[mainPlayer.uniqueId]?.destroy()
                sender.sendMessage("§a${mainPlayer.name}의 하이퍼스레딩 세션이 종료되었습니다.")
                return true
            }
        }
        return false
    }

    private fun createSession(
        mainPlayer: Player,
        headPlayer: Player,
        rightArmPlayer: Player,
        leftArmPlayer: Player,
        inventoryPlayer: Player,
        legsPlayer: Player
    ): HyperSession {
        val session = HyperSession(mainPlayer, headPlayer, rightArmPlayer, leftArmPlayer, inventoryPlayer, legsPlayer, this)
        sessions[mainPlayer.uniqueId] = session
        session.allControlPlayers.forEach { playerToSession[it.uniqueId] = session }
        playerToSession[mainPlayer.uniqueId] = session
        session.initialize()
        return session
    }

    private fun findSessionByPlayer(player: Player): HyperSession? {
        return playerToSession[player.uniqueId]
    }

    @EventHandler
    fun onPlayerItemConsume(event: PlayerItemConsumeEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (player in session.allControlPlayers) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (player !in session.allControlPlayers) return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (!player.isOnline) {
            player.sendMessage("§c저런! [${session.getRole(player)}](이)가 없어 아무것도 하지 못합니다!")
            event.isCancelled = true
            return
        }
        event.isCancelled = true

        when (player) {
            session.rightArmPlayer -> {
                if (event.action == Action.RIGHT_CLICK_AIR || event.action == Action.RIGHT_CLICK_BLOCK) {
                    val item = session.mainPlayer.inventory.itemInMainHand

                    if (isConsumable(item)) {
                        session.startConsuming(item)
                    } else {
                        session.handleRightArmAction(event)
                    }
                }
            }
            session.leftArmPlayer -> {
                if (event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK) {
                    session.handleLeftArmAction(event)
                }
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.leftArmPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            session.breakBlockAsMain(event)
        }
        if (player in session.allControlPlayers) event.isCancelled = true
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.rightArmPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            session.placeBlockAsMain(event)
        }
        if (player in session.allControlPlayers) event.isCancelled = true
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager is Player) {
            val session = findSessionByPlayer(damager) ?: return

            if (session.isPaused) {
                event.isCancelled = true
                return
            }

            if (damager == session.leftArmPlayer) {
                if (!damager.isOnline) {
                    damager.sendMessage("§c저런! [${session.getRole(damager)}]이 없어 아무것도 하지 못합니다!")
                    event.isCancelled = true
                    return
                }
                session.attackEntityAsMain(event.entity, event.damage)
            }
            if (damager in session.allControlPlayers) event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerToggleFlight(event: PlayerToggleFlightEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (player == session.legsPlayer) {
            if (session.isPaused) {
                event.isCancelled = true
                return
            }

            if (player.gameMode == GameMode.SPECTATOR) {
                event.isCancelled = true
                session.jumpAsMain()
            }
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (player == session.legsPlayer) {
            if (session.isPaused) {
                if (event.from.x != event.to.x || event.from.z != event.to.z) {
                    event.to = event.from
                }
                return
            }

            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }

            val from = event.from
            val to = event.to

            if (to.y > from.y && session.wasOnGround()) {
                session.jumpAsMain()
            }

            session.handleLegsMovement(event)
        } else if (player != session.mainPlayer) {
            if (event.from.x != event.to.x || event.from.z != event.to.z) {
                event.to = event.from
            }
        }
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.legsPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            session.setSneakAsMain(event.isSneaking)
        }
        if (player in session.allControlPlayers) event.isCancelled = true
    }

    @EventHandler
    fun onPlayerToggleSprint(event: PlayerToggleSprintEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.legsPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            session.setSprintAsMain(event.isSprinting)
        }
        if (player in session.allControlPlayers) event.isCancelled = true
    }

    @EventHandler
    fun onAsyncPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (player in session.allControlPlayers) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            event.isCancelled = true
            val role = session.getRole(player)
            val message = event.message

            Bukkit.getScheduler().runTask(this, Runnable {
                Bukkit.broadcastMessage("§7[${role}] §f${message}")
            })
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.headPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            if (event.slotType == InventoryType.SlotType.QUICKBAR) {
                session.mainPlayer.inventory.heldItemSlot = event.slot
            }
            event.isCancelled = true
            return
        }

        if (player == session.inventoryPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            session.handleInventoryClick(event)
        } else if (player in session.allControlPlayers) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player in session.allControlPlayers) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
        }

        if (player == session.inventoryPlayer || player == session.rightArmPlayer) {
            session.openInventoryForAll(event.inventory)
        }
        if (player in session.allControlPlayers) event.isCancelled = true
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = findSessionByPlayer(player) ?: return

        if (player == session.inventoryPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.inventory.close()
                return
            }
            session.closeInventoryForOthers()
        }
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.inventoryPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            session.dropItemAsMain(event.itemDrop.itemStack)
        }
        if (player in session.allControlPlayers) event.isCancelled = true
    }

    @EventHandler
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val entity = event.entity
        if (entity is Player) {
            val session = findSessionByPlayer(entity) ?: return

            if (session.isPaused) {
                event.isCancelled = true
                return
            }

            if (entity == session.mainPlayer) {
                if (!entity.isOnline) {
                    entity.sendMessage("§c저런! [${session.getRole(entity)}]이 없어 아무것도 하지 못합니다!")
                    event.isCancelled = true
                    return
                }
                session.pickupItemAsMain(event.item.itemStack)
                event.item.remove()
            }
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val session = findSessionByPlayer(player)

        if (session != null && session.isPaused) {
            Bukkit.getScheduler().runTaskLater(this, Runnable {
                if (player == session.mainPlayer) {
                    player.gameMode = GameMode.SURVIVAL
                } else if (player in session.allControlPlayers) {
                    player.gameMode = GameMode.SPECTATOR
                }
            }, 1L)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        val session = findSessionByPlayer(player)
        if (session != null) {
            if (player == session.mainPlayer) {
                session.destroy()
            } else {
                session.syncActionToAll("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
            }
        }
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (entity is Player) {
            val session = findSessionByPlayer(entity)
            if (session != null) {
                if (entity == session.mainPlayer) {
                    session.syncDamageToAll(event.damage, event.cause.name)

                    Bukkit.getScheduler().runTaskLater(this, Runnable {
                        session.syncHealthToAllControlPlayers()
                    }, 1L)
                } else if (entity in session.allControlPlayers) {
                    if (!entity.isOnline) {
                        entity.sendMessage("§c저런! [${session.getRole(entity)}]이 없어 아무것도 하지 못합니다!")
                        event.isCancelled = true
                        return
                    }
                    event.isCancelled = true
                    session.applyDamageToMain(event.damage, event.cause)
                }
            }
        }
    }

    @EventHandler
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        val session = findSessionByPlayer(player) ?: return
        if (player != session.mainPlayer) {
            if (!player.isOnline) {
                player.sendMessage("§c저런! [${session.getRole(player)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onEntityTarget(event: EntityTargetEvent) {
        val target = event.target as? Player ?: return
        val session = findSessionByPlayer(target) ?: return
        if (target in session.allControlPlayers) {
            if (!target.isOnline) {
                target.sendMessage("§c저런! [${session.getRole(target)}]이 없어 아무것도 하지 못합니다!")
                event.isCancelled = true
                return
            }
            event.target = session.mainPlayer
        }
    }

    @EventHandler
    fun onPlayerInteractEntity(event: PlayerInteractEntityEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (session.isPaused) {
            event.isCancelled = true
            return
        }

        if (player == session.rightArmPlayer) {
            event.isCancelled = true
            session.interactWithEntity(event.rightClicked)
        }
    }

    @EventHandler
    fun onPlayerSwapHandItems(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        val session = findSessionByPlayer(player) ?: return

        if (player in session.allControlPlayers) {
            event.isCancelled = true

            if (player == session.rightArmPlayer) {
                session.cancelConsuming()
            }
        }
    }
}

private fun isConsumable(item: ItemStack?): Boolean {
    if (item == null || item.type.isAir) return false

    val material = item.type
    return material.isEdible ||
            material == org.bukkit.Material.POTION ||
            material == org.bukkit.Material.MILK_BUCKET ||
            material.name.contains("POTION")
}

class HyperSession(
    val mainPlayer: Player,
    val headPlayer: Player,
    val rightArmPlayer: Player,
    val leftArmPlayer: Player,
    val inventoryPlayer: Player,
    val legsPlayer: Player,
    private val plugin: HyperX
) {

    val allControlPlayers = listOf(headPlayer, rightArmPlayer, leftArmPlayer, inventoryPlayer, legsPlayer)
    private val originalStates = mutableMapOf<UUID, PlayerState>()

    private var lastOnGround = false
    private var consumingTaskId: Int = -1
    private var consumingStartTime: Long = 0
    var isPaused = false
        private set

    private data class PlayerState(val gameMode: GameMode, val location: Location)

    fun initialize() {
        mainPlayer.gameMode = GameMode.SURVIVAL

        allControlPlayers.forEach { player ->
            originalStates[player.uniqueId] = PlayerState(player.gameMode, player.location)
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage("§a[하이퍼스레딩] 세션에 참가했습니다. 역할: §e${getRole(player)}")
        }
    }

    fun updateViewpoints() {
        if (!mainPlayer.isOnline) {
            destroy()
            return
        }

        if (isPaused) {
            checkAndResumeSession()
            return
        }

        val mainLoc = mainPlayer.location
        mainPlayer.teleport(mainLoc.apply {
            yaw = headPlayer.location.yaw
            pitch = headPlayer.location.pitch
        })

        val mainPlayerHealth = mainPlayer.health
        val mainPlayerMaxHealth = mainPlayer.maxHealth
        val mainPlayerFoodLevel = mainPlayer.foodLevel

        allControlPlayers.forEach { player ->
            if (player.isOnline) {
                val targetLoc = when (player) {
                    headPlayer -> mainLoc.clone().add(0.0, mainPlayer.eyeHeight, 0.0)
                    rightArmPlayer -> getShoulderLocation(mainLoc, 0.5)
                    leftArmPlayer -> getShoulderLocation(mainLoc, -0.5)
                    inventoryPlayer -> mainLoc.clone().add(mainLoc.direction.multiply(-2.0)).add(0.0, 1.0, 0.0)
                    legsPlayer -> mainLoc.clone()
                    else -> mainLoc
                }
                player.teleport(targetLoc.apply { yaw = mainLoc.yaw; pitch = mainLoc.pitch })

                player.health = mainPlayerHealth.coerceAtMost(player.maxHealth)

                val healthBar = "§c체력: ${"%.1f".format(mainPlayerHealth)}/${mainPlayerMaxHealth}"
                val foodBar = "§6배고픔: ${mainPlayerFoodLevel}/20"
                player.sendActionBar("§l$healthBar   $foodBar")
            }
        }
        syncHotbar()
    }

    private fun checkAndResumeSession() {
        val allAlive = (allControlPlayers + mainPlayer).all {
            it.isOnline && !it.isDead && it.health > 0
        }

        if (allAlive) {
            resumeSession()
        } else {
            val deadPlayers = (allControlPlayers + mainPlayer).filter {
                !it.isOnline || it.isDead || it.health <= 0
            }

            (allControlPlayers + mainPlayer).forEach { player ->
                if (player.isOnline) {
                    val deadList = deadPlayers.joinToString(", ") {
                        if (it == mainPlayer) "${it.name}(메인)"
                        else "${it.name}(${getRole(it)})"
                    }
                    player.sendActionBar("§e[대기 중] 부활 대기: $deadList")
                }
            }
        }
    }

    private fun resumeSession() {
        isPaused = false

        allControlPlayers.forEach { player ->
            if (player.isOnline) {
                player.gameMode = GameMode.SPECTATOR
            }
        }

        if (mainPlayer.isOnline) {
            mainPlayer.gameMode = GameMode.SURVIVAL
        }

        syncHealthToAllControlPlayers()

        (allControlPlayers + mainPlayer).forEach { player ->
            if (player.isOnline) {
                player.sendMessage("§a[하이퍼스레딩] 세션이 재개되었습니다!")
            }
        }
    }

    private fun pauseSession() {
        isPaused = true
        cancelConsuming()

        (allControlPlayers + mainPlayer).forEach { player ->
            if (player.isOnline) {
                player.sendMessage("§c[하이퍼스레딩] 세션이 일시정지되었습니다. 모든 플레이어가 부활할 때까지 대기 중...")
            }
        }
    }

    private fun getShoulderLocation(loc: Location, offset: Double): Location {
        val rightDir = loc.direction.clone().crossProduct(Vector(0, 1, 0)).normalize().multiply(offset)
        return loc.clone().add(rightDir).add(0.0, mainPlayer.eyeHeight - 0.4, 0.0)
    }

    private fun syncHotbar() {
        for (i in 0..8) {
            headPlayer.inventory.setItem(i, mainPlayer.inventory.getItem(i)?.clone())
        }
        headPlayer.updateInventory()
    }

    fun syncActionToAll(message: String) {
        (allControlPlayers + mainPlayer).forEach { it.sendActionBar("§e$message") }
    }

    fun syncDamageToAll(damage: Double, cause: String) {
        syncActionToAll("§c[경고] 데미지: ${String.format("%.1f", damage)} ($cause)")
    }

    fun syncHealthToAllControlPlayers() {
        val mainHealth = mainPlayer.health
        allControlPlayers.forEach { player ->
            if (player.isOnline) {
                player.health = mainHealth.coerceAtMost(player.maxHealth)
            }
        }
    }

    fun applyDamageToMain(damage: Double, cause: org.bukkit.event.entity.EntityDamageEvent.DamageCause) {
        val newHealth = (mainPlayer.health - damage).coerceAtLeast(0.0)
        mainPlayer.health = newHealth

        syncActionToAll("§c[경고] 데미지: ${String.format("%.1f", damage)} (${cause.name})")

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            syncHealthToAllControlPlayers()
        }, 1L)

        if (newHealth <= 0.0) {
            pauseSession()
        }
    }

    fun jumpAsMain() {
        if (mainPlayer.isOnGround || mainPlayer.location.block.type == org.bukkit.Material.WATER) {
            mainPlayer.velocity = mainPlayer.velocity.setY(0.42)
            syncActionToAll("[다리] 점프")
        }
    }

    fun wasOnGround(): Boolean {
        val current = mainPlayer.isOnGround
        val result = lastOnGround
        lastOnGround = current
        return result
    }

    fun startConsuming(item: ItemStack) {
        if (consumingTaskId != -1) return

        consumingStartTime = System.currentTimeMillis()
        syncActionToAll("[오른팔] ${item.type} 먹는 중...")

        val consumeTicks = when {
            item.type.name.contains("POTION") -> 32L
            item.type == org.bukkit.Material.MILK_BUCKET -> 32L
            item.type.isEdible -> 32L
            else -> 32L
        }

        consumingTaskId = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            finishConsuming(item)
        }, consumeTicks).taskId
    }

    private fun finishConsuming(item: ItemStack) {
        consumingTaskId = -1

        val itemInHand = mainPlayer.inventory.itemInMainHand
        if (itemInHand.isSimilar(item)) {
            when {
                item.type.isEdible -> {
                    val food = item.type
                    val restoreAmount = getFoodRestoration(food)
                    val saturation = getFoodSaturation(food)

                    mainPlayer.foodLevel = (mainPlayer.foodLevel + restoreAmount).coerceAtMost(20)
                    mainPlayer.saturation = (mainPlayer.saturation + saturation).coerceAtMost(20f)

                    syncActionToAll("§a[오른팔] ${item.type} 먹음 (배고픔 +${restoreAmount})")
                }
                item.type.name.contains("POTION") -> {
                    syncActionToAll("§a[오른팔] 포션 마심")
                }
                item.type == org.bukkit.Material.MILK_BUCKET -> {
                    mainPlayer.activePotionEffects.forEach { mainPlayer.removePotionEffect(it.type) }
                    syncActionToAll("§a[오른팔] 우유 마심 (효과 제거)")
                }
            }

            if (itemInHand.amount > 1) {
                itemInHand.amount -= 1
            } else {
                mainPlayer.inventory.setItemInMainHand(null)
            }

            when (item.type) {
                org.bukkit.Material.POTION -> {
                    mainPlayer.inventory.addItem(ItemStack(org.bukkit.Material.GLASS_BOTTLE))
                }
                org.bukkit.Material.MILK_BUCKET -> {
                    mainPlayer.inventory.addItem(ItemStack(org.bukkit.Material.BUCKET))
                }
                else -> {}
            }

            mainPlayer.updateInventory()
            syncHotbar()
        }
    }

    fun cancelConsuming() {
        if (consumingTaskId != -1) {
            Bukkit.getScheduler().cancelTask(consumingTaskId)
            consumingTaskId = -1
            syncActionToAll("§c[오른팔] 먹기/마시기 취소")
        }
    }

    fun interactWithEntity(entity: Entity) {
        syncActionToAll("[오른팔] ${entity.type}와 상호작용")
    }

    private fun getFoodRestoration(food: org.bukkit.Material): Int {
        return when (food) {
            org.bukkit.Material.APPLE -> 4
            org.bukkit.Material.GOLDEN_APPLE -> 4
            org.bukkit.Material.BREAD -> 5
            org.bukkit.Material.COOKED_BEEF, org.bukkit.Material.COOKED_PORKCHOP -> 8
            org.bukkit.Material.COOKED_CHICKEN, org.bukkit.Material.COOKED_MUTTON -> 6
            org.bukkit.Material.COOKED_COD, org.bukkit.Material.COOKED_SALMON -> 6
            org.bukkit.Material.BAKED_POTATO -> 5
            org.bukkit.Material.COOKIE -> 2
            org.bukkit.Material.MELON_SLICE -> 2
            org.bukkit.Material.CARROT, org.bukkit.Material.POTATO -> 1
            org.bukkit.Material.BEETROOT -> 1
            org.bukkit.Material.CHORUS_FRUIT -> 4
            else -> 2
        }
    }

    private fun getFoodSaturation(food: org.bukkit.Material): Float {
        return when (food) {
            org.bukkit.Material.GOLDEN_APPLE -> 9.6f
            org.bukkit.Material.COOKED_BEEF, org.bukkit.Material.COOKED_PORKCHOP -> 12.8f
            org.bukkit.Material.COOKED_SALMON -> 9.6f
            org.bukkit.Material.BREAD -> 6.0f
            else -> 1.2f
        }
    }

    fun destroy() {
        cancelConsuming()
        allControlPlayers.forEach { player ->
            originalStates[player.uniqueId]?.let { state ->
                if (player.isOnline) {
                    player.gameMode = state.gameMode
                    player.teleport(state.location)
                }
            }
            plugin.playerToSession.remove(player.uniqueId)
        }
        plugin.playerToSession.remove(mainPlayer.uniqueId)
        plugin.sessions.remove(mainPlayer.uniqueId)
        mainPlayer.sendMessage("§c[하이퍼스레딩] 세션이 종료되었습니다.")
    }

    fun handleRightArmAction(event: PlayerInteractEvent) {
        val fakeEvent = PlayerInteractEvent(mainPlayer, event.action, event.item, event.clickedBlock, event.blockFace, event.hand)
        Bukkit.getPluginManager().callEvent(fakeEvent)
        if (!fakeEvent.isCancelled) {
            mainPlayer.swingMainHand()
            syncActionToAll("[오른팔] ${event.action}")
        }
    }

    fun handleLeftArmAction(event: PlayerInteractEvent) {
        val fakeEvent = PlayerInteractEvent(mainPlayer, event.action, event.item, event.clickedBlock, event.blockFace, event.hand)
        Bukkit.getPluginManager().callEvent(fakeEvent)
        if (!fakeEvent.isCancelled) {
            mainPlayer.swingOffHand()
            syncActionToAll("[왼팔] ${event.action}")
        }
    }

    fun breakBlockAsMain(event: BlockBreakEvent) {
        val fakeEvent = BlockBreakEvent(event.block, mainPlayer)
        Bukkit.getPluginManager().callEvent(fakeEvent)
        if (!fakeEvent.isCancelled) {
            syncActionToAll("[왼팔] ${event.block.type} 파괴")
        }
    }

    fun placeBlockAsMain(event: BlockPlaceEvent) {
        val fakeEvent = BlockPlaceEvent(event.block, event.blockReplacedState, event.blockAgainst, event.itemInHand, mainPlayer, true, event.hand)
        Bukkit.getPluginManager().callEvent(fakeEvent)
        if (!fakeEvent.isCancelled) {
            syncActionToAll("[오른팔] ${event.block.type} 설치")
        }
    }

    fun attackEntityAsMain(entity: Entity, damage: Double) {
        if (entity is LivingEntity) {
            entity.damage(damage, mainPlayer)
            mainPlayer.swingMainHand()
            syncActionToAll("[왼팔] ${entity.type} 공격")
        }
    }

    fun handleLegsMovement(event: PlayerMoveEvent) {
        val movement = event.to.toVector().subtract(event.from.toVector())
        if (movement.lengthSquared() > 0) {
            mainPlayer.velocity = mainPlayer.velocity.add(movement)
        }
    }

    fun setSneakAsMain(sneaking: Boolean) {
        mainPlayer.isSneaking = sneaking
        syncActionToAll("[다리] 웅크리기: $sneaking")
    }

    fun setSprintAsMain(sprinting: Boolean) {
        mainPlayer.isSprinting = sprinting
        syncActionToAll("[다리] 달리기: $sprinting")
    }

    fun handleInventoryClick(event: InventoryClickEvent) {
        event.isCancelled = true

        val clickedInventory = event.clickedInventory ?: return
        val currentItem = event.currentItem
        val cursor = event.cursor
        val slot = event.slot
        val action = event.action

        val mainPlayerInventory = mainPlayer.inventory

        when (action) {
            org.bukkit.event.inventory.InventoryAction.PICKUP_ALL, org.bukkit.event.inventory.InventoryAction.PICKUP_HALF,
            org.bukkit.event.inventory.InventoryAction.PICKUP_ONE, org.bukkit.event.inventory.InventoryAction.PICKUP_SOME -> {
                if (currentItem != null) {
                    mainPlayerInventory.setItem(slot, null)
                    mainPlayer.setItemOnCursor(currentItem)
                }
            }
            org.bukkit.event.inventory.InventoryAction.PLACE_ALL, org.bukkit.event.inventory.InventoryAction.PLACE_ONE,
            org.bukkit.event.inventory.InventoryAction.PLACE_SOME -> {
                mainPlayerInventory.setItem(slot, cursor)
                mainPlayer.setItemOnCursor(null)
            }
            org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR -> {
                val temp = mainPlayerInventory.getItem(slot)
                mainPlayerInventory.setItem(slot, cursor)
                mainPlayer.setItemOnCursor(temp)
            }
            org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY -> {
                if (currentItem != null) {
                    val targetInventory = if (clickedInventory.type == InventoryType.PLAYER) {
                        mainPlayer.openInventory.bottomInventory
                    } else {
                        mainPlayer.inventory
                    }
                    targetInventory.addItem(currentItem)
                    mainPlayerInventory.setItem(slot, null)
                }
            }
            else -> {
                if (event.rawSlot < mainPlayerInventory.size) {
                    mainPlayerInventory.setItem(event.rawSlot, currentItem)
                }
                mainPlayer.setItemOnCursor(cursor)
            }
        }

        mainPlayer.updateInventory()
        inventoryPlayer.updateInventory()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { syncInventoryToOthers(mainPlayer.openInventory.topInventory) }, 1L)
        syncActionToAll("[인벤토리] 인벤토리 조작")
    }

    fun openInventoryForAll(inventory: Inventory) {
        mainPlayer.openInventory(inventory)
        inventoryPlayer.openInventory(inventory)
        syncInventoryToOthers(inventory)
    }

    fun closeInventoryForOthers() {
        (allControlPlayers - inventoryPlayer).forEach { it.closeInventory() }
    }

    private fun syncInventoryToOthers(sourceInventory: Inventory) {
        (allControlPlayers - inventoryPlayer).forEach { player ->
            val readOnlyInv = Bukkit.createInventory(null, sourceInventory.size, "§8[관전] ${sourceInventory.type.defaultTitle}")
            readOnlyInv.contents = sourceInventory.contents.map { item ->
                item?.clone()?.apply {
                    itemMeta = itemMeta?.apply {
                        lore = (lore ?: listOf()) + "§c클릭 불가"
                    }
                }
            }.toTypedArray()
            player.openInventory(readOnlyInv)
        }
    }

    fun dropItemAsMain(item: ItemStack) {
        mainPlayer.world.dropItem(mainPlayer.location, item)
        syncActionToAll("[인벤토리] ${item.type} 버림")
        syncInventoryToOthers(mainPlayer.inventory)
    }

    fun pickupItemAsMain(item: ItemStack) {
        val remaining = mainPlayer.inventory.addItem(item)
        remaining.values.forEach { mainPlayer.world.dropItem(mainPlayer.location, it) }
        syncActionToAll("[인벤토리] ${item.type} 주움")
        syncInventoryToOthers(mainPlayer.inventory)
    }

    fun getRole(player: Player): String = when (player) {
        headPlayer -> "머리"
        rightArmPlayer -> "오른팔"
        leftArmPlayer -> "왼팔"
        inventoryPlayer -> "인벤토리"
        legsPlayer -> "다리"
        else -> "알 수 없음"
    }
}